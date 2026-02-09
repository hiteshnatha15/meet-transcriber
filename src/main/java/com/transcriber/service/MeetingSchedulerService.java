package com.transcriber.service;

import com.transcriber.model.MeetingRequest;
import com.transcriber.model.MeetingSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;

@Slf4j
@Service
@RequiredArgsConstructor
public class MeetingSchedulerService {

    private final TaskScheduler taskScheduler;
    private final PlaywrightService playwrightService;
    private final TranscriptService transcriptService;
    private final CallbackService callbackService;

    // Track scheduled meetings
    private final ConcurrentHashMap<String, MeetingSession> activeSessions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ScheduledFuture<?>> scheduledTasks = new ConcurrentHashMap<>();

    /**
     * Schedule a new meeting
     * The bot will join ONLY at the specified start time, not immediately
     */
    public MeetingSession scheduleMeeting(MeetingRequest request) {
        String uuid = request.getUuid();
        
        // Check if meeting already scheduled
        if (activeSessions.containsKey(uuid)) {
            throw new IllegalStateException("Meeting with UUID " + uuid + " is already scheduled");
        }

        // Parse times with timezone
        ZoneId zoneId = ZoneId.of(request.getTimeZone());
        ZonedDateTime startTime = request.getStartTime().atZone(zoneId);
        ZonedDateTime endTime = request.getEndTime().atZone(zoneId);
        ZonedDateTime now = ZonedDateTime.now(zoneId);

        // Validate: Start time must be in the future (or within 30 seconds grace period)
        if (startTime.isBefore(now.minusSeconds(30))) {
            throw new IllegalArgumentException(
                "Start time " + startTime + " is in the past. Current time: " + now + 
                ". Please provide a future start time.");
        }

        // Validate: End time must be after start time
        if (endTime.isBefore(startTime) || endTime.equals(startTime)) {
            throw new IllegalArgumentException(
                "End time " + endTime + " must be after start time " + startTime);
        }

        // Create session
        MeetingSession session = MeetingSession.builder()
                .uuid(uuid)
                .meetUrl(request.getMeetUrl())
                .startTime(startTime)
                .endTime(endTime)
                .callbackUrl(request.getCallbackUrl())
                .status(MeetingSession.MeetingStatus.SCHEDULED)
                .build();

        activeSessions.put(uuid, session);

        // Calculate time until start
        Instant startInstant = startTime.toInstant();
        long secondsUntilStart = java.time.Duration.between(Instant.now(), startInstant).getSeconds();
        
        if (secondsUntilStart <= 0) {
            // Start time is now (within grace period) - start immediately
            log.info("[{}] Start time is now, joining meeting immediately", uuid);
            executeMeetingAsync(session);
        } else {
            // Schedule for future start time
            log.info("[{}] Meeting scheduled to start at: {} (in {} seconds)", 
                    uuid, startTime, secondsUntilStart);
            log.info("[{}] Meeting will end at: {}", uuid, endTime);
            
            ScheduledFuture<?> future = taskScheduler.schedule(
                    () -> executeMeetingAsync(session),
                    startInstant
            );
            scheduledTasks.put(uuid, future);
        }

        return session;
    }

    /**
     * Execute meeting asynchronously
     */
    @Async("meetingTaskExecutor")
    public void executeMeetingAsync(MeetingSession session) {
        String uuid = session.getUuid();
        log.info("[{}] Executing meeting task", uuid);

        try {
            // Join meeting and capture transcripts
            playwrightService.joinMeetingAndCapture(session);

            // Save transcripts in all formats (JSON, TXT, CSV)
            // Returns path to the primary JSON file
            String transcriptPath = transcriptService.saveAllFormats(session);
            log.info("[{}] Transcripts saved to: {}", uuid, transcriptPath);

            // Send callback with results
            callbackService.sendCallback(session, transcriptPath);

        } catch (Exception e) {
            log.error("[{}] Meeting execution failed: {}", uuid, e.getMessage(), e);
            session.setStatus(MeetingSession.MeetingStatus.FAILED);
            session.setErrorMessage(e.getMessage());
            callbackService.sendErrorCallback(session, e.getMessage());
        } finally {
            // Cleanup
            activeSessions.remove(uuid);
            scheduledTasks.remove(uuid);
        }
    }

    /**
     * Cancel a scheduled meeting
     */
    public boolean cancelMeeting(String uuid) {
        MeetingSession session = activeSessions.get(uuid);
        if (session == null) {
            return false;
        }

        // Cancel scheduled task if not yet started
        ScheduledFuture<?> future = scheduledTasks.get(uuid);
        if (future != null && !future.isDone()) {
            future.cancel(false);
        }

        // If meeting is in progress, signal to stop
        if (session.getStatus() == MeetingSession.MeetingStatus.IN_PROGRESS) {
            playwrightService.stopCapturing(uuid);
        }

        session.setStatus(MeetingSession.MeetingStatus.CANCELLED);
        activeSessions.remove(uuid);
        scheduledTasks.remove(uuid);

        log.info("[{}] Meeting cancelled", uuid);
        return true;
    }

    /**
     * Get meeting status
     */
    public MeetingSession getMeetingStatus(String uuid) {
        return activeSessions.get(uuid);
    }

    /**
     * Get count of active meetings
     */
    public int getActiveMeetingCount() {
        return activeSessions.size();
    }
}
