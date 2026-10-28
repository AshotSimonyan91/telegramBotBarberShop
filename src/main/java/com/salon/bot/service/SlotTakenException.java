package com.salon.bot.service;

/**
 * Выбранное время успели занять.
 */
public class SlotTakenException extends RuntimeException {

    public SlotTakenException() {
        super("Слот уже занят");
    }
}
