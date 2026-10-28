package com.salon.bot.service;

import com.salon.bot.model.Booking;
import com.salon.bot.model.BookingStatus;
import com.salon.bot.storage.BookingRepository;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** BookingService.findBookings / findBooking / ADMIN_SOURCE на подменённом хранилище. */
class BookingServiceQueryTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Yerevan");
    private static final LocalDate D1 = LocalDate.of(2026, 10, 22);
    private static final LocalDate D2 = LocalDate.of(2026, 10, 23);

    private final BookingRepository repository = mock(BookingRepository.class);
    private final Clock clock = Clock.fixed(java.time.Instant.parse("2026-10-21T05:00:00Z"), ZONE);
    private final BookingService service = new BookingService(repository, new SlotCalculator(clock), clock);

    private static Booking b(long id, String client, String master, LocalDate date, String time,
                             BookingStatus status, String phone, String username, String serviceName) {
        return Booking.builder().id(id).clientName(client).masterName(master).date(date).time(LocalTime.parse(time))
                .status(status).phone(phone).username(username).serviceName(serviceName).durationMinutes(30).build();
    }

    private static Booking b(long id, String client, String master, LocalDate date, String time) {
        return b(id, client, master, date, time, BookingStatus.CONFIRMED, "+374", "u", "Борода");
    }

    @Test
    void adminSourceConstantMatchesDesktopSource() {
        assertThat(BookingService.ADMIN_SOURCE).isEqualTo("администратор");
    }

    @Test
    void allQueryReturnsEverythingSortedByDateTimeId() {
        when(repository.getAllBookings()).thenReturn(List.of(
                b(3, "c", "Анна", D2, "10:00"), b(2, "b", "Анна", D1, "12:00"),
                b(1, "a", "Арам", D1, "12:00"), b(4, "d", "Анна", D1, "09:00")));
        assertThat(service.findBookings(BookingQuery.all())).extracting(Booking::getId).containsExactly(4L, 1L, 2L, 3L);
    }

    @Test
    void exactDateReadsOnlyThatDay() {
        when(repository.getBookings(D1)).thenReturn(List.of(b(1, "a", "Анна", D1, "10:00")));
        List<Booking> result = service.findBookings(new BookingQuery(D1, null, null, null, null, null));
        assertThat(result).hasSize(1);
        verify(repository, never()).getAllBookings();
    }

    @Test
    void exactDateStillFiltersByDateIfRepositoryReturnsOthers() {
        when(repository.getBookings(D1)).thenReturn(List.of(b(1, "a", "Анна", D1, "10:00"), b(2, "b", "Анна", D2, "10:00")));
        assertThat(service.findBookings(new BookingQuery(D1, null, null, null, null, null)))
                .extracting(Booking::getId).containsExactly(1L);
    }

    @Test
    void periodBoundsInclusive() {
        when(repository.getAllBookings()).thenReturn(List.of(
                b(1, "a", "Анна", D1.minusDays(1), "10:00"), b(2, "b", "Анна", D1, "10:00"),
                b(3, "c", "Анна", D2, "10:00"), b(4, "d", "Анна", D2.plusDays(1), "10:00")));
        assertThat(service.findBookings(new BookingQuery(null, D1, D2, null, null, null)))
                .extracting(Booking::getId).containsExactly(2L, 3L);
        assertThat(service.findBookings(new BookingQuery(null, D2, null, null, null, null)))
                .extracting(Booking::getId).containsExactly(3L, 4L);
        assertThat(service.findBookings(new BookingQuery(null, null, D1, null, null, null)))
                .extracting(Booking::getId).containsExactly(1L, 2L);
    }

    @Test
    void statusAndMasterFilters() {
        when(repository.getAllBookings()).thenReturn(List.of(
                b(1, "a", "Анна", D1, "10:00", BookingStatus.CONFIRMED, "1", "u", "s"),
                b(2, "b", "Арам", D1, "11:00", BookingStatus.CANCELLED, "2", "u", "s")));
        assertThat(service.findBookings(new BookingQuery(null, null, null, BookingStatus.CANCELLED, null, null)))
                .extracting(Booking::getId).containsExactly(2L);
        assertThat(service.findBookings(new BookingQuery(null, null, null, null, null, " АНН ")))
                .as("имя мастера — точное").isEmpty();
        assertThat(service.findBookings(new BookingQuery(null, null, null, null, null, " анна ")))
                .extracting(Booking::getId).containsExactly(1L);
        assertThat(service.findBookings(new BookingQuery(null, null, null, null, null, "  ")))
                .hasSize(2);
    }

    @Test
    void textSearchIsCaseInsensitiveAndNullSafe() {
        when(repository.getAllBookings()).thenReturn(List.of(
                b(1, "Иван Петров", "Анна", D1, "10:00", BookingStatus.CONFIRMED, "+37491", null, "Борода"),
                b(2, null, "Анна", D1, "11:00", BookingStatus.CONFIRMED, null, null, null),
                b(3, "Zoe", "Анна", D1, "12:00", BookingStatus.CONFIRMED, "+1", "ZOE_TG", "Окрашивание")));
        assertThat(service.findBookings(new BookingQuery(null, null, null, null, "ПЕТРОВ", null)))
                .extracting(Booking::getId).containsExactly(1L);
        assertThat(service.findBookings(new BookingQuery(null, null, null, null, "zoe_tg", null)))
                .extracting(Booking::getId).containsExactly(3L);
        assertThat(service.findBookings(new BookingQuery(null, null, null, null, "ЗОЕ", null)))
                .as("латиница не матчится кириллицей").isEmpty();
        assertThat(service.findBookings(new BookingQuery(null, null, null, null, "z", null)))
                .extracting(Booking::getId).containsExactly(3L);
    }

    @Test
    void findBookingByIdIncludesCancelledAndMissing() {
        when(repository.getAllBookings()).thenReturn(List.of(
                b(1, "a", "Анна", D1, "10:00", BookingStatus.CANCELLED, "1", "u", "s")));
        assertThat(service.findBooking(1)).isPresent();
        assertThat(service.findBooking(2)).isEmpty();
        assertThat(service.findBooking(0)).isEmpty();
        assertThat(service.findBooking(-1)).isEmpty();
    }
}
