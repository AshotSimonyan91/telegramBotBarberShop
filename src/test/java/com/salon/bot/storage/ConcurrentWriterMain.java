package com.salon.bot.storage;

import com.salon.bot.model.Booking;
import com.salon.bot.model.BookingStatus;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

/**
 * Второй процесс для {@link ExcelBookingRepositoryTest}: добавляет записи в тот же файл.
 * Аргументы: путь к xlsx, telegramId, количество записей.
 */
public class ConcurrentWriterMain {

    public static void main(String[] args) {
        ExcelBookingRepository repository =
                new ExcelBookingRepository(Path.of(args[0]), Clock.systemDefaultZone(), 3, Duration.ofMillis(50));
        long telegramId = Long.parseLong(args[1]);
        int count = Integer.parseInt(args[2]);
        for (int i = 0; i < count; i++) {
            repository.addBooking(Booking.builder()
                    .createdAt(LocalDateTime.of(2026, 10, 21, 9, 0))
                    .clientName("Процесс " + telegramId)
                    .telegramId(telegramId)
                    .username("")
                    .phone("+37491000000")
                    .serviceName("Стрижка мужская")
                    .masterName("Арам")
                    .date(LocalDate.of(2026, 10, 22))
                    .time(LocalTime.of(11, 0))
                    .source("test")
                    .status(BookingStatus.CONFIRMED)
                    .build());
        }
    }
}
