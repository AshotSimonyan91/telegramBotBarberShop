package com.salon.bot.state;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Сессии пользователей в памяти (после перезапуска бота диалог начинается заново).
 */
@Component
public class SessionStorage {

    private final Map<Long, UserSession> sessions = new ConcurrentHashMap<>();

    public UserSession get(long chatId) {
        return sessions.computeIfAbsent(chatId, UserSession::new);
    }

    public void remove(long chatId) {
        sessions.remove(chatId);
    }
}
