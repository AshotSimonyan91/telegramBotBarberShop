package com.salon.bot.handler;

import com.salon.bot.model.Booking;
import org.telegram.telegrambots.meta.api.objects.User;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Форматирование текстов для пользователя и проверка ввода.
 */
public final class Texts {

    private static final String[] DAYS = {"Пн", "Вт", "Ср", "Чт", "Пт", "Сб", "Вс"};
    private static final DateTimeFormatter DAY_MONTH = DateTimeFormatter.ofPattern("dd.MM");
    private static final DateTimeFormatter FULL_DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");
    private static final Pattern PHONE = Pattern.compile("\\+?\\d{8,15}");
    private static final Pattern NAME = Pattern.compile("[\\p{L}][\\p{L} .'\\-]{1,49}");

    private Texts() {
    }

    /** «Чт 22.10» */
    public static String shortDate(LocalDate date) {
        return DAYS[date.getDayOfWeek().getValue() - 1] + " " + date.format(DAY_MONTH);
    }

    /** «22.10.2026 (чт)» */
    public static String fullDate(LocalDate date) {
        return date.format(FULL_DATE) + " (" + DAYS[date.getDayOfWeek().getValue() - 1].toLowerCase() + ")";
    }

    public static String time(LocalTime time) {
        return time.format(TIME);
    }

    /** «5 000 ֏» */
    public static String price(int price) {
        return String.format("%,d", price).replace(',', ' ').replace(' ', ' ') + " ֏";
    }

    public static String clientDetails(Booking b) {
        return "Услуга: " + b.getServiceName() + "\n"
                + "Мастер: " + b.getMasterName() + "\n"
                + "Дата: " + fullDate(b.getDate()) + "\n"
                + "Время: " + time(b.getTime());
    }

    public static String adminDetails(Booking b) {
        String username = b.getUsername() == null || b.getUsername().isBlank() ? "" : " (@" + b.getUsername() + ")";
        return "Клиент: " + b.getClientName() + username + "\n"
                + "Телефон: " + b.getPhone() + "\n"
                + clientDetails(b) + "\n"
                + "Источник: " + b.getSource();
    }

    /** Уведомление клиенту об отмене записи салоном (приложение администратора, REST API). */
    public static String salonCancellationNotice(Booking b) {
        return "❌ Ваша запись №" + b.getId() + " отменена салоном.\n\n"
                + clientDetails(b)
                + "\n\nЧтобы записаться снова, нажмите /start.";
    }

    /** Короткая строка для списков: «№5 · Чт 22.10 10:00 · Стрижка мужская · Анна» */
    public static String line(Booking b) {
        return "№" + b.getId() + " · " + shortDate(b.getDate()) + " " + time(b.getTime())
                + " · " + b.getServiceName() + " · " + b.getMasterName();
    }

    public static String telegramName(User user) {
        if (user == null) {
            return "";
        }
        String last = user.getLastName() == null ? "" : " " + user.getLastName();
        return (user.getFirstName() + last).trim();
    }

    /** Нормализует номер: убирает пробелы, скобки, дефисы; 8–15 цифр, допускается «+». */
    public static Optional<String> normalizePhone(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        String phone = raw.replaceAll("[\\s()\\-.]", "");
        return PHONE.matcher(phone).matches() ? Optional.of(phone) : Optional.empty();
    }

    public static boolean isValidName(String name) {
        return name != null && NAME.matcher(name.trim()).matches();
    }
}
