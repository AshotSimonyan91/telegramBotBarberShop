package com.salon.bot.api.dto;

import java.time.format.DateTimeFormatter;

/** Форматы дат и времени в API (ISO, без секунд). */
public final class ApiFormats {

    public static final String DATE = "yyyy-MM-dd";
    public static final String TIME = "HH:mm";
    public static final String DATE_TIME = "yyyy-MM-dd'T'HH:mm";

    public static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern(DATE);
    public static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern(TIME);

    private ApiFormats() {
    }
}
