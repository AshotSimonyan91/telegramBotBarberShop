package com.salon.bot.model;

import lombok.Builder;
import lombok.Value;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Set;

/**
 * Мастер салона (лист «Мастера»).
 */
@Value
@Builder
public class Master {
    String name;
    Set<DayOfWeek> workDays;
    LocalTime workStart;
    LocalTime workEnd;
    boolean active;

    public boolean worksOn(LocalDate date) {
        return workDays.contains(date.getDayOfWeek());
    }
}
