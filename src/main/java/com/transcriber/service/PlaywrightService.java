package com.transcriber.service;

import com.microsoft.playwright.*;
import com.microsoft.playwright.options.LoadState;
import com.transcriber.model.MeetingSession;
import com.transcriber.model.TranscriptEntry;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.ZonedDateTime;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

@Slf4j
@Service
public class PlaywrightService {

    @Value("${meeting.bot-name:Alexa}")
    private String botName;

    @Value("${playwright.headless:true}")
    private boolean headless;

    @Value("${playwright.user-data-dir:/tmp/playwright-data}")
    private String userDataDir;

    @Value("${playwright.slow-mo:100}")
    private int slowMo;

    @Value("${meeting.transcript-path:/tmp/transcripts}")
    private String transcriptPath;

    @Value("${meeting.admission-timeout-seconds:120}")
    private int admissionTimeoutSeconds;

    // Track active browser contexts per meeting
    private final ConcurrentHashMap<String, BrowserContext> activeContexts = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicBoolean> stopFlags = new ConcurrentHashMap<>();

    @PostConstruct
    public void init() {
        // Validate Playwright is available by creating and immediately closing an instance
        try (Playwright pw = Playwright.create()) {
            log.info("Playwright verified and available (version check on startup)");
        } catch (Exception e) {
            log.error("Playwright NOT available! Install browsers with: mvn exec:java -e -Dexec.mainClass=com.microsoft.playwright.CLI -Dexec.args=\"install chromium\"");
            throw new RuntimeException("Playwright initialization failed", e);
        }
    }

    @PreDestroy
    public void cleanup() {
        activeContexts.values().forEach(context -> {
            try {
                context.close();
            } catch (Exception e) {
                log.warn("Error closing browser context: {}", e.getMessage());
            }
        });
        log.info("Playwright resources cleaned up");
    }

    /**
     * Main method to join meeting and capture transcripts
     */
    public void joinMeetingAndCapture(MeetingSession session) {
        String uuid = session.getUuid();
        stopFlags.put(uuid, new AtomicBoolean(false));
        
        // Each meeting gets its own Playwright instance on the calling thread.
        // Playwright Java is NOT thread-safe -- the instance must be created and
        // used on the same thread, so we cannot share a single @PostConstruct instance.
        Playwright playwright = null;
        BrowserContext context = null;
        Page page = null;

        try {
            log.info("[{}] Creating Playwright instance on thread: {}", uuid, Thread.currentThread().getName());
            playwright = Playwright.create();

            log.info("[{}] Starting browser for meeting: {}", uuid, session.getMeetUrl());
            log.info("[{}] Using anti-detection mode to bypass bot detection", uuid);
            session.setStatus(MeetingSession.MeetingStatus.JOINING);

            // Create browser with anti-detection measures to avoid Google bot blocking
            Browser browser = playwright.chromium().launch(new BrowserType.LaunchOptions()
                    .setHeadless(headless)
                    .setSlowMo(slowMo)
                    .setArgs(java.util.List.of(
                            // Media permissions (use fake stream to avoid needing real camera/mic)
                            "--use-fake-ui-for-media-stream",
                            "--use-fake-device-for-media-stream",
                            // NOTE: Do NOT use --auto-accept-camera-and-microphone-capture - it conflicts with fake-ui
                            
                            // Anti-detection: hide automation flags
                            "--disable-blink-features=AutomationControlled",
                            
                            // Appear as normal browser
                            "--disable-infobars",
                            "--no-first-run",
                            "--no-default-browser-check",
                            
                            // Performance & stability
                            "--disable-gpu",
                            "--disable-dev-shm-usage",
                            "--no-sandbox"
                    )));

            // Real Chrome user agent to avoid detection
            String realUserAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36";

            context = browser.newContext(new Browser.NewContextOptions()
                    .setPermissions(java.util.List.of("microphone", "camera", "notifications"))
                    .setViewportSize(1280, 720)
                    .setUserAgent(realUserAgent)
                    .setLocale("en-US")
                    .setTimezoneId("Asia/Kolkata"));
            
            activeContexts.put(uuid, context);
            page = context.newPage();
            
            // Inject anti-detection JavaScript before any page load
            page.addInitScript("() => {\n" +
                    "  // Hide webdriver flag\n" +
                    "  Object.defineProperty(navigator, 'webdriver', { get: () => undefined });\n" +
                    "  \n" +
                    "  // Hide automation indicators\n" +
                    "  window.chrome = { runtime: {} };\n" +
                    "  \n" +
                    "  // Override permissions query\n" +
                    "  const originalQuery = window.navigator.permissions.query;\n" +
                    "  window.navigator.permissions.query = (parameters) => (\n" +
                    "    parameters.name === 'notifications' ?\n" +
                    "      Promise.resolve({ state: Notification.permission }) :\n" +
                    "      originalQuery(parameters)\n" +
                    "  );\n" +
                    "  \n" +
                    "  // Hide plugins length\n" +
                    "  Object.defineProperty(navigator, 'plugins', {\n" +
                    "    get: () => [1, 2, 3, 4, 5]\n" +
                    "  });\n" +
                    "  \n" +
                    "  // Hide languages\n" +
                    "  Object.defineProperty(navigator, 'languages', {\n" +
                    "    get: () => ['en-US', 'en']\n" +
                    "  });\n" +
                    "}");

            // Navigate to Meet URL (anonymous join - no Google sign-in)
            log.info("[{}] Navigating to Meet URL", uuid);
            page.navigate(session.getMeetUrl(), new Page.NavigateOptions()
                    .setTimeout(60000));
            try {
                page.waitForLoadState(LoadState.NETWORKIDLE, new Page.WaitForLoadStateOptions().setTimeout(20000));
            } catch (Exception e) {
                log.debug("[{}] NETWORKIDLE not reached, continuing anyway", uuid);
            }
            
            // Give the page time to render (reduced from 7s to 3s)
            log.info("[{}] Waiting for page to load...", uuid);
            Thread.sleep(3000);

            // Handle pre-join screen (set name to Alexa, turn off cam/mic)
            handlePreJoinScreen(page, uuid);

            // Join the meeting
            joinMeeting(page, uuid);
            session.setStatus(MeetingSession.MeetingStatus.IN_PROGRESS);

            // Safety net: handle any remaining consent/notification dialogs
            // (Gemini notes, recording consent, self-view "Got it", etc.)
            handleRecordingConsentDialogs(page, uuid);

            // Enable captions (reduced delay from 2s to 1s)
            Thread.sleep(1000);
            enableCaptions(page, uuid);

            // Capture transcripts until end time
            captureTranscripts(page, session);

            session.setStatus(MeetingSession.MeetingStatus.COMPLETED);
            log.info("[{}] Meeting completed successfully. Total transcripts: {}", 
                    uuid, session.getTranscripts().size());

        } catch (Exception e) {
            log.error("[{}] Error during meeting: {}", uuid, e.getMessage(), e);
            session.setStatus(MeetingSession.MeetingStatus.FAILED);
            session.setErrorMessage(e.getMessage());
        } finally {
            // Leave meeting and cleanup
            if (page != null) {
                try {
                    leaveMeeting(page, uuid);
                } catch (Exception e) {
                    log.warn("[{}] Error leaving meeting: {}", uuid, e.getMessage());
                }
            }
            if (context != null) {
                try {
                    context.close();
                } catch (Exception e) {
                    log.warn("[{}] Error closing context: {}", uuid, e.getMessage());
                }
            }
            // Close the per-meeting Playwright instance (and its Node.js process)
            if (playwright != null) {
                try {
                    playwright.close();
                    log.info("[{}] Playwright instance closed", uuid);
                } catch (Exception e) {
                    log.warn("[{}] Error closing Playwright: {}", uuid, e.getMessage());
                }
            }
            activeContexts.remove(uuid);
            stopFlags.remove(uuid);
        }
    }

    /**
     * Signal to stop capturing for a meeting
     */
    public void stopCapturing(String uuid) {
        AtomicBoolean flag = stopFlags.get(uuid);
        if (flag != null) {
            flag.set(true);
            log.info("[{}] Stop signal sent", uuid);
        }
    }

    private void handlePreJoinScreen(Page page, String uuid) throws Exception {
        log.info("[{}] Handling pre-join screen", uuid);

        // Wait for pre-join screen to load (reduced from 5s to 2s)
        Thread.sleep(2000);

        // Turn off camera if toggle exists
        try {
            Locator cameraButton = page.locator("[data-is-muted='false'][aria-label*='camera' i], [aria-label*='Turn off camera' i]");
            if (cameraButton.count() > 0) {
                cameraButton.first().click();
                log.info("[{}] Camera turned off", uuid);
                Thread.sleep(500);
            }
        } catch (Exception e) {
            log.debug("[{}] Camera toggle not found or already off", uuid);
        }

        // Turn off microphone if toggle exists
        try {
            Locator micButton = page.locator("[data-is-muted='false'][aria-label*='microphone' i], [aria-label*='Turn off microphone' i]");
            if (micButton.count() > 0) {
                micButton.first().click();
                log.info("[{}] Microphone turned off", uuid);
                Thread.sleep(500);
            }
        } catch (Exception e) {
            log.debug("[{}] Microphone toggle not found or already off", uuid);
        }

        // Set display name if input exists
        try {
            Locator nameInput = page.locator("input[aria-label*='name' i], input[placeholder*='name' i]");
            if (nameInput.count() > 0 && nameInput.isVisible()) {
                nameInput.clear();
                nameInput.fill(botName);
                log.info("[{}] Bot name set to: {}", uuid, botName);
            }
        } catch (Exception e) {
            log.debug("[{}] Name input not found", uuid);
        }
    }

    private void joinMeeting(Page page, String uuid) throws Exception {
        log.info("[{}] Attempting to join meeting", uuid);

        // Wait for pre-join UI to settle (reduced from 3s to 1.5s)
        Thread.sleep(1500);

        // Check for "You can't join" / meeting doesn't allow guests AT ALL
        if (page.locator("text='You can't join this video call'").count() > 0
                || page.locator("text='Return to home screen'").count() > 0
                || page.locator("text='Returning to home screen'").count() > 0) {
            saveFailureScreenshot(page, uuid);
            throw new RuntimeException(
                    "Meeting COMPLETELY BLOCKED for guests. This meeting requires you to be signed in with an allowed Google account, " +
                    "OR the host must change settings to allow 'Anyone with the link' to join. " +
                    "The bot cannot join this meeting as an anonymous user.");
        }

        // Check for "meeting hasn't started" or "waiting for host"
        if (page.locator("text='Waiting for the host'").count() > 0
                || page.locator("text='The meeting hasn't started'").count() > 0
                || page.locator("text='waiting for someone to let you in'").count() > 0) {
            log.info("[{}] Meeting hasn't started yet or waiting for host. Will wait...", uuid);
        }

        // Wait for join/ask button to appear (up to 15s) – Google Meet can load slowly
        try {
            page.locator("button[aria-label*='Join' i], button[aria-label*='Ask' i], button:has-text('Join'), button:has-text('Ask to join'), button:has-text('Request to join')")
                    .first().waitFor(new Locator.WaitForOptions().setTimeout(15000));
        } catch (Exception e) {
            log.debug("[{}] No join button appeared within 15s", uuid);
        }

        // Try multiple selectors for the join button (Google Meet changes these often)
        // Prioritize direct "Join now" over "Ask to join"
        String[] directJoinSelectors = {
                "button[aria-label*='Join now' i]",
                "button[aria-label*='Join meeting' i]",
                "button:has-text('Join now')",
                "button:has-text('Join meeting')",
                "[role='button']:has-text('Join now')"
        };
        
        String[] askToJoinSelectors = {
                "button[aria-label*='Ask to join' i]",
                "button[aria-label*='Request to join' i]",
                "button:has-text('Ask to join')",
                "button:has-text('Request to join')",
                "[role='button']:has-text('Ask to join')",
                "[role='button']:has-text('Request to join')"
        };
        
        String[] fallbackSelectors = {
                "button:has-text('Join')",
                "[role='button']:has-text('Join')",
                "button[jsname='Qx7uuf']",
                "[data-idom-class*='join'] button",
                "span:has-text('Join now')",
                "span:has-text('Ask to join')"
        };

        boolean joined = false;
        boolean askedToJoin = false;
        
        // First try direct join buttons
        for (String selector : directJoinSelectors) {
            try {
                Locator joinButton = page.locator(selector).first();
                joinButton.waitFor(new Locator.WaitForOptions().setTimeout(1000));
                if (joinButton.isVisible()) {
                    joinButton.click();
                    log.info("[{}] Clicked DIRECT join button: {}", uuid, selector);
                    joined = true;
                    break;
                }
            } catch (Exception e) {
                // Not found, continue
            }
        }
        
        // If no direct join, try "Ask to join" buttons
        if (!joined) {
            for (String selector : askToJoinSelectors) {
                try {
                    Locator joinButton = page.locator(selector).first();
                    joinButton.waitFor(new Locator.WaitForOptions().setTimeout(1000));
                    if (joinButton.isVisible()) {
                        joinButton.click();
                        log.info("[{}] Clicked ASK TO JOIN button: {}", uuid, selector);
                        askedToJoin = true;
                        joined = true;
                        break;
                    }
                } catch (Exception e) {
                    // Not found, continue
                }
            }
        }
        
        // Try fallback selectors
        if (!joined) {
            for (String selector : fallbackSelectors) {
                try {
                    Locator joinButton = page.locator(selector).first();
                    joinButton.waitFor(new Locator.WaitForOptions().setTimeout(1000));
                    if (joinButton.isVisible()) {
                        String buttonText = joinButton.textContent().toLowerCase();
                        joinButton.click();
                        log.info("[{}] Clicked fallback join button: {}", uuid, selector);
                        askedToJoin = buttonText.contains("ask") || buttonText.contains("request");
                        joined = true;
                        break;
                    }
                } catch (Exception e) {
                    // Not found, continue
                }
            }
        }

        // Try getByRole with flexible name
        if (!joined) {
            try {
                Locator byRole = page.getByRole(com.microsoft.playwright.options.AriaRole.BUTTON,
                        new Page.GetByRoleOptions().setName(Pattern.compile("join|ask to join|request to join|join now|join meeting", Pattern.CASE_INSENSITIVE)));
                if (byRole.count() > 0 && byRole.first().isVisible()) {
                    String buttonText = byRole.first().textContent().toLowerCase();
                    byRole.first().click();
                    log.info("[{}] Clicked join button via getByRole", uuid);
                    askedToJoin = buttonText.contains("ask") || buttonText.contains("request");
                    joined = true;
                }
            } catch (Exception e) {
                log.debug("[{}] getByRole join button failed: {}", uuid, e.getMessage());
            }
        }

        // Try inside iframes
        if (!joined && page.frames().size() > 1) {
            for (Frame frame : page.frames()) {
                if (frame == page.mainFrame()) continue;
                try {
                    Locator inFrame = frame.locator("button[aria-label*='Join' i], button:has-text('Join'), [role='button']:has-text('Join')").first();
                    inFrame.waitFor(new Locator.WaitForOptions().setTimeout(2000));
                    if (inFrame.isVisible()) {
                        inFrame.click();
                        log.info("[{}] Clicked join button inside iframe", uuid);
                        joined = true;
                        break;
                    }
                } catch (Exception e) {
                    // skip this frame
                }
            }
        }

        if (!joined) {
            saveFailureScreenshot(page, uuid);
            throw new RuntimeException("Could not find any join button. The meeting page may not have loaded properly. Check screenshot.");
        }

        // Handle recording/Gemini consent dialogs that may appear after clicking join
        // (e.g. "This video call is being recorded and transcribed. Gemini is taking notes.")
        Thread.sleep(2000);
        handleRecordingConsentDialogs(page, uuid);

        // If we clicked "Ask to join", wait for host to admit us (up to 2 minutes)
        if (askedToJoin) {
            log.info("[{}] Waiting for host to admit us into the meeting (up to {} seconds)...", uuid, admissionTimeoutSeconds);
            boolean admitted = waitForAdmission(page, uuid, admissionTimeoutSeconds);
            if (!admitted) {
                saveFailureScreenshot(page, uuid);
                throw new RuntimeException("Host did not admit the bot within " + admissionTimeoutSeconds + " seconds. The bot requested to join but was not let in.");
            }
            log.info("[{}] Successfully admitted to meeting!", uuid);
            
            // Consent dialogs can appear AFTER admission with a delay.
            // Wait long enough for them to render before checking.
            Thread.sleep(2000);
            handleRecordingConsentDialogs(page, uuid);
        }

        // Wait for meeting to load
        Thread.sleep(2000);
        
        // Final check for any late-appearing consent dialogs
        handleRecordingConsentDialogs(page, uuid);
        
        try {
            page.waitForLoadState(LoadState.NETWORKIDLE, new Page.WaitForLoadStateOptions().setTimeout(5000));
        } catch (Exception e) {
            log.debug("[{}] NETWORKIDLE timeout, continuing anyway", uuid);
        }
        log.info("[{}] Successfully joined meeting", uuid);
    }

    /**
     * Handle consent/notification dialogs that appear when joining an organizational meeting:
     *   1) "This video call is being recorded and transcribed. Gemini is taking notes."  (full dialog)
     *   2) "Gemini is taking notes"  (smaller dialog)
     *   3) "Your self view tile may show less..." / "Got it"  (self-view notification)
     *
     * All of these block the meeting until the user clicks "Join now" or "Got it".
     * We retry a few times because dialogs can appear with a delay.
     */
    /**
     * Handle consent/notification dialogs that appear when joining an organizational meeting.
     * Uses JavaScript-based detection for reliability (Playwright text selectors can miss
     * dynamically rendered dialog text).
     *
     * Returns true if a dialog was found and dismissed, false otherwise.
     */
    private boolean handleRecordingConsentDialogs(Page page, String uuid) {
        log.info("[{}] Checking for recording/Gemini consent dialogs...", uuid);

        boolean anyDismissed = false;
        int maxAttempts = 5;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            boolean foundDialog = false;

            try {
                // --- Use JavaScript to detect AND dismiss consent dialogs ---
                // This is more reliable than Playwright selectors for dynamically rendered dialogs
                String detectAndDismissJS = """
                    (function() {
                        let result = { found: false, text: '', clicked: '', gotIt: false };

                        // 1) Look for consent dialog text
                        let consentPhrases = [
                            'Gemini is taking notes',
                            'being recorded and transcribed',
                            'being recorded',
                            'This video call is being recorded',
                            'This call is being recorded',
                            'recording in progress'
                        ];

                        let body = document.body ? document.body.innerText : '';
                        for (let phrase of consentPhrases) {
                            if (body.includes(phrase)) {
                                result.found = true;
                                result.text = phrase;
                                break;
                            }
                        }

                        // 2) If consent dialog detected, click "Join now"
                        if (result.found) {
                            // Find all clickable elements with "Join now" text
                            let allEls = document.querySelectorAll('button, [role="button"], a, span, div[role="link"], [tabindex]');
                            let joinCandidates = [];
                            for (let el of allEls) {
                                let t = (el.textContent || '').trim();
                                if (t === 'Join now') {
                                    joinCandidates.push(el);
                                }
                            }
                            // Click the last matching "Join now" (the consent dialog one, not pre-join)
                            if (joinCandidates.length > 0) {
                                let target = joinCandidates[joinCandidates.length - 1];
                                target.click();
                                result.clicked = 'join-now:' + joinCandidates.length;
                            } else {
                                result.clicked = 'not-found';
                            }
                        }

                        // 3) Also handle "Got it" notification (self-view, etc.)
                        let allBtns = document.querySelectorAll('button, [role="button"]');
                        for (let btn of allBtns) {
                            let t = (btn.textContent || '').trim();
                            if (t === 'Got it' && btn.offsetParent !== null) {
                                btn.click();
                                result.gotIt = true;
                                break;
                            }
                        }

                        return JSON.stringify(result);
                    })()
                    """;

                Object rawResult = page.evaluate(detectAndDismissJS);
                String resultStr = rawResult != null ? rawResult.toString() : "{}";
                log.debug("[{}] Consent dialog JS result: {}", uuid, resultStr);

                boolean consentFound = resultStr.contains("\"found\":true");
                boolean joinClicked = resultStr.contains("join-now:");
                boolean gotItClicked = resultStr.contains("\"gotIt\":true");

                if (consentFound) {
                    foundDialog = true;
                    if (joinClicked) {
                        log.info("[{}] Dismissed consent dialog via JS (clicked 'Join now')", uuid);
                        anyDismissed = true;
                        Thread.sleep(1500);
                    } else {
                        log.warn("[{}] Consent dialog detected but 'Join now' not found (attempt {}/{})", uuid, attempt, maxAttempts);
                        // Fallback: try Playwright locators
                        try {
                            Locator joinBtn = page.locator("text='Join now'");
                            if (joinBtn.count() > 0 && joinBtn.last().isVisible()) {
                                joinBtn.last().click();
                                log.info("[{}] Dismissed consent dialog via Playwright locator", uuid);
                                anyDismissed = true;
                                Thread.sleep(1500);
                            }
                        } catch (Exception e) {
                            log.debug("[{}] Playwright fallback for consent failed: {}", uuid, e.getMessage());
                        }
                    }
                }

                if (gotItClicked) {
                    log.info("[{}] Dismissed 'Got it' notification", uuid);
                    foundDialog = true;
                    anyDismissed = true;
                    Thread.sleep(500);
                }

                // If no dialog was found on this attempt, we're done
                if (!foundDialog) {
                    if (attempt == 1) {
                        log.info("[{}] No consent/notification dialogs detected", uuid);
                    }
                    break;
                }

                // Brief pause before checking for more dialogs
                Thread.sleep(1000);

            } catch (Exception e) {
                log.debug("[{}] Error checking consent dialogs (attempt {}): {}", uuid, attempt, e.getMessage());
                try { Thread.sleep(1000); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); return anyDismissed; }
            }
        }
        return anyDismissed;
    }

    private void saveFailureScreenshot(Page page, String uuid) {
        try {
            Path dir = Paths.get(transcriptPath);
            Files.createDirectories(dir);
            Path screenshotPath = dir.resolve("join-failed_" + uuid + "_" + System.currentTimeMillis() + ".png");
            page.screenshot(new Page.ScreenshotOptions().setPath(screenshotPath));
            log.warn("[{}] Join failed – screenshot saved to: {}", uuid, screenshotPath.toAbsolutePath());
        } catch (Exception e) {
            log.warn("[{}] Could not save failure screenshot: {}", uuid, e.getMessage());
        }
    }

    /**
     * Wait for the host to admit us into the meeting after clicking "Ask to join"
     * @param page The Playwright page
     * @param uuid Meeting UUID for logging
     * @param timeoutSeconds Maximum seconds to wait for admission
     * @return true if admitted, false if timed out or denied
     */
    private boolean waitForAdmission(Page page, String uuid, int timeoutSeconds) {
        long startTime = System.currentTimeMillis();
        long timeoutMs = timeoutSeconds * 1000L;
        int checkCount = 0;
        
        while (System.currentTimeMillis() - startTime < timeoutMs) {
            checkCount++;
            try {
                // Check if we were denied or the meeting blocked us
                if (page.locator("text='You can't join this video call'").count() > 0
                        || page.locator("text='Return to home screen'").count() > 0
                        || page.locator("text='Returning to home screen'").count() > 0
                        || page.locator("text='You were removed from the meeting'").count() > 0
                        || page.locator("text='denied your request'").count() > 0) {
                    log.warn("[{}] Request to join was denied or meeting blocked access", uuid);
                    return false;
                }
                
                // Check if we're in the meeting (indicators that we've been admitted)
                // Look for meeting controls that only appear when you're IN the meeting
                boolean inMeeting = false;
                
                // Check for leave/end call button (only visible when in meeting)
                if (page.locator("button[aria-label*='Leave call' i]").count() > 0
                        || page.locator("button[aria-label*='Leave meeting' i]").count() > 0
                        || page.locator("[aria-label*='Leave call' i]").count() > 0
                        || page.locator("[data-tooltip*='Leave call' i]").count() > 0) {
                    inMeeting = true;
                }
                
                // Check for captions/CC button (only in meeting)
                if (!inMeeting && (page.locator("button[aria-label*='caption' i]").count() > 0
                        || page.locator("button[aria-label*='subtitle' i]").count() > 0)) {
                    inMeeting = true;
                }
                
                // Check for participant list / people button (only in meeting)
                if (!inMeeting && (page.locator("button[aria-label*='people' i]").count() > 0
                        || page.locator("button[aria-label*='participant' i]").count() > 0)) {
                    inMeeting = true;
                }
                
                // Check for meeting title/info that appears when in meeting
                if (!inMeeting && page.locator("[data-meeting-title]").count() > 0) {
                    inMeeting = true;
                }
                
                if (inMeeting) {
                    log.info("[{}] Detected meeting UI - we have been admitted!", uuid);
                    return true;
                }
                
                // Still waiting - check for "waiting" indicators
                if (page.locator("text='Waiting for someone to let you in'").count() > 0
                        || page.locator("text='Asking to be let in'").count() > 0
                        || page.locator("text='Someone will let you in soon'").count() > 0
                        || page.locator("text='waiting'").count() > 0) {
                    if (checkCount % 10 == 0) { // Log every 10 checks (~30 seconds)
                        long elapsed = (System.currentTimeMillis() - startTime) / 1000;
                        log.info("[{}] Still waiting for host to admit... ({} seconds elapsed)", uuid, elapsed);
                    }
                }
                
                // Wait 3 seconds before checking again
                Thread.sleep(3000);
                
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            } catch (Exception e) {
                log.debug("[{}] Error during admission check: {}", uuid, e.getMessage());
                try {
                    Thread.sleep(3000);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
        }
        
        log.warn("[{}] Timed out waiting for host to admit after {} seconds", uuid, timeoutSeconds);
        return false;
    }

    private void enableCaptions(Page page, String uuid) throws Exception {
        log.info("[{}] Enabling captions", uuid);

        // Wait for meeting UI to be fully ready after join
        Thread.sleep(2000);

        // Check for late-appearing consent dialogs BEFORE attempting captions
        // (these dialogs block the entire UI including keyboard shortcuts)
        handleRecordingConsentDialogs(page, uuid);

        // Check if captions are already on
        if (areCaptionsAlreadyOn(page, uuid)) {
            log.info("[{}] Captions are already enabled!", uuid);
            return;
        }

        // Ensure keyboard focus is inside the meeting (required for shortcut to work)
        focusMeetingContent(page, uuid);

        // Try keyboard shortcut 'c' to toggle captions
        int maxRetries = 5;
        boolean captionsEnabled = false;

        for (int attempt = 1; attempt <= maxRetries && !captionsEnabled; attempt++) {
            try {
                log.info("[{}] Pressing 'c' to enable captions (attempt {}/{})", uuid, attempt, maxRetries);
                focusMeetingContent(page, uuid);
                Thread.sleep(200);
                page.keyboard().press("c");
                Thread.sleep(800);

                if (areCaptionsAlreadyOn(page, uuid)) {
                    captionsEnabled = true;
                    log.info("[{}] Captions enabled successfully!", uuid);
                    break;
                }
            } catch (Exception e) {
                log.warn("[{}] Keyboard shortcut failed on attempt {}: {}", uuid, attempt, e.getMessage());
            }

            // After 2 failed attempts, check if a consent dialog appeared late and is blocking us
            if (!captionsEnabled && attempt == 2) {
                boolean dismissed = handleRecordingConsentDialogs(page, uuid);
                if (dismissed) {
                    log.info("[{}] Late consent dialog dismissed, retrying captions...", uuid);
                    focusMeetingContent(page, uuid);
                    Thread.sleep(500);
                }
            }

            if (!captionsEnabled && attempt < maxRetries) {
                Thread.sleep(500);
            }
        }

        // Fallback: try clicking CC button with short timeout (works when shortcut doesn't)
        if (!captionsEnabled) {
            captionsEnabled = tryClickCaptionButton(page, uuid);
        }

        // Last resort: check for consent dialog one more time, dismiss, and retry everything
        if (!captionsEnabled) {
            boolean dismissed = handleRecordingConsentDialogs(page, uuid);
            if (dismissed) {
                log.info("[{}] Consent dialog was still blocking -- retrying captions after dismissal", uuid);
                Thread.sleep(1000);
                focusMeetingContent(page, uuid);

                // Retry keyboard shortcut
                for (int attempt = 1; attempt <= 3 && !captionsEnabled; attempt++) {
                    try {
                        focusMeetingContent(page, uuid);
                        Thread.sleep(200);
                        page.keyboard().press("c");
                        Thread.sleep(800);
                        if (areCaptionsAlreadyOn(page, uuid)) {
                            captionsEnabled = true;
                            log.info("[{}] Captions enabled on retry after consent dismissal!", uuid);
                        }
                    } catch (Exception e) {
                        log.debug("[{}] Retry caption attempt {} failed: {}", uuid, attempt, e.getMessage());
                    }
                }

                // Retry CC button
                if (!captionsEnabled) {
                    captionsEnabled = tryClickCaptionButton(page, uuid);
                }
            }
        }

        if (!captionsEnabled) {
            log.error("[{}] FAILED to enable captions after all attempts. Transcription may not work!", uuid);
            try {
                Path dir = Paths.get(transcriptPath);
                Files.createDirectories(dir);
                Path screenshotPath = dir.resolve("captions-failed_" + uuid + "_" + System.currentTimeMillis() + ".png");
                page.screenshot(new Page.ScreenshotOptions().setPath(screenshotPath));
                log.warn("[{}] Screenshot saved to: {}", uuid, screenshotPath.toAbsolutePath());
            } catch (Exception e) {
                log.debug("[{}] Could not save screenshot: {}", uuid, e.getMessage());
            }
        }

        Thread.sleep(500);
    }

    /**
     * Focus the meeting content so keyboard shortcuts (e.g. 'c' for captions) are received by Meet.
     */
    private void focusMeetingContent(Page page, String uuid) {
        try {
            // Click main/video area so focus is inside the meeting app
            String[] focusSelectors = {
                    "[class*='video']",
                    "main",
                    "[role='main']",
                    "[class*='content']",
                    "body"
            };
            for (String selector : focusSelectors) {
                try {
                    Locator el = page.locator(selector);
                    if (el.count() > 0) {
                        el.first().click(new Locator.ClickOptions().setTimeout(2000));
                        Thread.sleep(100);
                        return;
                    }
                } catch (Exception ignored) {}
            }
            // Fallback: focus body via JS
            page.evaluate("document.body.focus()");
        } catch (Exception e) {
            log.debug("[{}] Focus meeting content: {}", uuid, e.getMessage());
        }
    }

    /**
     * Try to enable captions by clicking the CC button (short timeout to avoid long waits).
     */
    private boolean tryClickCaptionButton(Page page, String uuid) {
        String[] captionSelectors = {
                "button[aria-label*='Turn on captions' i]",
                "button[aria-label*='captions' i]",
                "[aria-label*='Turn on captions' i]",
                "button[data-tooltip*='captions' i]"
        };
        for (String selector : captionSelectors) {
            try {
                Locator btn = page.locator(selector).first();
                btn.waitFor(new Locator.WaitForOptions().setTimeout(3000));
                if (btn.isVisible()) {
                    btn.click();
                    Thread.sleep(500);
                    if (areCaptionsAlreadyOn(page, uuid)) {
                        log.info("[{}] Captions enabled via CC button", uuid);
                        return true;
                    }
                }
            } catch (Exception ignored) {}
        }
        return false;
    }

    /**
     * Check if captions are already enabled
     */
    private boolean areCaptionsAlreadyOn(Page page, String uuid) {
        try {
            // Check for "Turn off captions" button (means captions ARE on)
            String[] captionsOnIndicators = {
                    "button[aria-label*='Turn off captions' i]",
                    "button[aria-pressed='true'][aria-label*='caption' i]",
                    "button[data-tooltip*='Turn off captions' i]",
                    "[aria-label*='Turn off captions' i]"
            };
            
            for (String selector : captionsOnIndicators) {
                try {
                    if (page.locator(selector).count() > 0) {
                        log.debug("[{}] Found captions-on indicator: {}", uuid, selector);
                        return true;
                    }
                } catch (Exception ignored) {}
            }
            
            // Check for visible caption container/text area
            String[] captionContainerSelectors = {
                    "[class*='caption']",
                    "[class*='iTTPOb']",
                    "[class*='TBMuR']",
                    "[class*='iOzk7']",
                    "[data-message-text]"
            };
            
            for (String selector : captionContainerSelectors) {
                try {
                    Locator container = page.locator(selector);
                    if (container.count() > 0 && container.first().isVisible()) {
                        log.debug("[{}] Found visible caption container: {}", uuid, selector);
                        return true;
                    }
                } catch (Exception ignored) {}
            }
            
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    private void captureTranscripts(Page page, MeetingSession session) throws Exception {
        String uuid = session.getUuid();
        log.info("[{}] Starting transcript capture until: {}", uuid, session.getEndTime());

        AtomicBoolean stopFlag = stopFlags.get(uuid);
        String pendingText = "";  // Accumulates all caption text
        int loopCount = 0;
        long lastDebugTime = 0;

        // Take initial debug screenshot
        // Debug screenshots disabled
        // saveDebugScreenshot(page, uuid, "capture-start");

        while (!stopFlag.get() && ZonedDateTime.now().isBefore(session.getEndTime())) {
            loopCount++;
            try {
                // Check if still in meeting
                if (isKickedOrMeetingEnded(page)) {
                    log.info("[{}] Meeting ended or kicked out", uuid);
                    break;
                }

                // Extract caption using JavaScript
                String[] captionData = extractCaptionViaJS(page, uuid);
                String speaker = captionData[0];
                String text = captionData[1];

                // DEBUG: Log every 30 iterations (~9 seconds) what we're getting
                long now = System.currentTimeMillis();
                if (now - lastDebugTime > 10000) {
                    log.info("[{}] DEBUG loop#{}: speaker='{}', text='{}' (len={})", 
                            uuid, loopCount, speaker, 
                            text != null && text.length() > 50 ? text.substring(0, 50) + "..." : text,
                            text != null ? text.length() : 0);
                    lastDebugTime = now;
                    // Periodic screenshots disabled
                    // if (loopCount % 100 == 0) {
                    //     saveDebugScreenshot(page, uuid, "capture-loop-" + loopCount);
                    // }
                }
                
                if (text != null && !text.isEmpty() && isValidCaptionText(text)) {
                    // Only log when text has grown/changed significantly
                    if (text.length() > pendingText.length() + 20 || 
                        (!text.startsWith(pendingText.isEmpty() ? "" : pendingText.substring(0, Math.min(20, pendingText.length()))))) {
                        log.info("[{}] Caption updated: {} chars", uuid, text.length());
                    }
                    
                    // Keep the longest/latest caption text (it accumulates all speakers)
                    if (text.length() >= pendingText.length()) {
                        pendingText = text;
                    }
                }

                // Small delay
                Thread.sleep(300);

            } catch (Exception e) {
                log.warn("[{}] Error during capture loop: {}", uuid, e.getMessage());
                Thread.sleep(500);
            }
        }
        
        // Save any remaining pending caption - parse into speaker turns
        if (!pendingText.isEmpty()) {
            parseAndSaveTranscripts(session, pendingText, uuid);
        }

        // Debug screenshots disabled
        // saveDebugScreenshot(page, uuid, "capture-end");
        
        log.info("[{}] Transcript capture ended. Total entries: {}", uuid, session.getTranscripts().size());
    }

    /**
     * Parse raw caption text containing multiple speakers and save as individual transcript entries.
     * Handles both newline-separated and inline speaker names (including lowercase).
     */
    private void parseAndSaveTranscripts(MeetingSession session, String rawText, String uuid) {
        if (rawText == null || rawText.isEmpty()) return;
        
        // Replace literal \n with actual newlines
        String text = rawText.replace("\\n", "\n");
        
        // Collect known speaker names from the text (both Title Case and lowercase)
        // Pattern matches: "Firstname Lastname" at start of line OR after newline
        java.util.Set<String> knownNames = new java.util.LinkedHashSet<>();
        
        // First pass: find all potential speaker names (lines that are just names)
        String[] lines = text.split("\n");
        java.util.regex.Pattern nameLinePattern = java.util.regex.Pattern.compile(
            "^[A-Za-z][a-zA-Z]*(?:\\s+[A-Za-z][a-zA-Z]*){0,3}$"
        );
        
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) continue;
            if (nameLinePattern.matcher(trimmed).matches() && 
                trimmed.length() >= 3 && trimmed.length() < 40 &&
                !trimmed.contains(".") && !trimmed.contains(",") &&
                !trimmed.contains("?") && !trimmed.contains("!")) {
                knownNames.add(trimmed);
            }
        }
        
        log.debug("[{}] Found {} potential speaker names: {}", uuid, knownNames.size(), knownNames);
        
        // If no names found from line parsing, try to extract from inline patterns
        if (knownNames.isEmpty()) {
            // Look for patterns like "name Sure." or "name Yeah." where name is 2-3 capitalized words
            java.util.regex.Pattern inlineNamePattern = java.util.regex.Pattern.compile(
                "([A-Z][a-z]+(?:\\s+[A-Z][a-z]+){0,2})\\s+(?=[A-Z])"
            );
            java.util.regex.Matcher m = inlineNamePattern.matcher(text);
            while (m.find()) {
                String name = m.group(1).trim();
                if (name.length() >= 3 && name.length() < 40) {
                    knownNames.add(name);
                }
            }
        }
        
        // Build entries by splitting on known names
        java.util.List<TranscriptEntry> entries = new java.util.ArrayList<>();
        
        if (knownNames.isEmpty()) {
            // No names found - save as single Unknown entry
            String cleanText = text.replace("\n", " ").replaceAll("\\s+", " ").trim();
            if (!cleanText.isEmpty() && isValidCaptionText(cleanText)) {
                entries.add(TranscriptEntry.builder()
                        .speaker("Unknown")
                        .text(cleanText)
                        .timestamp(LocalDateTime.now())
                        .build());
            }
        } else {
            // Build regex to split by any known name (case-insensitive for lowercase versions)
            StringBuilder nameRegex = new StringBuilder();
            for (String name : knownNames) {
                if (nameRegex.length() > 0) nameRegex.append("|");
                // Match the name followed by newline or at word boundary
                nameRegex.append("(?:^|\\n|(?<=\\s))").append(java.util.regex.Pattern.quote(name)).append("(?:\\n|\\s+(?=[A-Z]))");
                // Also match lowercase version
                String lowerName = name.toLowerCase();
                if (!lowerName.equals(name)) {
                    nameRegex.append("|(?:^|\\n|(?<=\\s))").append(java.util.regex.Pattern.quote(lowerName)).append("(?:\\n|\\s+(?=[A-Z]))");
                }
            }
            
            // Split text by speaker names
            java.util.regex.Pattern splitPattern = java.util.regex.Pattern.compile(
                "(" + nameRegex.toString() + ")", 
                java.util.regex.Pattern.CASE_INSENSITIVE
            );
            
            java.util.regex.Matcher matcher = splitPattern.matcher(text);
            
            java.util.List<String> allParts = new java.util.ArrayList<>();
            int lastEnd = 0;
            while (matcher.find()) {
                if (matcher.start() > lastEnd) {
                    allParts.add(text.substring(lastEnd, matcher.start()));
                }
                allParts.add(matcher.group().trim());
                lastEnd = matcher.end();
            }
            if (lastEnd < text.length()) {
                allParts.add(text.substring(lastEnd));
            }
            
            // Process parts: name followed by text
            String currentSpeaker = "Unknown";
            StringBuilder currentText = new StringBuilder();
            
            for (String part : allParts) {
                String trimmed = part.trim();
                if (trimmed.isEmpty()) continue;
                
                // Check if this part is a speaker name
                boolean isName = false;
                for (String name : knownNames) {
                    if (trimmed.equalsIgnoreCase(name)) {
                        isName = true;
                        // Save previous
                        if (currentText.length() > 0) {
                            String spokenText = currentText.toString().replaceAll("\\s+", " ").trim();
                            if (!spokenText.isEmpty() && isValidCaptionText(spokenText)) {
                                entries.add(TranscriptEntry.builder()
                                        .speaker(toTitleCase(currentSpeaker))
                                        .text(spokenText)
                                        .timestamp(LocalDateTime.now())
                                        .build());
                            }
                            currentText = new StringBuilder();
                        }
                        currentSpeaker = name;
                        break;
                    }
                }
                
                if (!isName) {
                    if (currentText.length() > 0) currentText.append(" ");
                    currentText.append(trimmed);
                }
            }
            
            // Save last entry
            if (currentText.length() > 0) {
                String spokenText = currentText.toString().replaceAll("\\s+", " ").trim();
                if (!spokenText.isEmpty() && isValidCaptionText(spokenText)) {
                    entries.add(TranscriptEntry.builder()
                            .speaker(toTitleCase(currentSpeaker))
                            .text(spokenText)
                            .timestamp(LocalDateTime.now())
                            .build());
                }
            }
        }
        
        // Add all entries to session
        for (TranscriptEntry entry : entries) {
            session.addTranscript(entry);
            log.debug("[{}] Parsed: {} - {}", uuid, entry.getSpeaker(), 
                    entry.getText().length() > 50 ? entry.getText().substring(0, 50) + "..." : entry.getText());
        }
        
        log.info("[{}] Parsed {} speaker turns from raw caption", uuid, entries.size());
    }
    
    /**
     * Convert string to Title Case (e.g., "hitesh natha" -> "Hitesh Natha")
     */
    private String toTitleCase(String input) {
        if (input == null || input.isEmpty()) return input;
        StringBuilder result = new StringBuilder();
        boolean capitalizeNext = true;
        for (char c : input.toCharArray()) {
            if (Character.isWhitespace(c)) {
                capitalizeNext = true;
                result.append(c);
            } else if (capitalizeNext) {
                result.append(Character.toUpperCase(c));
                capitalizeNext = false;
            } else {
                result.append(Character.toLowerCase(c));
            }
        }
        return result.toString();
    }

    private void saveDebugScreenshot(Page page, String uuid, String label) {
        try {
            Path dir = Paths.get(transcriptPath);
            Files.createDirectories(dir);
            Path screenshotPath = dir.resolve("debug_" + uuid + "_" + label + "_" + System.currentTimeMillis() + ".png");
            page.screenshot(new Page.ScreenshotOptions().setPath(screenshotPath).setFullPage(true));
            log.info("[{}] Debug screenshot saved: {}", uuid, screenshotPath.getFileName());
            
            // Also save HTML for analysis
            if ("capture-start".equals(label)) {
                try {
                    String html = page.content();
                    Path htmlPath = dir.resolve("debug_" + uuid + "_page.html");
                    Files.writeString(htmlPath, html);
                    log.info("[{}] Debug HTML saved: {}", uuid, htmlPath.getFileName());
                } catch (Exception e) {
                    log.debug("[{}] Could not save HTML: {}", uuid, e.getMessage());
                }
            }
        } catch (Exception e) {
            log.warn("[{}] Could not save debug screenshot: {}", uuid, e.getMessage());
        }
    }

    /**
     * Extract caption text using JavaScript - runs in main page and all iframes (Meet often puts captions in iframe).
     * Returns [speaker, text].
     */
    private String[] extractCaptionViaJS(Page page, String uuid) {
        String js = getCaptionExtractionJS();
        // 1) Try main frame first
        String[] result = evaluateCaptionJS(page.mainFrame(), js, uuid);
        if (result != null && !result[1].isEmpty()) return result;
        // 2) Try each iframe (Google Meet often renders meeting + captions inside an iframe)
        for (Frame frame : page.frames()) {
            if (frame == page.mainFrame()) continue;
            try {
                result = evaluateCaptionJS(frame, js, uuid);
                if (result != null && !result[1].isEmpty()) {
                    log.info("[{}] Caption found in iframe: {}", uuid, result[1].substring(0, Math.min(50, result[1].length())));
                    return result;
                }
            } catch (Exception e) {
                // Cross-origin or detached frame – skip
            }
        }
        
        // 3) Fallback: use Playwright locators directly to find caption text
        try {
            // Try multiple locator strategies
            String[] locatorSelectors = {
                    "[aria-live='polite']",
                    "[aria-live='assertive']",
                    "[role='status']",
                    "[class*='caption']",
                    "[class*='subtitle']",
                    "[data-message-text]"
            };
            for (String selector : locatorSelectors) {
                try {
                    Locator loc = page.locator(selector);
                    if (loc.count() > 0) {
                        String text = loc.first().innerText();
                        if (text != null && text.length() > 2 && text.length() < 800 && !text.contains("BETA")) {
                            log.info("[{}] Caption via locator '{}': {}", uuid, selector, text.substring(0, Math.min(50, text.length())));
                            return new String[]{"Unknown", text.trim()};
                        }
                    }
                } catch (Exception ignored) {}
            }
        } catch (Exception e) {
            log.debug("[{}] Locator fallback failed: {}", uuid, e.getMessage());
        }
        
        return result != null ? result : new String[]{"", ""};
    }

    private String getCaptionExtractionJS() {
        return """
                (function() {
                    let result = {speaker: '', text: '', debug: ''};
                    function setResult(s, t, d) { 
                        result.speaker = s || 'Unknown'; 
                        result.text = t || ''; 
                        result.debug = d || '';
                        return result.text.length > 0; 
                    }
                    let doc = document;
                    let win = window;
                    
                    // UI/accessibility text patterns to SKIP (not real captions)
                    const uiPatterns = /Press Down Arrow|hover tray|Escape to close|Press Enter|Press Tab|Use arrow keys|keyboard shortcut|Screen reader|Click to|Tap to|Swipe|Double-click|Right-click|participants? in this call|You're presenting|Present now|Stop presenting|Turn on|Turn off|microphone|camera|Leave call|End call|More options|Activities|raised hand|raise hand|lower hand|Reactions|Send a message|Chat with|Open chat|BETA|Font size|language|settings|Afrikaans|Albanian|Amharic|Arabic/i;
                    
                    function isUIText(text) {
                        return uiPatterns.test(text);
                    }
                    
                    // Method 1: data-message-text attribute (MOST RELIABLE for actual caption text)
                    let msgTextEls = doc.querySelectorAll('[data-message-text]');
                    result.debug += 'data-message-text:' + msgTextEls.length + '; ';
                    for (let el of msgTextEls) {
                        let text = el.getAttribute('data-message-text') || (el.innerText || '').trim();
                        if (text.length > 1 && text.length < 800 && !isUIText(text)) {
                            // Find speaker from parent
                            let speaker = 'Unknown';
                            let parent = el.closest('[data-sender-name]') || el.closest('[data-self-name]');
                            if (parent) {
                                speaker = parent.getAttribute('data-sender-name') || parent.getAttribute('data-self-name') || 'Unknown';
                            }
                            setResult(speaker, text, 'data-message-text');
                            return JSON.stringify(result);
                        }
                    }
                    
                    // Method 2: data-sender-name containers
                    let senderContainers = doc.querySelectorAll('[data-sender-name], [data-self-name]');
                    result.debug += 'sender-containers:' + senderContainers.length + '; ';
                    for (let c of senderContainers) {
                        let name = c.getAttribute('data-sender-name') || c.getAttribute('data-self-name') || '';
                        let textEl = c.querySelector('[data-message-text]') || c;
                        let text = textEl.getAttribute('data-message-text') || (textEl.innerText || '').trim();
                        if (text.length > 1 && text.length < 800 && !isUIText(text)) {
                            setResult(name || 'Unknown', text, 'sender-container');
                            return JSON.stringify(result);
                        }
                    }
                    
                    // Method 3: Known Google Meet caption classes (specific to caption display)
                    let meetClasses = doc.querySelectorAll('.iTTPOb, .TBMuR, .iOzk7, .a4cQT, .zs7s8d, .CNusmb, .Mz6pEf, .NWpY1c');
                    result.debug += 'meet-classes:' + meetClasses.length + '; ';
                    for (let el of meetClasses) {
                        let text = (el.innerText || '').trim();
                        if (text.length > 2 && text.length < 800 && !isUIText(text)) {
                            setResult('Unknown', text, 'meet-class');
                            return JSON.stringify(result);
                        }
                    }
                    
                    // Method 4: Caption/subtitle divs (class name contains caption/subtitle)
                    let captionDivs = doc.querySelectorAll('div[class*="caption" i], div[class*="subtitle" i], span[class*="caption" i]');
                    result.debug += 'caption-divs:' + captionDivs.length + '; ';
                    for (let el of captionDivs) {
                        let text = (el.innerText || '').trim();
                        if (text.length < 2 || text.length > 800 || isUIText(text)) continue;
                        let speaker = 'Unknown';
                        let parent = el.closest('[class*="caption"]');
                        if (parent) {
                            let nameEl = parent.querySelector('[class*="name"]');
                            if (nameEl) speaker = (nameEl.textContent || '').trim();
                        }
                        setResult(speaker, text, 'caption-div');
                        return JSON.stringify(result);
                    }
                    
                    // Method 5: aria-live regions (filter out UI text)
                    let liveEls = doc.querySelectorAll('[aria-live="polite"], [aria-live="assertive"]');
                    result.debug += 'aria-live:' + liveEls.length + '; ';
                    for (let el of liveEls) {
                        let raw = (el.innerText || '').trim();
                        if (raw.length < 2 || raw.length > 800 || isUIText(raw)) continue;
                        let lines = raw.split(/[\\n\\r]+/).map(l => l.trim()).filter(l => l.length > 0 && !isUIText(l));
                        if (lines.length >= 2) {
                            let first = lines[0], rest = lines.slice(1).join(' ').trim();
                            if (first.length < 60 && rest.length > 1 && !isUIText(rest)) {
                                setResult(first, rest, 'aria-live');
                                return JSON.stringify(result);
                            }
                        }
                        if (lines.length === 1 && lines[0].length > 2 && !isUIText(lines[0])) {
                            setResult('Unknown', lines[0], 'aria-live-single');
                            return JSON.stringify(result);
                        }
                    }
                    
                    // Method 6: role=region - but ONLY if text doesn't match UI patterns
                    let regions = Array.from(doc.querySelectorAll('[role="region"]'));
                    result.debug += 'regions:' + regions.length + '; ';
                    for (let region of regions) {
                        let raw = (region.innerText || '').trim();
                        if (!raw || raw.length < 2 || raw.length > 800 || isUIText(raw)) continue;
                        let lines = raw.split(/[\\n\\r]+/).map(l => l.trim()).filter(l => l.length > 0 && !isUIText(l));
                        if (lines.length >= 2) {
                            let first = lines[0], rest = lines.slice(1).join(' ').trim();
                            if (first.length < 60 && rest.length > 1 && !isUIText(rest)) {
                                setResult(first, rest, 'region');
                                return JSON.stringify(result);
                            }
                        }
                        // Single valid line
                        if (lines.length === 1 && lines[0].length > 2) {
                            setResult('Unknown', lines[0], 'region-single');
                            return JSON.stringify(result);
                        }
                    }
                    
                    return JSON.stringify(result);
                })()
                """;
    }

    private String[] evaluateCaptionJS(Frame frame, String js, String uuid) {
        try {
            Object resultObj = frame.evaluate(js);
            if (resultObj == null) return new String[]{"", ""};
            String jsonStr = resultObj.toString();
            String speaker = "";
            String text = "";
            String debug = "";
            
            // Extract speaker
            int speakerStart = jsonStr.indexOf("\"speaker\":\"");
            if (speakerStart >= 0) {
                speakerStart += 11;
                int speakerEnd = jsonStr.indexOf("\"", speakerStart);
                if (speakerEnd > speakerStart) speaker = jsonStr.substring(speakerStart, speakerEnd);
            }
            
            // Extract text
            int textStart = jsonStr.indexOf("\"text\":\"");
            if (textStart >= 0) {
                textStart += 8;
                int textEnd = jsonStr.indexOf("\",\"debug\"");
                if (textEnd < 0) textEnd = jsonStr.lastIndexOf("\"");
                if (textEnd > textStart) text = jsonStr.substring(textStart, textEnd);
            }
            
            // Extract debug info
            int debugStart = jsonStr.indexOf("\"debug\":\"");
            if (debugStart >= 0) {
                debugStart += 9;
                int debugEnd = jsonStr.lastIndexOf("\"");
                if (debugEnd > debugStart) debug = jsonStr.substring(debugStart, debugEnd);
            }
            
            // Log debug info periodically
            if (!debug.isEmpty()) {
                log.debug("[{}] JS extraction debug: {}", uuid, debug);
            }
            
            return new String[]{speaker, text};
        } catch (Exception e) {
            log.debug("[{}] Frame eval failed: {}", uuid, e.getMessage());
            return new String[]{"", ""};
        }
    }

    /**
     * Check if the captured text is valid caption content (not UI junk)
     */
    private boolean isValidCaptionText(String text) {
        if (text == null || text.length() < 2) {
            return false;
        }
        
        // Filter out common junk patterns including Google Meet UI/accessibility text
        String[] junkPatterns = {
            "BETA", "Font size", "Font color", "format_size", "arrow_downward",
            "Jump to bottom", "Open caption settings", "settings", "language",
            "Afrikaans", "Albanian", "Amharic", "Arabic", "Default", "Tiny",
            "Small", "Medium", "Large", "Huge", "Jumbo", "circle",
            "(South Africa)", "(Spain)", "(Brazil)", "(India)", "BETAChinese",
            // Google Meet accessibility/UI messages - NOT actual captions
            "Press Down Arrow", "hover tray", "Escape to close",
            "Press Enter to", "Press Tab to", "Use arrow keys",
            "Screen reader", "keyboard shortcut", "Click to",
            "Tap to", "Swipe to", "Double-click", "Right-click",
            "participants", "participant", "in this call",
            "You're presenting", "Present now", "Stop presenting",
            "Turn on microphone", "Turn off microphone", "Turn on camera", "Turn off camera",
            "Leave call", "End call", "More options", "Activities",
            "raised hand", "raise hand", "lower hand", "Reactions",
            "Send a message", "Chat with everyone", "Open chat"
        };
        
        for (String pattern : junkPatterns) {
            if (text.contains(pattern)) {
                return false;
            }
        }
        
        // Filter out time patterns like "7:19 PM"
        if (text.matches("^\\d{1,2}:\\d{2}\\s*(AM|PM)?$")) {
            return false;
        }
        
        // Filter out meeting codes like "abc-defg-hij"
        if (text.matches("^[a-z]{3}-[a-z]{4}-[a-z]{3}$")) {
            return false;
        }
        
        // Text should be reasonable length (match JS extraction max 800)
        return text.length() >= 2 && text.length() < 900;
    }

    private boolean isKickedOrMeetingEnded(Page page) {
        try {
            // Check for "meeting ended" or "removed" indicators
            String[] endedSelectors = {
                    "text='You have been removed from the meeting'",
                    "text='The meeting has ended'",
                    "text='You left the meeting'",
                    "text='Return to home screen'"
            };

            for (String selector : endedSelectors) {
                if (page.locator(selector).count() > 0) {
                    return true;
                }
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    private void leaveMeeting(Page page, String uuid) {
        log.info("[{}] Leaving meeting", uuid);
        try {
            // Method 1: Use JavaScript to find and click leave button (most reliable)
            String jsClickLeave = """
                (function() {
                    // Find leave button by various attributes
                    let btn = document.querySelector('button[aria-label*="Leave" i]') ||
                              document.querySelector('[aria-label*="Leave call" i]') ||
                              document.querySelector('[data-tooltip*="Leave" i]');
                    if (btn) { btn.click(); return 'clicked'; }
                    
                    // Try finding by icon/svg
                    let icons = document.querySelectorAll('button');
                    for (let b of icons) {
                        if (b.innerHTML.includes('call_end') || 
                            b.innerText.toLowerCase().includes('leave')) {
                            b.click(); return 'clicked-alt';
                        }
                    }
                    return 'not-found';
                })()
                """;
            
            Object result = page.evaluate(jsClickLeave);
            log.info("[{}] Leave button JS result: {}", uuid, result);
            
            Thread.sleep(1000);
            
            // Method 2: Click confirmation dialog if it appeared
            String jsClickConfirm = """
                (function() {
                    let btns = document.querySelectorAll('button');
                    for (let b of btns) {
                        let txt = (b.innerText || b.textContent || '').toLowerCase();
                        let label = (b.getAttribute('aria-label') || '').toLowerCase();
                        if (txt === 'leave' || txt === 'leave call' || 
                            label.includes('leave call') || label.includes('leave meeting')) {
                            b.click(); return 'confirmed';
                        }
                    }
                    return 'no-confirm';
                })()
                """;
            
            Object confirmResult = page.evaluate(jsClickConfirm);
            log.info("[{}] Confirm dialog JS result: {}", uuid, confirmResult);
            
            Thread.sleep(500);
            log.info("[{}] Left meeting", uuid);
            
        } catch (Exception e) {
            log.warn("[{}] Error leaving: {}", uuid, e.getMessage());
        } finally {
            // ALWAYS close page to ensure cleanup
            try { 
                page.close(); 
                log.info("[{}] Browser page closed", uuid);
            } catch (Exception ignored) {}
        }
    }
}
