package com.salon.bot.keyboard;

import com.salon.bot.handler.Texts;
import com.salon.bot.model.Booking;
import com.salon.bot.model.Master;
import com.salon.bot.model.Service;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboardRemove;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.KeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.KeyboardRow;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

@Component
public class KeyboardFactory {

    public static final String BACK_TEXT = "⬅️ Назад";
    public static final String SEND_CONTACT_TEXT = "📱 Отправить контакт";

    private static final DateTimeFormatter DATE_DATA = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final DateTimeFormatter TIME_DATA = DateTimeFormatter.ofPattern("HHmm");

    public InlineKeyboardMarkup mainMenu() {
        return markup(List.of(
                row(button("✂️ Записаться", Callbacks.BOOK)),
                row(button("📋 Мои записи", Callbacks.MY_BOOKINGS))));
    }

    public InlineKeyboardMarkup services(List<Service> services) {
        List<InlineKeyboardRow> rows = new ArrayList<>();
        for (int i = 0; i < services.size(); i++) {
            Service s = services.get(i);
            rows.add(row(button(s.getName() + " — " + Texts.price(s.getPrice()), Callbacks.SERVICE + i)));
        }
        rows.add(backRow());
        return markup(rows);
    }

    public InlineKeyboardMarkup masters(List<Master> masters) {
        List<InlineKeyboardRow> rows = new ArrayList<>();
        for (int i = 0; i < masters.size(); i++) {
            rows.add(row(button(masters.get(i).getName(), Callbacks.MASTER + i)));
        }
        rows.add(row(button("🎲 Любой свободный", Callbacks.ANY_MASTER)));
        rows.add(backRow());
        return markup(rows);
    }

    public InlineKeyboardMarkup dates(List<LocalDate> dates) {
        List<InlineKeyboardButton> buttons = dates.stream()
                .map(d -> button(Texts.shortDate(d), Callbacks.DATE + d.format(DATE_DATA)))
                .toList();
        List<InlineKeyboardRow> rows = grid(buttons, 3);
        rows.add(backRow());
        return markup(rows);
    }

    public InlineKeyboardMarkup times(List<LocalTime> times) {
        List<InlineKeyboardButton> buttons = times.stream()
                .map(t -> button(Texts.time(t), Callbacks.TIME + t.format(TIME_DATA)))
                .toList();
        List<InlineKeyboardRow> rows = grid(buttons, 4);
        rows.add(backRow());
        return markup(rows);
    }

    public InlineKeyboardMarkup noSlots() {
        return markup(List.of(row(button("📅 Выбрать другую дату", Callbacks.BACK))));
    }

    public InlineKeyboardMarkup name(String telegramName) {
        List<InlineKeyboardRow> rows = new ArrayList<>();
        if (telegramName != null && !telegramName.isBlank()) {
            rows.add(row(button("👤 " + telegramName, Callbacks.NAME_FROM_TELEGRAM)));
        }
        rows.add(backRow());
        return markup(rows);
    }

    public InlineKeyboardMarkup backOnly() {
        return markup(List.of(backRow()));
    }

    public ReplyKeyboardMarkup requestContact() {
        KeyboardRow contact = new KeyboardRow();
        contact.add(KeyboardButton.builder().text(SEND_CONTACT_TEXT).requestContact(true).build());
        KeyboardRow back = new KeyboardRow();
        back.add(KeyboardButton.builder().text(BACK_TEXT).build());
        return ReplyKeyboardMarkup.builder()
                .keyboardRow(contact)
                .keyboardRow(back)
                .resizeKeyboard(true)
                .oneTimeKeyboard(true)
                .build();
    }

    public ReplyKeyboardRemove removeReplyKeyboard() {
        return ReplyKeyboardRemove.builder().removeKeyboard(true).build();
    }

    public InlineKeyboardMarkup confirm() {
        return markup(List.of(
                row(button("✅ Подтвердить", Callbacks.CONFIRM), button("✖️ Отмена", Callbacks.ABORT)),
                backRow()));
    }

    public InlineKeyboardMarkup afterBooking() {
        return markup(List.of(
                row(button("📋 Мои записи", Callbacks.MY_BOOKINGS)),
                row(button("🏠 В меню", Callbacks.HOME))));
    }

    public InlineKeyboardMarkup myBookings(List<Booking> bookings) {
        List<InlineKeyboardRow> rows = new ArrayList<>();
        for (Booking b : bookings) {
            rows.add(row(button("❌ Отменить №" + b.getId() + " (" + Texts.shortDate(b.getDate()) + " "
                    + Texts.time(b.getTime()) + ")", Callbacks.CANCEL_BOOKING + b.getId())));
        }
        if (bookings.isEmpty()) {
            rows.add(row(button("✂️ Записаться", Callbacks.BOOK)));
        }
        rows.add(row(button("🏠 В меню", Callbacks.HOME)));
        return markup(rows);
    }

    private static List<InlineKeyboardRow> grid(List<InlineKeyboardButton> buttons, int perRow) {
        List<InlineKeyboardRow> rows = new ArrayList<>();
        for (int i = 0; i < buttons.size(); i += perRow) {
            rows.add(new InlineKeyboardRow(buttons.subList(i, Math.min(i + perRow, buttons.size()))));
        }
        return rows;
    }

    private static InlineKeyboardRow backRow() {
        return row(button(BACK_TEXT, Callbacks.BACK));
    }

    private static InlineKeyboardRow row(InlineKeyboardButton... buttons) {
        return new InlineKeyboardRow(buttons);
    }

    private static InlineKeyboardButton button(String text, String data) {
        return InlineKeyboardButton.builder().text(text).callbackData(data).build();
    }

    private static InlineKeyboardMarkup markup(List<InlineKeyboardRow> rows) {
        return new InlineKeyboardMarkup(rows);
    }
}
