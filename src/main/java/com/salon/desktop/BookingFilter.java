package com.salon.desktop;

import com.salon.bot.model.Booking;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;

/**
 * Фильтр таблицы записей: период, мастер, отменённые, поиск по клиенту/телефону.
 */
record BookingFilter(Period period, String master, boolean showCancelled, String query) {

    /** Значение фильтра «все мастера». */
    static final String ALL_MASTERS = "Все мастера";

    enum Period {
        TODAY("Сегодня"),
        TOMORROW("Завтра"),
        WEEK("Ближайшие 7 дней"),
        UPCOMING("Все предстоящие"),
        PAST("Прошедшие"),
        ALL("Все");

        private final String title;

        Period(String title) {
            this.title = title;
        }

        @Override
        public String toString() {
            return title;
        }
    }

    List<Booking> apply(List<Booking> bookings, LocalDateTime now) {
        Comparator<Booking> order = Comparator.comparing(Booking::start);
        return bookings.stream()
                .filter(periodFilter(now))
                .filter(b -> showCancelled || b.isActive())
                .filter(b -> master == null || ALL_MASTERS.equals(master) || b.getMasterName().equalsIgnoreCase(master))
                .filter(this::matchesQuery)
                .sorted(period == Period.PAST ? order.reversed() : order)
                .toList();
    }

    private Predicate<Booking> periodFilter(LocalDateTime now) {
        LocalDate today = now.toLocalDate();
        return switch (period) {
            case TODAY -> b -> b.getDate().equals(today);
            case TOMORROW -> b -> b.getDate().equals(today.plusDays(1));
            case WEEK -> b -> !b.getDate().isBefore(today) && b.getDate().isBefore(today.plusDays(7));
            case UPCOMING -> b -> !b.start().isBefore(now);
            case PAST -> b -> b.start().isBefore(now);
            case ALL -> b -> true;
        };
    }

    private boolean matchesQuery(Booking b) {
        if (query == null || query.isBlank()) {
            return true;
        }
        String q = query.trim().toLowerCase(Locale.ROOT);
        String digits = q.replaceAll("\\D", "");
        return b.getClientName().toLowerCase(Locale.ROOT).contains(q)
                || (b.getUsername() != null && b.getUsername().toLowerCase(Locale.ROOT).contains(q))
                || (!digits.isEmpty() && b.getPhone().replaceAll("\\D", "").contains(digits))
                || String.valueOf(b.getId()).equals(q);
    }
}
