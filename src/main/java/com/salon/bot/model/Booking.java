package com.salon.bot.model;

import lombok.Builder;
import lombok.Value;
import lombok.With;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

/**
 * Запись клиента (строка листа «Записи»).
 * {@code durationMinutes} в файле не хранится — берётся из услуги.
 */
@Value
@With
@Builder(toBuilder = true)
public class Booking {
    long id;
    LocalDateTime createdAt;
    String clientName;
    long telegramId;
    String username;
    String phone;
    String serviceName;
    String masterName;
    LocalDate date;
    LocalTime time;
    String source;
    BookingStatus status;
    boolean reminderSent;
    int durationMinutes;

    public LocalDateTime start() {
        return date.atTime(time);
    }

    public LocalDateTime end() {
        return start().plusMinutes(durationMinutes);
    }

    public boolean isActive() {
        return status != BookingStatus.CANCELLED;
    }
}
