package com.salon.bot.api.dto;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

/** Свободное время на дату. */
public record SlotsResponse(
        @JsonFormat(pattern = ApiFormats.DATE) LocalDate date,
        String serviceName,
        int durationMinutes,
        @JsonFormat(pattern = ApiFormats.TIME) List<LocalTime> slots
) {
}
