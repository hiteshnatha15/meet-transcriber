package com.transcriber.service;

import com.transcriber.model.CallbackPayload;
import com.transcriber.model.MeetingSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.time.LocalDateTime;

@Slf4j
@Service
@RequiredArgsConstructor
public class CallbackService {

    private final WebClient webClient;
    private final TranscriptService transcriptService;

    /**
     * Send meeting results to callback URL
     */
    public void sendCallback(MeetingSession session, String csvFilePath) {
        log.info("[{}] Sending callback to: {}", session.getUuid(), session.getCallbackUrl());

        // Merge consecutive speaker entries for cleaner output
        var mergedTranscripts = transcriptService.mergeConsecutiveSpeakers(session.getTranscripts());

        CallbackPayload payload = CallbackPayload.builder()
                .uuid(session.getUuid())
                .meetUrl(session.getMeetUrl())
                .status(session.getStatus().name())
                .meetingStartTime(session.getStartTime().toLocalDateTime())
                .meetingEndTime(session.getEndTime().toLocalDateTime())
                .actualStartTime(LocalDateTime.now())
                .actualEndTime(LocalDateTime.now())
                .transcripts(mergedTranscripts)
                .totalEntries(mergedTranscripts.size())
                .csvFilePath(csvFilePath)
                .errorMessage(session.getErrorMessage())
                .build();

        webClient.post()
                .uri(session.getCallbackUrl())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(payload)
                .retrieve()
                .bodyToMono(String.class)
                .retryWhen(Retry.backoff(3, Duration.ofSeconds(2))
                        .maxBackoff(Duration.ofSeconds(10))
                        .doBeforeRetry(signal -> 
                            log.warn("[{}] Retrying callback, attempt: {}", 
                                    session.getUuid(), signal.totalRetries() + 1)))
                .doOnSuccess(response -> 
                    log.info("[{}] Callback sent successfully. Response: {}", 
                            session.getUuid(), response))
                .doOnError(error -> 
                    log.error("[{}] Failed to send callback: {}", 
                            session.getUuid(), error.getMessage()))
                .onErrorResume(e -> Mono.empty())
                .subscribe();
    }

    /**
     * Send error callback
     */
    public void sendErrorCallback(MeetingSession session, String error) {
        log.info("[{}] Sending error callback to: {}", session.getUuid(), session.getCallbackUrl());

        CallbackPayload payload = CallbackPayload.builder()
                .uuid(session.getUuid())
                .meetUrl(session.getMeetUrl())
                .status(MeetingSession.MeetingStatus.FAILED.name())
                .errorMessage(error)
                .build();

        webClient.post()
                .uri(session.getCallbackUrl())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(payload)
                .retrieve()
                .bodyToMono(String.class)
                .doOnSuccess(response -> 
                    log.info("[{}] Error callback sent", session.getUuid()))
                .doOnError(err -> 
                    log.error("[{}] Failed to send error callback: {}", 
                            session.getUuid(), err.getMessage()))
                .onErrorResume(e -> Mono.empty())
                .subscribe();
    }
}
