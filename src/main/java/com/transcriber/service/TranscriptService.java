package com.transcriber.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.opencsv.CSVWriter;
import com.transcriber.model.MeetingSession;
import com.transcriber.model.TranscriptEntry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
public class TranscriptService {

    @Value("${meeting.transcript-path:/tmp/transcripts}")
    private String transcriptPath;

    private static final DateTimeFormatter FILE_DATE_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");
    private static final DateTimeFormatter READABLE_DATETIME_FORMAT = DateTimeFormatter.ofPattern("MMMM dd, yyyy hh:mm:ss a");
    private static final DateTimeFormatter TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final ObjectMapper objectMapper;

    public TranscriptService() {
        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new JavaTimeModule());
        this.objectMapper.enable(SerializationFeature.INDENT_OUTPUT);
        this.objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    @PostConstruct
    public void init() {
        try {
            Path path = Paths.get(transcriptPath);
            if (!Files.exists(path)) {
                Files.createDirectories(path);
                log.info("Created transcript directory: {}", transcriptPath);
            }
        } catch (IOException e) {
            log.error("Failed to create transcript directory: {}", e.getMessage());
        }
    }

    /**
     * Save transcript to TXT format only
     * Returns the path to the TXT file
     */
    public String saveAllFormats(MeetingSession session) {
        // Merge consecutive speaker entries for cleaner output
        List<TranscriptEntry> mergedTranscripts = mergeConsecutiveSpeakers(session.getTranscripts());
        
        // Only save TXT format
        return saveToTXT(session, mergedTranscripts);
    }

    /**
     * Save transcripts to well-structured JSON file
     */
    public String saveToJSON(MeetingSession session, List<TranscriptEntry> mergedTranscripts) {
        String filename = String.format("transcript_%s_%s.json",
                session.getUuid(),
                session.getStartTime().format(FILE_DATE_FORMAT));
        
        Path filePath = Paths.get(transcriptPath, filename);
        
        try {
            // Build structured transcript document
            Map<String, Object> document = new LinkedHashMap<>();
            
            // Meeting metadata
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("meetingId", session.getUuid());
            metadata.put("meetingUrl", session.getMeetUrl());
            metadata.put("startTime", session.getStartTime().toString());
            metadata.put("endTime", session.getEndTime().toString());
            metadata.put("duration", formatDuration(session));
            metadata.put("status", session.getStatus().toString());
            metadata.put("generatedAt", LocalDateTime.now().toString());
            document.put("metadata", metadata);
            
            // Statistics
            Map<String, Object> statistics = new LinkedHashMap<>();
            statistics.put("totalEntries", session.getTranscripts().size());
            statistics.put("mergedEntries", mergedTranscripts.size());
            statistics.put("participants", getUniqueParticipants(mergedTranscripts));
            statistics.put("participantStats", getParticipantStatistics(mergedTranscripts));
            document.put("statistics", statistics);
            
            // Transcript entries (merged for readability)
            List<Map<String, Object>> transcriptList = new ArrayList<>();
            for (TranscriptEntry entry : mergedTranscripts) {
                Map<String, Object> entryMap = new LinkedHashMap<>();
                entryMap.put("timestamp", entry.getTimestamp() != null ? entry.getTimestamp().format(TIMESTAMP_FORMAT) : null);
                entryMap.put("speaker", entry.getSpeaker());
                entryMap.put("text", entry.getText());
                entryMap.put("wordCount", entry.getText() != null ? entry.getText().split("\\s+").length : 0);
                transcriptList.add(entryMap);
            }
            document.put("transcript", transcriptList);
            
            // Full conversation as plain text (for easy reading/copy)
            document.put("fullText", generateFullText(mergedTranscripts));
            
            // Write JSON file
            objectMapper.writeValue(filePath.toFile(), document);
            
            log.info("[{}] Saved structured JSON transcript to: {}", session.getUuid(), filePath);
            return filePath.toString();
            
        } catch (IOException e) {
            log.error("[{}] Failed to save JSON: {}", session.getUuid(), e.getMessage());
            return null;
        }
    }

    /**
     * Save transcripts to CSV file
     */
    public String saveToCSV(MeetingSession session) {
        String filename = String.format("transcript_%s_%s.csv",
                session.getUuid(),
                session.getStartTime().format(FILE_DATE_FORMAT));
        
        Path filePath = Paths.get(transcriptPath, filename);
        
        try (CSVWriter writer = new CSVWriter(new FileWriter(filePath.toFile()))) {
            // Write header
            writer.writeNext(TranscriptEntry.getCsvHeaders());
            
            // Write transcript entries
            for (TranscriptEntry entry : session.getTranscripts()) {
                writer.writeNext(entry.toStringArray());
            }
            
            log.info("[{}] Saved {} transcript entries to CSV: {}", 
                    session.getUuid(), session.getTranscripts().size(), filePath);
            
            return filePath.toString();
            
        } catch (IOException e) {
            log.error("[{}] Failed to save CSV: {}", session.getUuid(), e.getMessage());
            return null;
        }
    }

    /**
     * Save transcripts to TXT file with metadata
     * Format: [TIME] SPEAKER: Text
     */
    public String saveToTXT(MeetingSession session, List<TranscriptEntry> mergedTranscripts) {
        String filename = String.format("transcript_%s_%s.txt",
                session.getUuid(),
                session.getStartTime().format(FILE_DATE_FORMAT));
        
        Path filePath = Paths.get(transcriptPath, filename);
        
        try (FileWriter writer = new FileWriter(filePath.toFile())) {
            // Header
            writer.write("================================================================================\n");
            writer.write("                              MEETING TRANSCRIPT\n");
            writer.write("================================================================================\n\n");
            
            // Meeting Metadata
            writer.write("MEETING DETAILS\n");
            writer.write("-".repeat(40) + "\n");
            writer.write("Meeting ID    : " + session.getUuid() + "\n");
            writer.write("Meeting URL   : " + session.getMeetUrl() + "\n");
            writer.write("Start Time    : " + session.getStartTime().format(READABLE_DATETIME_FORMAT) + "\n");
            writer.write("End Time      : " + session.getEndTime().format(READABLE_DATETIME_FORMAT) + "\n");
            writer.write("Duration      : " + formatDuration(session) + "\n");
            writer.write("Status        : " + session.getStatus().toString() + "\n");
            writer.write("\n");
            
            // Participants
            List<String> participants = getUniqueParticipants(mergedTranscripts);
            Map<String, Integer> participantStats = getParticipantStatistics(mergedTranscripts);
            writer.write("PARTICIPANTS (" + participants.size() + ")\n");
            writer.write("-".repeat(40) + "\n");
            for (String participant : participants) {
                int count = participantStats.getOrDefault(participant, 0);
                writer.write("  - " + participant + " (" + count + " message" + (count != 1 ? "s" : "") + ")\n");
            }
            writer.write("\n");
            
            // Statistics
            int totalWords = mergedTranscripts.stream()
                    .mapToInt(e -> e.getText() != null ? e.getText().split("\\s+").length : 0)
                    .sum();
            writer.write("STATISTICS\n");
            writer.write("-".repeat(40) + "\n");
            writer.write("Total Messages: " + mergedTranscripts.size() + "\n");
            writer.write("Total Words   : " + totalWords + "\n");
            writer.write("Generated At  : " + LocalDateTime.now().format(READABLE_DATETIME_FORMAT) + "\n");
            writer.write("\n");
            
            // Transcript
            writer.write("================================================================================\n");
            writer.write("                                TRANSCRIPT\n");
            writer.write("================================================================================\n\n");
            
            // Transcript entries - simple format: SPEAKER: Text
            for (TranscriptEntry entry : mergedTranscripts) {
                String speaker = entry.getSpeaker() != null ? entry.getSpeaker() : "Unknown";
                String text = entry.getText() != null ? entry.getText() : "";
                
                writer.write(speaker + ":\n");
                writer.write("    " + text + "\n\n");
            }
            
            // Footer
            writer.write("================================================================================\n");
            writer.write("                              END OF TRANSCRIPT\n");
            writer.write("================================================================================\n");
            
            log.info("[{}] Saved TXT transcript to: {}", session.getUuid(), filePath);
            return filePath.toString();
            
        } catch (IOException e) {
            log.error("[{}] Failed to save TXT: {}", session.getUuid(), e.getMessage());
            return null;
        }
    }

    /**
     * Legacy method - Save transcripts to TXT file
     */
    public String saveToTXT(MeetingSession session) {
        return saveToTXT(session, mergeConsecutiveSpeakers(session.getTranscripts()));
    }

    /**
     * Merge consecutive entries from same speaker
     */
    public List<TranscriptEntry> mergeConsecutiveSpeakers(List<TranscriptEntry> entries) {
        if (entries == null || entries.size() <= 1) {
            return entries != null ? entries : new ArrayList<>();
        }

        List<TranscriptEntry> merged = new ArrayList<>();
        TranscriptEntry current = entries.get(0);
        StringBuilder textBuilder = new StringBuilder(current.getText() != null ? current.getText() : "");

        for (int i = 1; i < entries.size(); i++) {
            TranscriptEntry next = entries.get(i);
            String currentSpeaker = current.getSpeaker() != null ? current.getSpeaker() : "";
            String nextSpeaker = next.getSpeaker() != null ? next.getSpeaker() : "";
            
            if (nextSpeaker.equals(currentSpeaker)) {
                // Same speaker - merge text
                if (next.getText() != null && !next.getText().isEmpty()) {
                    textBuilder.append(" ").append(next.getText());
                }
            } else {
                // Different speaker - save current and start new
                merged.add(TranscriptEntry.builder()
                        .speaker(current.getSpeaker())
                        .text(textBuilder.toString().trim())
                        .timestamp(current.getTimestamp())
                        .build());
                
                current = next;
                textBuilder = new StringBuilder(current.getText() != null ? current.getText() : "");
            }
        }
        
        // Add last entry
        merged.add(TranscriptEntry.builder()
                .speaker(current.getSpeaker())
                .text(textBuilder.toString().trim())
                .timestamp(current.getTimestamp())
                .build());

        return merged;
    }

    /**
     * Get unique participants from transcript entries
     */
    private List<String> getUniqueParticipants(List<TranscriptEntry> entries) {
        return entries.stream()
                .map(TranscriptEntry::getSpeaker)
                .filter(Objects::nonNull)
                .distinct()
                .sorted()
                .collect(Collectors.toList());
    }

    /**
     * Get message count per participant
     */
    private Map<String, Integer> getParticipantStatistics(List<TranscriptEntry> entries) {
        Map<String, Integer> stats = new LinkedHashMap<>();
        for (TranscriptEntry entry : entries) {
            String speaker = entry.getSpeaker() != null ? entry.getSpeaker() : "Unknown";
            stats.put(speaker, stats.getOrDefault(speaker, 0) + 1);
        }
        return stats;
    }

    /**
     * Format meeting duration
     */
    private String formatDuration(MeetingSession session) {
        if (session.getStartTime() == null || session.getEndTime() == null) {
            return "Unknown";
        }
        Duration duration = Duration.between(session.getStartTime(), session.getEndTime());
        long hours = duration.toHours();
        long minutes = duration.toMinutesPart();
        long seconds = duration.toSecondsPart();
        
        if (hours > 0) {
            return String.format("%d hr %d min %d sec", hours, minutes, seconds);
        } else if (minutes > 0) {
            return String.format("%d min %d sec", minutes, seconds);
        } else {
            return String.format("%d sec", seconds);
        }
    }

    /**
     * Generate full conversation text
     */
    private String generateFullText(List<TranscriptEntry> entries) {
        StringBuilder sb = new StringBuilder();
        for (TranscriptEntry entry : entries) {
            String speaker = entry.getSpeaker() != null ? entry.getSpeaker() : "Unknown";
            String text = entry.getText() != null ? entry.getText() : "";
            sb.append(speaker).append(": ").append(text).append("\n\n");
        }
        return sb.toString().trim();
    }

}
