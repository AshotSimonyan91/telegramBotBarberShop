package com.salon.bot.service;

import com.salon.bot.model.Booking;
import com.salon.bot.model.Master;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Расчёт свободных слотов. Не зависит от способа хранения данных.
 */
@Component
public class SlotCalculator {

    public static final int STEP_MINUTES = 30;

    private final Clock clock;

    public SlotCalculator(Clock clock) {
        this.clock = clock;
    }

    /**
     * Свободные начала визита с шагом 30 минут в рабочие часы мастера.
     * Отменённые записи слот не занимают; для сегодняшней даты прошедшее время не показывается.
     */
    public List<LocalTime> getFreeSlots(Master master, LocalDate date, int serviceDuration,
                                        List<Booking> existingBookings) {
        LocalDateTime now = LocalDateTime.now(clock);
        if (!master.worksOn(date) || date.isBefore(now.toLocalDate()) || serviceDuration <= 0) {
            return List.of();
        }
        List<Booking> busy = existingBookings.stream()
                .filter(Booking::isActive)
                .filter(b -> b.getDate().equals(date))
                .filter(b -> b.getMasterName().equalsIgnoreCase(master.getName()))
                .toList();

        LocalDateTime dayEnd = date.atTime(master.getWorkEnd());
        List<LocalTime> slots = new ArrayList<>();
        for (LocalDateTime start = date.atTime(master.getWorkStart());
             !start.plusMinutes(serviceDuration).isAfter(dayEnd);
             start = start.plusMinutes(STEP_MINUTES)) {
            LocalDateTime end = start.plusMinutes(serviceDuration);
            LocalDateTime slotStart = start;
            boolean overlaps = busy.stream()
                    .anyMatch(b -> slotStart.isBefore(b.end()) && b.start().isBefore(end));
            if (start.isAfter(now) && !overlaps) {
                slots.add(start.toLocalTime());
            }
        }
        return slots;
    }

    public boolean isFree(Master master, LocalDate date, LocalTime time, int serviceDuration,
                          List<Booking> existingBookings) {
        return getFreeSlots(master, date, serviceDuration, existingBookings).contains(time);
    }
}
