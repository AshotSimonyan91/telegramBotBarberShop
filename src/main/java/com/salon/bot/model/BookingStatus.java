package com.salon.bot.model;

import java.util.Arrays;

public enum BookingStatus {
    CONFIRMED("Подтверждена"),
    CANCELLED("Отменена");

    private final String title;

    BookingStatus(String title) {
        this.title = title;
    }

    public String title() {
        return title;
    }

    public static BookingStatus fromTitle(String title) {
        return Arrays.stream(values())
                .filter(s -> s.title.equalsIgnoreCase(title.trim()))
                .findFirst()
                .orElse(CONFIRMED);
    }
}
