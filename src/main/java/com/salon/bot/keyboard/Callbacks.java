package com.salon.bot.keyboard;

/**
 * Короткие callback data (лимит Telegram — 64 байта).
 */
public final class Callbacks {

    public static final String HOME = "m:home";
    public static final String BOOK = "m:book";
    public static final String MY_BOOKINGS = "m:my";
    public static final String BACK = "b";
    /** s:<индекс услуги> */
    public static final String SERVICE = "s:";
    /** w:<индекс мастера> или w:* — любой свободный */
    public static final String MASTER = "w:";
    public static final String ANY_MASTER = "w:*";
    /** d:yyyyMMdd */
    public static final String DATE = "d:";
    /** t:HHmm */
    public static final String TIME = "t:";
    public static final String NAME_FROM_TELEGRAM = "n:tg";
    public static final String CONFIRM = "ok";
    public static final String ABORT = "no";
    /** x:<ID записи> */
    public static final String CANCEL_BOOKING = "x:";

    private Callbacks() {
    }
}
