package com.transcriber.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MeetingRequest {

    @NotBlank(message = "Meet URL is required")
    private String meetUrl;

    @NotBlank(message = "UUID is required")
    private String uuid;

    @NotNull(message = "Start time is required")
    private LocalDateTime startTime;

    @NotNull(message = "End time is required")
    private LocalDateTime endTime;

    @NotBlank(message = "Time zone is required")
    private String timeZone;

    @NotBlank(message = "Callback URL is required")
    private String callbackUrl;
}
