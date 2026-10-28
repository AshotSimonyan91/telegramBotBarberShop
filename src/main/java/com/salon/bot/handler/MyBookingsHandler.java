package com.salon.bot.handler;

import com.salon.bot.bot.AdminNotifier;
import com.salon.bot.bot.Messenger;
import com.salon.bot.keyboard.Callbacks;
import com.salon.bot.keyboard.KeyboardFactory;
import com.salon.bot.model.Booking;
import com.salon.bot.service.BookingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.Update;

import java.util.List;
import java.util.Optional;

/**
 * «Мои записи» и отмена записи клиентом.
 */
@Slf4j
@Order(40)
@Component
@RequiredArgsConstructor
public class MyBookingsHandler implements UpdateHandler {

    private final Messenger messenger;
    private final KeyboardFactory keyboards;
    private final BookingService bookingService;
    private final AdminNotifier adminNotifier;

    @Override
    public boolean supports(Update update) {
        if (!update.hasCallbackQuery()) {
            return false;
        }
        String data = update.getCallbackQuery().getData();
        return Callbacks.MY_BOOKINGS.equals(data) || data.startsWith(Callbacks.CANCEL_BOOKING);
    }

    @Override
    public void handle(Update update) {
        CallbackQuery query = update.getCallbackQuery();
        long userId = query.getFrom().getId();
        String notice = "";

        if (query.getData().startsWith(Callbacks.CANCEL_BOOKING)) {
            long bookingId = Long.parseLong(query.getData().substring(Callbacks.CANCEL_BOOKING.length()));
            Optional<Booking> cancelled = bookingService.cancel(bookingId, userId);
            if (cancelled.isPresent()) {
                log.info("Клиент {} отменил запись №{}", userId, bookingId);
                adminNotifier.cancelled(cancelled.get());
                messenger.answer(query.getId(), "Запись №" + bookingId + " отменена");
                notice = "✅ Запись №" + bookingId + " отменена.\n\n";
            } else {
                messenger.answer(query.getId(), "Эта запись уже отменена или не найдена");
            }
        }

        List<Booking> bookings = bookingService.upcomingBookings(userId);
        messenger.edit(userId, query.getMessage().getMessageId(), notice + text(bookings), keyboards.myBookings(bookings));
    }

    static String text(List<Booking> bookings) {
        if (bookings.isEmpty()) {
            return "📋 У вас нет предстоящих записей.";
        }
        StringBuilder sb = new StringBuilder("📋 Ваши предстоящие записи:\n");
        for (Booking b : bookings) {
            sb.append("\n").append(Texts.line(b));
        }
        sb.append("\n\nЧтобы отменить запись, нажмите кнопку ниже.");
        return sb.toString();
    }
}
