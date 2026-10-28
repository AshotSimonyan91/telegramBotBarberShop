package com.salon.bot.api.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.salon.bot.model.Master;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.List;

/** Мастер салона; рабочие дни — по порядку недели (MONDAY..SUNDAY). */
public record MasterDto(
        String name,
        List<DayOfWeek> workDays,
        @JsonFormat(pattern = ApiFormats.TIME) LocalTime workStart,
        @JsonFormat(pattern = ApiFormats.TIME) LocalTime workEnd,
        boolean active
) {

    public static MasterDto from(Master m) {
        List<DayOfWeek> days = m.getWorkDays() == null ? List.of() : m.getWorkDays().stream().sorted().toList();
        return new MasterDto(m.getName(), days, m.getWorkStart(), m.getWorkEnd(), m.isActive());
    }
}
