package com.salon.desktop;

import com.salon.bot.model.Booking;
import com.salon.bot.model.BookingStatus;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class BookingFilterTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 22, 12, 0);

    private static Booking booking(long id, LocalDate date, String time, String master, BookingStatus status) {
        return Booking.builder()
                .id(id)
                .clientName("Клиент " + id)
                .username("user" + id)
                .phone("+374 91 00-00-0" + id)
                .serviceName("Стрижка мужская")
                .masterName(master)
                .date(date)
                .time(LocalTime.parse(time))
                .status(status)
                .durationMinutes(45)
                .build();
    }

    private final List<Booking> bookings = List.of(
            booking(1, NOW.toLocalDate(), "10:00", "Анна", BookingStatus.CONFIRMED),
            booking(2, NOW.toLocalDate(), "15:00", "Арам", BookingStatus.CONFIRMED),
            booking(3, NOW.toLocalDate().plusDays(1), "11:00", "Анна", BookingStatus.CANCELLED),
            booking(4, NOW.toLocalDate().plusDays(10), "11:00", "Анна", BookingStatus.CONFIRMED),
            booking(5, NOW.toLocalDate().minusDays(3), "11:00", "Арам", BookingStatus.CONFIRMED));

    private List<Long> ids(BookingFilter.Period period, String master, boolean cancelled, String query) {
        return new BookingFilter(period, master, cancelled, query).apply(bookings, NOW).stream()
                .map(Booking::getId).toList();
    }

    @Test
    void periods() {
        assertThat(ids(BookingFilter.Period.TODAY, null, true, "")).containsExactly(1L, 2L);
        assertThat(ids(BookingFilter.Period.TOMORROW, null, true, "")).containsExactly(3L);
        assertThat(ids(BookingFilter.Period.WEEK, null, true, "")).containsExactly(1L, 2L, 3L);
        assertThat(ids(BookingFilter.Period.UPCOMING, null, true, "")).containsExactly(2L, 3L, 4L);
        assertThat(ids(BookingFilter.Period.PAST, null, true, "")).containsExactly(1L, 5L);
        assertThat(ids(BookingFilter.Period.ALL, null, true, "")).containsExactly(5L, 1L, 2L, 3L, 4L);
    }

    @Test
    void hidesCancelledAndFiltersByMaster() {
        assertThat(ids(BookingFilter.Period.ALL, BookingFilter.ALL_MASTERS, false, "")).containsExactly(5L, 1L, 2L, 4L);
        assertThat(ids(BookingFilter.Period.ALL, "анна", false, "")).containsExactly(1L, 4L);
    }

    @Test
    void searchesByNamePhoneUsernameAndId() {
        assertThat(ids(BookingFilter.Period.ALL, null, true, "клиент 2")).containsExactly(2L);
        assertThat(ids(BookingFilter.Period.ALL, null, true, "0000-04")).containsExactly(4L);
        assertThat(ids(BookingFilter.Period.ALL, null, true, "USER5")).containsExactly(5L);
        assertThat(ids(BookingFilter.Period.ALL, null, true, "3")).contains(3L);
    }
}
