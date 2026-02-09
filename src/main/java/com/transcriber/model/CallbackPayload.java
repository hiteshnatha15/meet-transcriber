package com.transcriber.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CallbackPayload {

    private String uuid;
    private String meetUrl;
    private String status;
    private LocalDateTime meetingStartTime;
    private LocalDateTime meetingEndTime;
    private LocalDateTime actualStartTime;
    private LocalDateTime actualEndTime;
    private List<TranscriptEntry> transcripts;
    private int totalEntries;
    private String csvFilePath;
    private String errorMessage;
}
