package com.salon.bot.api.dto;

import com.salon.bot.model.Service;

/** Услуга салона. */
public record ServiceDto(String name, int durationMinutes, int price) {

    public static ServiceDto from(Service s) {
        return new ServiceDto(s.getName(), s.getDurationMinutes(), s.getPrice());
    }
}
