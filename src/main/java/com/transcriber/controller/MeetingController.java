package com.transcriber.controller;

import com.transcriber.model.ApiResponse;
import com.transcriber.model.CallbackPayload;
import com.transcriber.model.MeetingRequest;
import com.transcriber.model.MeetingSession;
import com.transcriber.service.MeetingSchedulerService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class MeetingController {

    private final MeetingSchedulerService schedulerService;

    /**
     * Schedule a new meeting transcription
     */
    @PostMapping("/join-meeting")
    public ResponseEntity<ApiResponse<Map<String, Object>>> joinMeeting(
            @Valid @RequestBody MeetingRequest request) {
        
        log.info("Received meeting request: UUID={}, URL={}, Start={}, End={}, TZ={}",
                request.getUuid(),
                request.getMeetUrl(),
                request.getStartTime(),
                request.getEndTime(),
                request.getTimeZone());

        try {
            MeetingSession session = schedulerService.scheduleMeeting(request);
            
            Map<String, Object> data = new HashMap<>();
            data.put("uuid", session.getUuid());
            data.put("status", "SCHEDULED");  // initial status; use GET /api/meeting/{uuid} for current status
            data.put("scheduledStart", session.getStartTime().toString());
            data.put("scheduledEnd", session.getEndTime().toString());
            
            return ResponseEntity.ok(ApiResponse.success("Meeting scheduled successfully", data));
            
        } catch (IllegalStateException e) {
            log.warn("Meeting already exists: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(ApiResponse.error(e.getMessage()));
        } catch (IllegalArgumentException e) {
            log.warn("Invalid request: {}", e.getMessage());
            return ResponseEntity.badRequest()
                    .body(ApiResponse.error(e.getMessage()));
        } catch (Exception e) {
            log.error("Failed to schedule meeting: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(ApiResponse.error("Failed to schedule meeting: " + e.getMessage()));
        }
    }

    /**
     * Get meeting status
     */
    @GetMapping("/meeting/{uuid}")
    public ResponseEntity<ApiResponse<MeetingSession>> getMeetingStatus(@PathVariable String uuid) {
        MeetingSession session = schedulerService.getMeetingStatus(uuid);
        
        if (session == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(ApiResponse.error("Meeting not found: " + uuid));
        }
        
        return ResponseEntity.ok(ApiResponse.success("Meeting found", session));
    }

    /**
     * Cancel a scheduled meeting
     */
    @DeleteMapping("/meeting/{uuid}")
    public ResponseEntity<ApiResponse<Void>> cancelMeeting(@PathVariable String uuid) {
        boolean cancelled = schedulerService.cancelMeeting(uuid);
        
        if (!cancelled) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(ApiResponse.error("Meeting not found or already completed: " + uuid));
        }
        
        return ResponseEntity.ok(ApiResponse.success("Meeting cancelled successfully"));
    }

    /**
     * Test callback endpoint – receives the same payload the app sends when a meeting ends.
     * Use this URL as callbackUrl when testing: http://localhost:8080/api/test-callback
     */
    @PostMapping("/test-callback")
    public ResponseEntity<ApiResponse<String>> testCallback(@RequestBody CallbackPayload payload) {
        log.info("=== TEST CALLBACK RECEIVED ===");
        log.info("UUID: {}", payload.getUuid());
        log.info("Meet URL: {}", payload.getMeetUrl());
        log.info("Status: {}", payload.getStatus());
        log.info("Total entries: {}", payload.getTotalEntries());
        log.info("Error: {}", payload.getErrorMessage());
        if (payload.getTranscripts() != null && !payload.getTranscripts().isEmpty()) {
            log.info("Transcripts (first 5):");
            payload.getTranscripts().stream().limit(5).forEach(t ->
                    log.info("  [{}] {}: {}", t.getTimestamp(), t.getSpeaker(), t.getText()));
        }
        log.info("==============================");
        return ResponseEntity.ok(ApiResponse.success("Callback received", "OK"));
    }

    /**
     * Health check endpoint
     */
    @GetMapping("/health")
    public ResponseEntity<ApiResponse<Map<String, Object>>> healthCheck() {
        Map<String, Object> health = new HashMap<>();
        health.put("status", "UP");
        health.put("activeMeetings", schedulerService.getActiveMeetingCount());
        health.put("timestamp", java.time.Instant.now().toString());
        
        return ResponseEntity.ok(ApiResponse.success("Service is healthy", health));
    }
}
