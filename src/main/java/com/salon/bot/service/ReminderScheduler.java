package com.salon.bot.service;

import com.salon.bot.bot.AdminNotifier;
import com.salon.bot.bot.Messenger;
import com.salon.bot.handler.Texts;
import com.salon.bot.model.Booking;
import com.salon.bot.storage.BookingRepository;
import com.salon.bot.storage.StorageBusyException;
import com.salon.bot.storage.StorageException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Stream;

/**
 * Каждые 10 минут напоминает клиентам о визитах, до которых осталось не больше 2 часов.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReminderScheduler {

    static final Duration REMIND_BEFORE = Duration.ofHours(2);

    private final BookingRepository repository;
    private final Messenger messenger;
    private final AdminNotifier adminNotifier;
    private final Clock clock;

    @Scheduled(fixedRate = 10 * 60 * 1000, initialDelay = 60 * 1000)
    public void sendReminders() {
        try {
            for (Booking booking : dueBookings()) {
                Integer sent = messenger.send(booking.getTelegramId(), "⏰ Напоминание о визите\n\n"
                        + "Ждём вас сегодня в " + Texts.time(booking.getTime()) + "!\n\n"
                        + Texts.clientDetails(booking) + "\nНомер записи: №" + booking.getId()
                        + "\n\nЕсли планы изменились, отмените запись в разделе «Мои записи».");
                if (sent != null) {
                    repository.markReminderSent(booking.getId());
                    log.info("Отправлено напоминание по записи №{}", booking.getId());
                }
            }
        } catch (StorageException e) {
            log.error("Не удалось обработать напоминания", e);
            if (e instanceof StorageBusyException) {
                adminNotifier.storageBusy();
            }
        }
    }

    List<Booking> dueBookings() {
        LocalDateTime now = LocalDateTime.now(clock);
        LocalDateTime until = now.plus(REMIND_BEFORE);
        LocalDate today = now.toLocalDate();
        return Stream.of(today, today.plusDays(1))
                .filter(d -> !d.isAfter(until.toLocalDate()))
                .flatMap(d -> repository.getBookings(d).stream())
                .filter(Booking::isActive)
                .filter(b -> b.getTelegramId() != 0) // запись из настольного приложения — писать некому
                .filter(b -> !b.isReminderSent())
                .filter(b -> b.start().isAfter(now) && !b.start().isAfter(until))
                .toList();
    }
}
