package com.salon.bot.api.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.salon.bot.model.Booking;
import com.salon.bot.model.BookingStatus;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

/** Запись в ответах API. */
public record BookingDto(
        long id,
        @JsonFormat(pattern = ApiFormats.DATE_TIME) LocalDateTime createdAt,
        String clientName,
        long telegramId,
        String username,
        String phone,
        String serviceName,
        String masterName,
        @JsonFormat(pattern = ApiFormats.DATE) LocalDate date,
        @JsonFormat(pattern = ApiFormats.TIME) LocalTime time,
        int durationMinutes,
        String source,
        BookingStatus status,
        boolean reminderSent
) {

    public static BookingDto from(Booking b) {
        return new BookingDto(b.getId(), b.getCreatedAt(), b.getClientName(), b.getTelegramId(),
                nullToEmpty(b.getUsername()), b.getPhone(), b.getServiceName(), b.getMasterName(),
                b.getDate(), b.getTime(), b.getDurationMinutes(), b.getSource(), b.getStatus(), b.isReminderSent());
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
