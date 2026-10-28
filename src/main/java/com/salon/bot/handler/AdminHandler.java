package com.salon.bot.handler;

import com.salon.bot.bot.AdminNotifier;
import com.salon.bot.bot.Messenger;
import com.salon.bot.model.Booking;
import com.salon.bot.service.BookingService;
import com.salon.bot.storage.BookingRepository;
import com.salon.bot.storage.ExportedFile;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.objects.Update;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * /admin и /export — только для ADMIN_CHAT_ID.
 */
@Slf4j
@Order(30)
@Component
@RequiredArgsConstructor
public class AdminHandler implements UpdateHandler {

    private final Messenger messenger;
    private final AdminNotifier adminNotifier;
    private final BookingService bookingService;
    private final BookingRepository repository;
    private final Clock clock;

    @Override
    public boolean supports(Update update) {
        return Commands.is(update, "/admin") || Commands.is(update, "/export");
    }

    @Override
    public void handle(Update update) {
        long chatId = update.getMessage().getChatId();
        if (!adminNotifier.isAdmin(chatId)) {
            log.warn("Попытка вызвать админ-команду из чата {}", chatId);
            messenger.send(chatId, "Эта команда доступна только администратору салона.");
            return;
        }
        if (Commands.is(update, "/export")) {
            export(chatId);
        } else {
            schedule(chatId);
        }
    }

    private void schedule(long chatId) {
        LocalDate today = LocalDate.now(clock);
        String text = day("Сегодня", today) + "\n\n" + day("Завтра", today.plusDays(1));
        messenger.send(chatId, text);
    }

    private String day(String title, LocalDate date) {
        List<Booking> bookings = bookingService.activeBookingsOn(date);
        StringBuilder sb = new StringBuilder("📅 ").append(title).append(", ").append(Texts.fullDate(date))
                .append(" — ").append(bookings.size()).append(" зап.\n");
        if (bookings.isEmpty()) {
            sb.append("Записей нет.");
        }
        for (Booking b : bookings) {
            sb.append("\n").append(Texts.time(b.getTime())).append(" — ").append(b.getClientName())
                    .append(", ").append(b.getPhone()).append("\n   ").append(b.getServiceName())
                    .append(" · ").append(b.getMasterName()).append(" · №").append(b.getId())
                    .append(" · ").append(b.getSource());
        }
        return sb.toString();
    }

    private void export(long chatId) {
        ExportedFile file = repository.export();
        String caption = "Записи на " + LocalDateTime.now(clock).format(DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm"));
        if (!messenger.sendDocument(chatId, file.fileName(), file.content(), caption)) {
            messenger.send(chatId, "Не удалось отправить файл. Попробуйте ещё раз позже.");
        }
    }
}
