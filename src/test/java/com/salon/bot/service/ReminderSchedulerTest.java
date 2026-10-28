package com.salon.bot.service;

import com.salon.bot.bot.AdminNotifier;
import com.salon.bot.bot.Messenger;
import com.salon.bot.model.Booking;
import com.salon.bot.model.BookingStatus;
import com.salon.bot.storage.BookingRepository;
import com.salon.bot.storage.StorageBusyException;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Напоминания: записи администратора (telegramId=0) пропускаются, границы окна 2 часа, часовой пояс. */
class ReminderSchedulerTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Yerevan");
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 21, 9, 0);

    private final BookingRepository repository = mock(BookingRepository.class);
    private final Messenger messenger = mock(Messenger.class);
    private final AdminNotifier adminNotifier = mock(AdminNotifier.class);
    private final Clock clock = Clock.fixed(NOW.atZone(ZONE).toInstant(), ZONE);
    private final ReminderScheduler scheduler = new ReminderScheduler(repository, messenger, adminNotifier, clock);

    private static Booking booking(long id, long tg, LocalDate date, String time) {
        return Booking.builder().id(id).telegramId(tg).clientName("c").phone("+1").serviceName("Борода")
                .masterName("Анна").date(date).time(LocalTime.parse(time)).durationMinutes(30)
                .status(BookingStatus.CONFIRMED).build();
    }

    private static final LocalDate TODAY = NOW.toLocalDate();

    @Test
    void adminBookingsWithZeroTelegramIdAreSkipped() {
        when(repository.getBookings(TODAY)).thenReturn(List.of(booking(1, 0, TODAY, "10:00"), booking(2, 55, TODAY, "10:00")));
        when(repository.getBookings(TODAY.plusDays(1))).thenReturn(List.of());
        assertThat(scheduler.dueBookings()).extracting(Booking::getId).containsExactly(2L);
    }

    @Test
    void windowIsExclusiveAtNowAndInclusiveAtTwoHours() {
        when(repository.getBookings(TODAY)).thenReturn(List.of(
                booking(1, 5, TODAY, "09:00"), booking(2, 5, TODAY, "09:01"),
                booking(3, 5, TODAY, "11:00"), booking(4, 5, TODAY, "11:01")));
        when(repository.getBookings(TODAY.plusDays(1))).thenReturn(List.of());
        assertThat(scheduler.dueBookings()).extracting(Booking::getId).containsExactly(2L, 3L);
    }

    @Test
    void cancelledAndAlreadyRemindedAreSkipped() {
        Booking cancelled = booking(1, 5, TODAY, "10:00").withStatus(BookingStatus.CANCELLED);
        Booking reminded = booking(2, 5, TODAY, "10:00").withReminderSent(true);
        when(repository.getBookings(TODAY)).thenReturn(List.of(cancelled, reminded));
        when(repository.getBookings(TODAY.plusDays(1))).thenReturn(List.of());
        assertThat(scheduler.dueBookings()).isEmpty();
    }

    @Test
    void windowAcrossMidnight() {
        Clock late = Clock.fixed(LocalDateTime.of(2026, 10, 21, 23, 30).atZone(ZONE).toInstant(), ZONE);
        ReminderScheduler s = new ReminderScheduler(repository, messenger, adminNotifier, late);
        LocalDate tomorrow = TODAY.plusDays(1);
        when(repository.getBookings(TODAY)).thenReturn(List.of());
        when(repository.getBookings(tomorrow)).thenReturn(List.of(booking(1, 5, tomorrow, "01:00"), booking(2, 5, tomorrow, "01:31")));
        assertThat(s.dueBookings()).extracting(Booking::getId).containsExactly(1L);
    }

    @Test
    void marksReminderOnlyWhenMessageWasSent() {
        when(repository.getBookings(TODAY)).thenReturn(List.of(booking(1, 5, TODAY, "10:00"), booking(2, 6, TODAY, "10:30")));
        when(repository.getBookings(TODAY.plusDays(1))).thenReturn(List.of());
        when(messenger.send(eq(5L), anyString())).thenReturn(10);
        when(messenger.send(eq(6L), anyString())).thenReturn(null);
        scheduler.sendReminders();
        verify(repository).markReminderSent(1);
        verify(repository, never()).markReminderSent(2);
        verify(messenger, never()).send(eq(0L), anyString());
    }

    @Test
    void storageBusyNotifiesAdminAndDoesNotThrow() {
        when(repository.getBookings(TODAY)).thenThrow(new StorageBusyException("busy", null));
        scheduler.sendReminders();
        verify(adminNotifier).storageBusy();
        verify(messenger, never()).send(anyLong(), anyString());
    }

    @Test
    void usesBotTimezoneForToday() {
        // 20:30 UTC 21.10 -> 00:30 22.10 в Ереване: «сегодня» = 22.10
        Clock utcEvening = Clock.fixed(Instant.parse("2026-10-21T20:30:00Z"), ZONE);
        ReminderScheduler s = new ReminderScheduler(repository, messenger, adminNotifier, utcEvening);
        LocalDate day22 = LocalDate.of(2026, 10, 22);
        when(repository.getBookings(day22)).thenReturn(List.of(booking(1, 5, day22, "02:00")));
        when(repository.getBookings(day22.plusDays(1))).thenReturn(List.of());
        assertThat(s.dueBookings()).extracting(Booking::getId).containsExactly(1L);
    }
}
