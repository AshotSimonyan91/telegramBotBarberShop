package com.salon.bot.handler;

import org.telegram.telegrambots.meta.api.objects.Update;

/**
 * Разбор текстовых команд вида «/cmd», «/cmd@BotName», «/cmd аргумент».
 */
final class Commands {

    private Commands() {
    }

    static boolean is(Update update, String command) {
        if (!update.hasMessage() || !update.getMessage().hasText()) {
            return false;
        }
        String first = update.getMessage().getText().trim().split("\\s+", 2)[0];
        int at = first.indexOf('@');
        return (at >= 0 ? first.substring(0, at) : first).equalsIgnoreCase(command);
    }

    static String argument(Update update) {
        String[] parts = update.getMessage().getText().trim().split("\\s+", 2);
        return parts.length > 1 ? parts[1] : null;
    }
}
