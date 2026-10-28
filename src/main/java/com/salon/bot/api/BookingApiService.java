package com.salon.bot.api;

import com.salon.bot.api.dto.ApiFormats;
import com.salon.bot.api.dto.NewBookingRequest;
import com.salon.bot.bot.Messenger;
import com.salon.bot.handler.Texts;
import com.salon.bot.model.Booking;
import com.salon.bot.model.BookingStatus;
import com.salon.bot.model.Master;
import com.salon.bot.model.Service;
import com.salon.bot.service.BookingQuery;
import com.salon.bot.service.BookingService;
import com.salon.bot.storage.BookingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Сценарии REST API поверх {@link BookingService}: разбор и проверка параметров, создание и отмена записей.
 * Ошибки запроса — {@link ApiException}; ошибки хранилища ({@code StorageException}) пробрасываются как есть.
 */
@Slf4j
@ConditionalOnApi
@Component
@RequiredArgsConstructor
public class BookingApiService {

    static final int MAX_DAYS = 60;
    static final int DEFAULT_DAYS = 14;
    private static final Pattern PHONE_CHARS = Pattern.compile("\\+?[0-9\\s()\\-]+");
    private static final Pattern PHONE_SEPARATORS = Pattern.compile("[\\s()\\-]");

    private final BookingService bookingService;
    private final BookingRepository repository;
    private final Messenger messenger;
    private final Clock clock;

    /** Результат отмены: обновлённая запись и попытка уведомить клиента. */
    public record CancelResult(Booking booking, boolean notifyAttempted, boolean notified) {
    }

    // ---------- Записи ----------

    public List<Booking> findBookings(String date, String from, String to, String status, String q,
                                      String masterName) {
        Map<String, String> errors = new LinkedHashMap<>();
        LocalDate exact = parseDate("date", date, errors, false);
        LocalDate fromDate = parseDate("from", from, errors, false);
        LocalDate toDate = parseDate("to", to, errors, false);
        if (exact != null && (fromDate != null || toDate != null)) {
            errors.put("date", "Укажите либо date, либо период from/to");
        }
        if (fromDate != null && toDate != null && fromDate.isAfter(toDate)) {
            errors.put("from", "Начало периода позже конца");
        }
        BookingStatus bookingStatus = null;
        if (!isBlank(status)) {
            try {
                bookingStatus = BookingStatus.valueOf(status.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                errors.put("status", "Допустимые значения: CONFIRMED, CANCELLED");
            }
        }
        throwIfAny(errors);
        return bookingService.findBookings(new BookingQuery(exact, fromDate, toDate, bookingStatus, q, masterName));
    }

    public Booking getBooking(long id) {
        return bookingService.findBooking(id).orElseThrow(BookingApiService::bookingNotFound);
    }

    /**
     * Создаёт запись от имени администратора — так же, как приложение (NewBookingDialog):
     * источник «администратор», без Telegram ID и username.
     *
     * @throws ApiException validation — неверные поля; slot_taken — время занято или нет свободного мастера
     */
    public Booking create(NewBookingRequest request) {
        if (request == null) {
            throw ApiException.validation("body", "Пустой запрос");
        }
        Map<String, String> errors = new LinkedHashMap<>();
        LocalDateTime now = LocalDateTime.now(clock);

        String clientName = trim(request.clientName());
        if (clientName.length() < 2 || clientName.length() > 100) {
            errors.put("clientName", "Имя клиента: от 2 до 100 символов");
        }

        String phoneRaw = trim(request.phone());
        String phone = PHONE_SEPARATORS.matcher(phoneRaw).replaceAll("");
        if (phoneRaw.length() < 5 || phoneRaw.length() > 20 || !PHONE_CHARS.matcher(phoneRaw).matches()
                || phone.chars().filter(Character::isDigit).count() < 5) {
            errors.put("phone", "Телефон: от 5 до 20 символов — цифры, пробелы, дефисы и скобки, «+» только в начале");
        }

        Service service = null;
        if (isBlank(request.serviceName())) {
            errors.put("serviceName", "Выберите услугу");
        } else {
            service = bookingService.findService(request.serviceName().trim()).orElse(null);
            if (service == null) {
                errors.put("serviceName", "Такой услуги нет");
            }
        }

        Master master = null;
        if (!isBlank(request.masterName())) {
            master = bookingService.findActiveMaster(request.masterName().trim()).orElse(null);
            if (master == null) {
                errors.put("masterName", "Такого мастера нет или он сейчас не принимает");
            }
        }

        LocalDate date = parseDate("date", request.date(), errors, true);
        if (date != null && date.isBefore(now.toLocalDate())) {
            errors.put("date", "Дата уже прошла");
            date = null;
        }
        LocalTime time = parseTime("time", request.time(), errors);
        if (date != null && time != null && !date.atTime(time).isAfter(now)) {
            errors.put("time", "Это время уже прошло");
        }
        if (master != null && date != null && !errors.containsKey("date") && !master.worksOn(date)) {
            errors.put("date", "Мастер не работает в этот день");
        }
        throwIfAny(errors);

        Booking draft = Booking.builder()
                .clientName(clientName)
                .telegramId(0)
                .username("")
                .phone(phone)
                .serviceName(service.getName())
                .date(date)
                .time(time)
                .source(BookingService.ADMIN_SOURCE)
                .durationMinutes(service.getDurationMinutes())
                .build();

        // «Любой мастер»: выбор свободного мастера и запись — атомарно, под одной блокировкой.
        Booking booking = master != null
                ? bookingService.book(draft, master)
                : bookingService.bookWithAnyMaster(draft, bookingService.activeMasters());
        log.info("API: создана запись №{} ({} {} {}, мастер {})", booking.getId(), booking.getServiceName(),
                booking.getDate(), booking.getTime(), booking.getMasterName());
        return booking;
    }

    /**
     * Отменяет запись салоном. Клиенту из Telegram отправляется уведомление, как в приложении (BookingsPanel);
     * сбой уведомления отмену не отменяет.
     *
     * @throws ApiException not_found — нет записи; already_cancelled — запись уже отменена
     */
    public CancelResult cancel(long id) {
        Booking current = getBooking(id);
        if (!current.isActive()) {
            throw ApiException.alreadyCancelled();
        }
        Booking cancelled = repository.cancelBooking(id).orElseThrow(ApiException::alreadyCancelled);
        log.info("API: запись №{} отменена салоном", id);

        boolean notifyAttempted = cancelled.getTelegramId() > 0;
        boolean notified = false;
        if (notifyAttempted) {
            try {
                notified = messenger.send(cancelled.getTelegramId(), Texts.salonCancellationNotice(cancelled)) != null;
            } catch (RuntimeException e) {
                log.warn("API: не удалось уведомить клиента об отмене записи №{}", id, e);
            }
            if (!notified) {
                log.warn("API: уведомление об отмене записи №{} клиенту не доставлено", id);
            }
        }
        return new CancelResult(cancelled, notifyAttempted, notified);
    }

    // ---------- Справочники и слоты ----------

    public Service requireService(String serviceName) {
        if (isBlank(serviceName)) {
            throw ApiException.validation("serviceName", "Обязательный параметр");
        }
        return bookingService.findService(serviceName.trim())
                .orElseThrow(() -> ApiException.notFound("Услуга не найдена"));
    }

    /** Указанный активный мастер или все активные, если имя не задано. */
    public List<Master> mastersFor(String masterName) {
        if (isBlank(masterName)) {
            return bookingService.activeMasters();
        }
        return List.of(bookingService.findActiveMaster(masterName.trim())
                .orElseThrow(() -> ApiException.notFound("Мастер не найден")));
    }

    public LocalDate requireDate(String name, String value) {
        Map<String, String> errors = new LinkedHashMap<>();
        LocalDate date = parseDate(name, value, errors, true);
        throwIfAny(errors);
        return date;
    }

    public int parseDays(String days) {
        if (isBlank(days)) {
            return DEFAULT_DAYS;
        }
        try {
            int value = Integer.parseInt(days.trim());
            if (value >= 1 && value <= MAX_DAYS) {
                return value;
            }
        } catch (NumberFormatException ignored) {
            // ниже — общая ошибка валидации
        }
        throw ApiException.validation("days", "Число дней: от 1 до " + MAX_DAYS);
    }

    // ---------- Разбор параметров ----------

    private static LocalDate parseDate(String name, String value, Map<String, String> errors, boolean required) {
        if (isBlank(value)) {
            if (required) {
                errors.put(name, "Укажите дату в формате ГГГГ-ММ-ДД");
            }
            return null;
        }
        try {
            return LocalDate.parse(value.trim(), ApiFormats.DATE_FORMAT);
        } catch (DateTimeParseException e) {
            errors.put(name, "Дата в формате ГГГГ-ММ-ДД, например 2026-10-30");
            return null;
        }
    }

    private static LocalTime parseTime(String name, String value, Map<String, String> errors) {
        if (isBlank(value)) {
            errors.put(name, "Укажите время в формате ЧЧ:ММ");
            return null;
        }
        try {
            return LocalTime.parse(value.trim(), ApiFormats.TIME_FORMAT);
        } catch (DateTimeParseException e) {
            errors.put(name, "Время в формате ЧЧ:ММ, например 14:30");
            return null;
        }
    }

    private static void throwIfAny(Map<String, String> errors) {
        if (!errors.isEmpty()) {
            throw ApiException.validation(errors);
        }
    }

    private static ApiException bookingNotFound() {
        return ApiException.notFound("Запись не найдена");
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }
}
