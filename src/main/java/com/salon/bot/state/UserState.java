package com.salon.bot.state;

/**
 * Шаги диалога записи.
 */
public enum UserState {
    START,
    CHOOSING_SERVICE,
    CHOOSING_MASTER,
    CHOOSING_DATE,
    CHOOSING_TIME,
    ENTERING_NAME,
    ENTERING_PHONE,
    CONFIRMING
}
