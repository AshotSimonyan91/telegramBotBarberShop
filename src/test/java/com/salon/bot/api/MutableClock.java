package com.salon.bot.api;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

/** Управляемые часы для тестов: время задаётся как локальное в зоне бота. */
public class MutableClock extends Clock {

    private final ZoneId zone;
    private volatile Instant instant;

    public MutableClock(ZoneId zone, LocalDateTime initial) {
        this.zone = zone;
        set(initial);
    }

    public void set(LocalDateTime local) {
        this.instant = local.atZone(zone).toInstant();
    }

    public void set(Instant instant) {
        this.instant = instant;
    }

    @Override
    public ZoneId getZone() {
        return zone;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        MutableClock copy = new MutableClock(zone, LocalDateTime.now());
        copy.instant = instant;
        return copy;
    }

    @Override
    public Instant instant() {
        return instant;
    }
}
