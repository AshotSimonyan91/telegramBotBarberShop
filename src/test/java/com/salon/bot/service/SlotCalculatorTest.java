package com.salon.bot.service;

import com.salon.bot.model.Booking;
import com.salon.bot.model.BookingStatus;
import com.salon.bot.model.Master;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.EnumSet;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SlotCalculatorTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Yerevan");
    /** Четверг. */
    private static final LocalDate DATE = LocalDate.of(2026, 10, 22);

    private final Master anna = Master.builder()
            .name("Анна")
            .workDays(EnumSet.range(DayOfWeek.MONDAY, DayOfWeek.FRIDAY))
            .workStart(LocalTime.of(10, 0))
            .workEnd(LocalTime.of(19, 0))
            .active(true)
            .build();

    private static SlotCalculator calculatorAt(LocalDateTime now) {
        return new SlotCalculator(Clock.fixed(now.atZone(ZONE).toInstant(), ZONE));
    }

    private final SlotCalculator calculator = calculatorAt(LocalDateTime.of(2026, 10, 21, 9, 0));

    private static Booking booking(String master, String time, int duration, BookingStatus status) {
        return Booking.builder()
                .id(1)
                .masterName(master)
                .date(DATE)
                .time(LocalTime.parse(time))
                .durationMinutes(duration)
                .status(status)
                .build();
    }

    @Test
    void freeDayHasSlotsEvery30MinutesWithinWorkingHours() {
        List<LocalTime> slots = calculator.getFreeSlots(anna, DATE, 60, List.of());

        assertThat(slots).first().isEqualTo(LocalTime.of(10, 0));
        assertThat(slots).last().isEqualTo(LocalTime.of(18, 0));
        assertThat(slots).hasSize(17);
        assertThat(slots).allMatch(t -> t.getMinute() % 30 == 0);
    }

    @Test
    void serviceMustFinishBeforeEndOfWorkingDay() {
        assertThat(calculator.getFreeSlots(anna, DATE, 45, List.of())).last().isEqualTo(LocalTime.of(18, 0));
        assertThat(calculator.getFreeSlots(anna, DATE, 30, List.of())).last().isEqualTo(LocalTime.of(18, 30));
        assertThat(calculator.getFreeSlots(anna, DATE, 120, List.of())).last().isEqualTo(LocalTime.of(17, 0));
        assertThat(calculator.getFreeSlots(anna, DATE, 10 * 60, List.of())).isEmpty();
    }

    @Test
    void overlappingSlotsAreExcluded() {
        List<Booking> existing = List.of(booking("Анна", "12:00", 60, BookingStatus.CONFIRMED));

        List<LocalTime> slots = calculator.getFreeSlots(anna, DATE, 60, existing);

        assertThat(slots).contains(LocalTime.of(11, 0), LocalTime.of(13, 0));
        assertThat(slots).doesNotContain(LocalTime.of(11, 30), LocalTime.of(12, 0), LocalTime.of(12, 30));
    }

    @Test
    void durationOfExistingBookingAndOfNewServiceAreRespected() {
        List<Booking> shortBooking = List.of(booking("Анна", "12:00", 30, BookingStatus.CONFIRMED));
        List<Booking> longBooking = List.of(booking("Анна", "12:00", 120, BookingStatus.CONFIRMED));

        assertThat(calculator.getFreeSlots(anna, DATE, 30, shortBooking))
                .contains(LocalTime.of(11, 30), LocalTime.of(12, 30))
                .doesNotContain(LocalTime.of(12, 0));
        assertThat(calculator.getFreeSlots(anna, DATE, 30, longBooking))
                .contains(LocalTime.of(14, 0))
                .doesNotContain(LocalTime.of(12, 30), LocalTime.of(13, 30));
        // 90-минутная услуга в 11:00 заходит на запись в 12:00
        assertThat(calculator.getFreeSlots(anna, DATE, 90, shortBooking))
                .contains(LocalTime.of(10, 30))
                .doesNotContain(LocalTime.of(11, 0), LocalTime.of(11, 30));
    }

    @Test
    void cancelledBookingsDoNotOccupySlots() {
        List<Booking> existing = List.of(booking("Анна", "12:00", 60, BookingStatus.CANCELLED));

        assertThat(calculator.getFreeSlots(anna, DATE, 60, existing)).contains(LocalTime.of(12, 0)).hasSize(17);
    }

    @Test
    void bookingsOfOtherMastersAreIgnored() {
        List<Booking> existing = List.of(booking("Арам", "12:00", 60, BookingStatus.CONFIRMED));

        assertThat(calculator.getFreeSlots(anna, DATE, 60, existing)).contains(LocalTime.of(12, 0));
    }

    @Test
    void pastTimeIsHiddenForToday() {
        SlotCalculator atNoon = calculatorAt(DATE.atTime(13, 10));

        List<LocalTime> slots = atNoon.getFreeSlots(anna, DATE, 60, List.of());

        assertThat(slots).first().isEqualTo(LocalTime.of(13, 30));
        assertThat(slots).noneMatch(t -> t.isBefore(LocalTime.of(13, 10)));
    }

    @Test
    void slotStartingRightNowIsHidden() {
        assertThat(calculatorAt(DATE.atTime(13, 0)).getFreeSlots(anna, DATE, 60, List.of()))
                .first().isEqualTo(LocalTime.of(13, 30));
    }

    @Test
    void pastDatesAndDaysOffHaveNoSlots() {
        assertThat(calculator.getFreeSlots(anna, LocalDate.of(2026, 10, 20), 60, List.of())).isEmpty();
        assertThat(calculator.getFreeSlots(anna, LocalDate.of(2026, 10, 24), 60, List.of())).isEmpty();
    }
}
