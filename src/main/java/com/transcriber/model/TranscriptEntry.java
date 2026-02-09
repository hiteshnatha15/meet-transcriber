package com.transcriber.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TranscriptEntry {

    private String speaker;
    private String text;
    private LocalDateTime timestamp;

    // For CSV export
    public String[] toStringArray() {
        return new String[]{
                timestamp != null ? timestamp.toString() : "",
                speaker != null ? speaker : "Unknown",
                text != null ? text : ""
        };
    }

    public static String[] getCsvHeaders() {
        return new String[]{"Timestamp", "Speaker", "Text"};
    }
}
