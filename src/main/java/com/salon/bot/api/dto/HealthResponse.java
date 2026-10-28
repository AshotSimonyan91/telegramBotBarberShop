package com.salon.bot.api.dto;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.LocalDateTime;

/** Ответ GET /api/v1/health. */
public record HealthResponse(
        String status,
        String timezone,
        @JsonFormat(pattern = ApiFormats.DATE_TIME) LocalDateTime now
) {
}
