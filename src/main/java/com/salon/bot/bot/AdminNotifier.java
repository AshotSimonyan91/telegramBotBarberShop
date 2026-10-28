package com.salon.bot.bot;

import com.salon.bot.config.BotProperties;
import com.salon.bot.handler.Texts;
import com.salon.bot.model.Booking;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * Уведомления администратору (ADMIN_CHAT_ID).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AdminNotifier {

    private static final Duration BUSY_NOTICE_INTERVAL = Duration.ofMinutes(10);

    private final BotProperties properties;
    private final Messenger messenger;
    private final Clock clock;

    private volatile Instant lastBusyNotice;

    public boolean isAdmin(long chatId) {
        return properties.adminChatId() != null && properties.adminChatId() == chatId;
    }

    public void newBooking(Booking booking) {
        notifyAdmin("🆕 Новая запись №" + booking.getId() + "\n\n" + Texts.adminDetails(booking));
    }

    public void cancelled(Booking booking) {
        notifyAdmin("❌ Отмена записи №" + booking.getId() + "\n\n" + Texts.adminDetails(booking));
    }

    /** Файл занят — не чаще раза в 10 минут, чтобы не засыпать админа сообщениями. */
    public void storageBusy() {
        Instant now = clock.instant();
        Instant last = lastBusyNotice;
        if (last != null && Duration.between(last, now).compareTo(BUSY_NOTICE_INTERVAL) < 0) {
            return;
        }
        lastBusyNotice = now;
        notifyAdmin("⚠️ Бот не может записать данные: Excel-файл занят.\n"
                + "Пожалуйста, закройте Excel-файл " + properties.excelFile().getFileName() + ".");
    }

    private void notifyAdmin(String text) {
        if (properties.adminChatId() == null) {
            log.debug("ADMIN_CHAT_ID не задан, уведомление не отправлено");
            return;
        }
        messenger.send(properties.adminChatId(), text);
    }
}
