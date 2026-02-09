package com.transcriber.model;

import lombok.Builder;
import lombok.Data;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

@Data
@Builder
public class MeetingSession {

    private String uuid;
    private String meetUrl;
    private ZonedDateTime startTime;
    private ZonedDateTime endTime;
    private String callbackUrl;
    private MeetingStatus status;
    
    @Builder.Default
    private List<TranscriptEntry> transcripts = new CopyOnWriteArrayList<>();
    
    private String errorMessage;

    public enum MeetingStatus {
        SCHEDULED,
        JOINING,
        IN_PROGRESS,
        COMPLETED,
        FAILED,
        CANCELLED
    }

    public void addTranscript(TranscriptEntry entry) {
        transcripts.add(entry);
    }
}
