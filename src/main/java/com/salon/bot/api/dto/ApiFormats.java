package com.salon.bot.api.dto;

import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;

/** Форматы дат и времени в API (ISO, без секунд). */
public final class ApiFormats {

    public static final String DATE = "yyyy-MM-dd";
    public static final String TIME = "HH:mm";
    public static final String DATE_TIME = "yyyy-MM-dd'T'HH:mm";

    /**
     * Разбор входных дат и времени — строгий: несуществующие даты (2027-02-30) и «24:00» отклоняются,
     * а не сдвигаются. В режиме STRICT год задаётся через «uuuu» (у «yyyy» нужна эра).
     */
    public static final DateTimeFormatter DATE_FORMAT =
            DateTimeFormatter.ofPattern("uuuu-MM-dd").withResolverStyle(ResolverStyle.STRICT);
    public static final DateTimeFormatter TIME_FORMAT =
            DateTimeFormatter.ofPattern(TIME).withResolverStyle(ResolverStyle.STRICT);

    private ApiFormats() {
    }
}
