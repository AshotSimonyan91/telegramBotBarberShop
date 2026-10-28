package com.salon.bot.service;

import com.salon.bot.model.Booking;
import com.salon.bot.model.BookingStatus;
import com.salon.bot.model.Master;
import com.salon.bot.model.Service;
import com.salon.bot.storage.BookingRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Stream;

/**
 * Бизнес-операции над записями поверх {@link BookingRepository}.
 */
@Component
@RequiredArgsConstructor
public class BookingService {

    /** Источник записей, созданных администратором (приложение, REST API), — колонка «Источник». */
    public static final String ADMIN_SOURCE = "администратор";

    private final BookingRepository repository;
    private final SlotCalculator slotCalculator;
    private final Clock clock;

    /** Проверка слота и сохранение должны идти без вмешательства других потоков. */
    private final ReentrantLock bookingLock = new ReentrantLock();

    public List<Service> services() {
        return repository.getServices();
    }

    public List<Master> activeMasters() {
        return repository.getMasters().stream().filter(Master::isActive).toList();
    }

    public Optional<Master> findActiveMaster(String name) {
        return activeMasters().stream().filter(m -> m.getName().equalsIgnoreCase(name)).findFirst();
    }

    public Optional<Service> findService(String name) {
        return services().stream().filter(s -> s.getName().equalsIgnoreCase(name)).findFirst();
    }

    /** Ближайшие {@code days} дней, в которые работает хотя бы один из мастеров. */
    public List<LocalDate> availableDates(List<Master> masters, int days) {
        LocalDate today = LocalDate.now(clock);
        return today.datesUntil(today.plusDays(days))
                .filter(d -> masters.stream().anyMatch(m -> m.worksOn(d)))
                .toList();
    }

    public List<LocalTime> freeSlots(Master master, LocalDate date, int duration) {
        return slotCalculator.getFreeSlots(master, date, duration, repository.getBookings(master.getName(), date));
    }

    /** Объединение свободных слотов нескольких мастеров. */
    public List<LocalTime> freeSlots(List<Master> masters, LocalDate date, int duration) {
        Set<LocalTime> slots = new LinkedHashSet<>();
        for (Master master : masters) {
            slots.addAll(freeSlots(master, date, duration));
        }
        return slots.stream().sorted().toList();
    }

    /** Первый мастер из списка, свободный в указанное время. */
    public Optional<Master> firstFreeMaster(List<Master> masters, LocalDate date, LocalTime time, int duration) {
        return masters.stream()
                .filter(m -> freeSlots(m, date, duration).contains(time))
                .findFirst();
    }

    /**
     * Повторно проверяет, что слот свободен, и сохраняет запись со статусом «Подтверждена».
     *
     * @throws SlotTakenException если время уже занято
     */
    public Booking book(Booking draft, Master master) {
        bookingLock.lock();
        try {
            List<Booking> existing = repository.getBookings(master.getName(), draft.getDate());
            if (!slotCalculator.isFree(master, draft.getDate(), draft.getTime(), draft.getDurationMinutes(), existing)) {
                throw new SlotTakenException();
            }
            Booking booking = draft.toBuilder()
                    .masterName(master.getName())
                    .createdAt(LocalDateTime.now(clock).withSecond(0).withNano(0))
                    .status(BookingStatus.CONFIRMED)
                    .reminderSent(false)
                    .build();
            long id = repository.addBooking(booking);
            return booking.withId(id);
        } finally {
            bookingLock.unlock();
        }
    }

    /** Будущие активные записи клиента. */
    public List<Booking> upcomingBookings(long telegramId) {
        LocalDateTime now = LocalDateTime.now(clock);
        return repository.getUserBookings(telegramId).stream()
                .filter(Booking::isActive)
                .filter(b -> b.start().isAfter(now))
                .sorted(Comparator.comparing(Booking::start))
                .toList();
    }

    /** Отменяет запись, только если она принадлежит клиенту. */
    public Optional<Booking> cancel(long bookingId, long telegramId) {
        boolean owned = repository.getUserBookings(telegramId).stream().anyMatch(b -> b.getId() == bookingId);
        return owned ? repository.cancelBooking(bookingId) : Optional.empty();
    }

    /** Запись по ID (включая отменённые). */
    public Optional<Booking> findBooking(long bookingId) {
        return repository.getAllBookings().stream().filter(b -> b.getId() == bookingId).findFirst();
    }

    /**
     * Записи по фильтру, отсортированные по дате и времени визита (затем по ID).
     * Для точной даты читаются только записи этого дня.
     */
    public List<Booking> findBookings(BookingQuery query) {
        List<Booking> source = query.date() != null
                ? repository.getBookings(query.date())
                : repository.getAllBookings();
        String text = query.text() == null || query.text().isBlank()
                ? null : query.text().trim().toLowerCase(Locale.ROOT);
        String master = query.masterName() == null || query.masterName().isBlank()
                ? null : query.masterName().trim();
        return source.stream()
                .filter(b -> query.date() == null || b.getDate().equals(query.date()))
                .filter(b -> query.from() == null || !b.getDate().isBefore(query.from()))
                .filter(b -> query.to() == null || !b.getDate().isAfter(query.to()))
                .filter(b -> query.status() == null || b.getStatus() == query.status())
                .filter(b -> master == null || master.equalsIgnoreCase(b.getMasterName()))
                .filter(b -> text == null || matches(b, text))
                .sorted(Comparator.comparing(Booking::getDate)
                        .thenComparing(Booking::getTime)
                        .thenComparingLong(Booking::getId))
                .toList();
    }

    private static boolean matches(Booking b, String lowerText) {
        return Stream.of(b.getClientName(), b.getPhone(), b.getServiceName(), b.getUsername())
                .anyMatch(v -> v != null && v.toLowerCase(Locale.ROOT).contains(lowerText));
    }

    public List<Booking> activeBookingsOn(LocalDate date) {
        return repository.getBookings(date).stream()
                .filter(Booking::isActive)
                .sorted(Comparator.comparing(Booking::getTime))
                .toList();
    }
}
