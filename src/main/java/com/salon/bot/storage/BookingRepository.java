package com.salon.bot.storage;

import com.salon.bot.model.Booking;
import com.salon.bot.model.Master;
import com.salon.bot.model.Service;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Хранилище записей, услуг и мастеров.
 * Остальной код работает только через этот интерфейс (сейчас — Excel, в будущем — Google Sheets).
 * Все методы могут бросить {@link StorageException}.
 */
public interface BookingRepository {

    /** Создаёт хранилище с примерами данных, если его ещё нет. */
    void init();

    List<Service> getServices();

    List<Master> getMasters();

    /** Записи мастера на дату (включая отменённые). */
    List<Booking> getBookings(String masterName, LocalDate date);

    /** Все записи на дату (включая отменённые). */
    List<Booking> getBookings(LocalDate date);

    /** Все записи (включая отменённые), по времени визита. */
    List<Booking> getAllBookings();

    /** Сохраняет запись и возвращает присвоенный ID. */
    long addBooking(Booking booking);

    List<Booking> getUserBookings(long telegramId);

    /** Ставит статус «Отменена». Возвращает запись, если она была активна. */
    Optional<Booking> cancelBooking(long bookingId);

    void markReminderSent(long bookingId);

    /** Актуальная копия хранилища в виде файла. */
    ExportedFile export();
}
