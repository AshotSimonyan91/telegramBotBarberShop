package com.salon.bot.api;

import com.salon.bot.model.Booking;
import com.salon.bot.model.BookingStatus;
import com.salon.bot.service.ReminderScheduler;
import com.salon.bot.service.SlotTakenException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.message.Message;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Бизнес-логика записи на уровне интеграции: слоты, конфликты, мастера, конкурентность, время, напоминания. */
class BookingLogicTest extends ApiTestBase {

    @Autowired
    ReminderScheduler reminderScheduler;

    private long idOf(Resp r) {
        return r.json().get("id").asLong();
    }

    private <T> List<T> runParallel(int n, Callable<T> task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch ready = new CountDownLatch(n);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<T>> futures = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            futures.add(pool.submit(() -> {
                ready.countDown();
                go.await();
                return task.call();
            }));
        }
        assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
        go.countDown();
        List<T> results = new ArrayList<>();
        for (Future<T> f : futures) {
            results.add(f.get(120, TimeUnit.SECONDS));
        }
        pool.shutdown();
        return results;
    }

    private long count(List<Integer> statuses, int status) {
        return statuses.stream().filter(s -> s == status).count();
    }

    // ------------------------------------------------------------ двойное бронирование

    @Test
    void sequentialDoubleBookingSecondIs409() {
        Resp first = book("Борода", "Анна", THURSDAY, "12:00");
        Resp second = book("Борода", "Анна", THURSDAY, "12:00");
        assertThat(first.status()).isEqualTo(201);
        assertThat(second.status()).isEqualTo(409);
        assertThat(second.json().get("error").asText()).isEqualTo("slot_taken");
        assertThat(allBookings()).hasSize(1);
    }

    @Test
    void sameTimeDifferentMastersIsFine() {
        assertThat(book("Борода", "Анна", THURSDAY, "12:00").status()).isEqualTo(201);
        assertThat(book("Борода", "Арам", THURSDAY, "12:00").status()).isEqualTo(201);
    }

    @Test
    void parallelApiBookingsOfSameSlotExactlyOneWins() throws Exception {
        List<Integer> statuses = runParallel(16, () -> book("Борода", "Анна", THURSDAY, "12:00").status());
        assertThat(count(statuses, 201)).as("статусы: %s", statuses).isEqualTo(1);
        assertThat(count(statuses, 409)).isEqualTo(15);
        assertThat(allBookings()).hasSize(1);
    }

    @Test
    void parallelServiceBookingsOfSameSlotExactlyOneWins() throws Exception {
        Booking draft = Booking.builder().clientName("X").telegramId(1).username("u").phone("+37400000000")
                .serviceName("Борода").date(THURSDAY).time(LocalTime.of(12, 0)).source("t").durationMinutes(30).build();
        List<String> outcomes = runParallel(16, () -> {
            try {
                bookingService.book(draft, master("Анна"));
                return "ok";
            } catch (SlotTakenException e) {
                return "taken";
            }
        });
        assertThat(outcomes.stream().filter("ok"::equals).count()).isEqualTo(1);
        assertThat(outcomes.stream().filter("taken"::equals).count()).isEqualTo(15);
        assertThat(allBookings()).hasSize(1);
    }

    @Test
    void parallelMixOfApiAndServiceOnSameSlotExactlyOneWins() throws Exception {
        Booking draft = Booking.builder().clientName("X").telegramId(1).username("u").phone("+37400000000")
                .serviceName("Борода").date(THURSDAY).time(LocalTime.of(12, 0)).source("t").durationMinutes(30).build();
        AtomicInteger counter = new AtomicInteger();
        List<Boolean> wins = runParallel(16, () -> {
            if (counter.getAndIncrement() % 2 == 0) {
                return book("Борода", "Анна", THURSDAY, "12:00").status() == 201;
            }
            try {
                bookingService.book(draft, master("Анна"));
                return true;
            } catch (SlotTakenException e) {
                return false;
            }
        });
        assertThat(wins.stream().filter(w -> w).count()).isEqualTo(1);
        assertThat(allBookings()).hasSize(1);
    }

    @Test
    void parallelBookingsOfDifferentSlotsAreAllSavedWithUniqueIds() throws Exception {
        AtomicInteger idx = new AtomicInteger();
        List<Integer> statuses = runParallel(12, () -> {
            int i = idx.getAndIncrement();
            LocalTime t = LocalTime.of(11, 0).plusMinutes(30L * i);
            return book("Борода", "Арам", THURSDAY, t.toString()).status();
        });
        assertThat(count(statuses, 201)).as("статусы: %s", statuses).isEqualTo(12);
        List<Booking> all = allBookings();
        assertThat(all).hasSize(12);
        Set<Long> ids = new HashSet<>();
        all.forEach(b -> ids.add(b.getId()));
        assertThat(ids).hasSize(12);
    }

    @Test
    void parallelOverlappingBookingsNeverOverlapInStorage() throws Exception {
        // Окрашивание 120 мин и Борода 30 мин в пересекающиеся моменты у одного мастера
        AtomicInteger idx = new AtomicInteger();
        String[] times = {"12:00", "12:30", "13:00", "13:30", "12:00", "13:00", "12:30", "13:30"};
        runParallel(times.length, () -> {
            int i = idx.getAndIncrement();
            return i % 2 == 0
                    ? book("Окрашивание", "Анна", THURSDAY, times[i]).status()
                    : book("Борода", "Анна", THURSDAY, times[i]).status();
        });
        List<Booking> annas = allBookings();
        for (Booking a : annas) {
            for (Booking b : annas) {
                if (a.getId() < b.getId()) {
                    assertThat(a.start().isBefore(b.end()) && b.start().isBefore(a.end()))
                            .as("пересечение %s и %s", a, b).isFalse();
                }
            }
        }
        assertThat(annas).isNotEmpty();
    }

    // ------------------------------------------------------------ «любой мастер»

    @Test
    void anyMasterDistributesDifferentMastersThenRunsOut() {
        // среда: работают все трое
        Set<String> masters = new HashSet<>();
        for (int i = 0; i < 3; i++) {
            Resp r = book("Борода", null, TODAY, "15:00");
            assertThat(r.status()).as("запись %d: %s", i, r.body()).isEqualTo(201);
            masters.add(r.json().get("masterName").asText());
        }
        assertThat(masters).containsExactlyInAnyOrder("Анна", "Арам", "Мариам");
        Resp fourth = book("Борода", null, TODAY, "15:00");
        assertThat(fourth.status()).isEqualTo(409);
        assertThat(fourth.json().get("error").asText()).isEqualTo("slot_taken");
        assertThat(allBookings()).hasSize(3);
        // соседнее время ещё свободно
        assertThat(book("Борода", null, TODAY, "15:30").status()).isEqualTo(201);
    }

    @Test
    void anyMasterSkipsMasterBusyWithLongService() {
        Resp long1 = book("Окрашивание", "Анна", THURSDAY, "11:00");   // Анна 11:00-13:00
        assertThat(long1.status()).isEqualTo(201);
        Resp any = book("Борода", null, THURSDAY, "12:00");           // Анна занята -> Арам
        assertThat(any.status()).isEqualTo(201);
        assertThat(any.json().get("masterName").asText()).isEqualTo("Арам");
        assertThat(book("Борода", null, THURSDAY, "12:30").status()).isEqualTo(201);   // Арам
        assertThat(book("Борода", null, THURSDAY, "12:30").status()).as("в четверг Мариам выходная").isEqualTo(409);
        assertThat(book("Борода", null, THURSDAY, "12:00").status()).as("заняты оба").isEqualTo(409);
    }

    @Test
    void anyMasterOnDayNobodyWorksIs409() {
        Resp r = book("Борода", null, SUNDAY, "12:00");
        assertThat(r.status()).isEqualTo(409);
        assertThat(r.json().get("error").asText()).isEqualTo("slot_taken");
    }

    /**
     * Три мастера свободны на 15:00, 12 одновременных запросов «любой мастер» — должны получить ровно 3 записи.
     * Если firstFreeMaster и book не атомарны, часть запросов получит 409 при наличии свободных мастеров.
     */
    @Test
    void parallelAnyMasterFillsAllMastersExactly() throws Exception {
        for (int attempt = 0; attempt < 3; attempt++) {
            resetBookings();
            List<Integer> statuses = runParallel(12, () -> book("Борода", null, TODAY, "15:00").status());
            assertThat(count(statuses, 201)).as("попытка %d, статусы: %s", attempt, statuses).isEqualTo(3);
            assertThat(count(statuses, 409)).isEqualTo(9);
            assertThat(allBookings().stream().map(Booking::getMasterName).distinct()).hasSize(3);
        }
    }

    private void resetBookings() throws java.io.IOException {
        java.nio.file.Files.deleteIfExists(excelFile());
        repository.init();
    }

    // ------------------------------------------------------------ границы слотов

    @Test
    void lastSlotBeforeShiftEnd() {
        // Анна до 19:00
        assertThat(book("Борода", "Анна", THURSDAY, "18:30").status()).isEqualTo(201);       // 18:30-19:00
        assertThat(book("Стрижка женская", "Анна", THURSDAY, "18:30").status()).as("60 мин не влезает").isEqualTo(409);
        assertThat(book("Стрижка женская", "Анна", THURSDAY, "18:00").status()).isEqualTo(409); // занято 18:30
        assertThat(book("Борода", "Анна", THURSDAY, "19:00").status()).as("после конца смены").isEqualTo(409);
        assertThat(book("Окрашивание", "Анна", THURSDAY, "17:30").status()).as("120 мин до 19:30").isEqualTo(409);
        assertThat(book("Окрашивание", "Арам", THURSDAY, "18:00").status()).as("Арам до 20:00 -> 18:00..20:00")
                .isEqualTo(201);
        assertThat(book("Стрижка мужская", "Арам", THURSDAY, "19:30").status()).as("45 мин от 19:30 > 20:00")
                .isEqualTo(409);
    }

    @Test
    void firstSlotOfShiftAndBeforeShiftStart() {
        assertThat(book("Борода", "Анна", THURSDAY, "09:30").status()).isEqualTo(409);
        assertThat(book("Борода", "Анна", THURSDAY, "10:00").status()).isEqualTo(201);
    }

    @Test
    void serviceLongerThanWholeShiftNeverFits() {
        // Окрашивание 120, смена Анны 9 часов — влезает; проверяем, что слоты заканчиваются ровно в 17:00
        Resp slots = get("/api/v1/slots?serviceName=Окрашивание&date=2026-10-22&masterName=Анна");
        assertThat(slots.json().get("slots").get(slots.json().get("slots").size() - 1).asText()).isEqualTo("17:00");
    }

    @Test
    void adjacentBookingsDoNotConflictButOverlapsDo() {
        assertThat(book("Окрашивание", "Анна", THURSDAY, "10:30").status()).isEqualTo(201);   // 10:30-12:30
        assertThat(book("Борода", "Анна", THURSDAY, "10:00").status()).as("впритык до").isEqualTo(201);
        assertThat(book("Борода", "Анна", THURSDAY, "12:30").status()).as("впритык после").isEqualTo(201);
        assertThat(book("Борода", "Анна", THURSDAY, "11:30").status()).as("внутри").isEqualTo(409);
        assertThat(book("Борода", "Анна", THURSDAY, "12:00").status()).as("хвост").isEqualTo(409);
        assertThat(book("Стрижка мужская", "Анна", THURSDAY, "13:00").status()).as("45 мин после 13:00").isEqualTo(201);
        // 45 минут 13:00-13:45: старт 13:30 пересекается, 14:00 — нет
        assertThat(book("Борода", "Анна", THURSDAY, "13:30").status()).isEqualTo(409);
        assertThat(book("Борода", "Анна", THURSDAY, "14:00").status()).isEqualTo(201);
    }

    @Test
    void newLongBookingCannotCoverExistingShortOne() {
        assertThat(book("Борода", "Анна", THURSDAY, "11:30").status()).isEqualTo(201);        // 11:30-12:00
        assertThat(book("Окрашивание", "Анна", THURSDAY, "10:30").status()).as("10:30-12:30 накрывает 11:30")
                .isEqualTo(409);
        assertThat(book("Окрашивание", "Анна", THURSDAY, "10:00").status()).as("10:00-12:00 накрывает хвостом")
                .isEqualTo(409);
        assertThat(book("Окрашивание", "Анна", THURSDAY, "12:00").status()).isEqualTo(201);
    }

    @Test
    void slotsEndpointReflectsBookings() {
        book("Окрашивание", "Анна", THURSDAY, "11:00");   // 11:00-13:00
        List<String> slots = new ArrayList<>();
        get("/api/v1/slots?serviceName=Борода&date=2026-10-22&masterName=Анна").json().get("slots")
                .forEach(n -> slots.add(n.asText()));
        assertThat(slots).contains("10:00", "10:30", "13:00").doesNotContain("11:00", "11:30", "12:00", "12:30");
        // 60 минут: 10:00 (10:00-11:00) ещё можно, 10:30 уже нет
        slots.clear();
        get("/api/v1/slots?serviceName=Стрижка женская&date=2026-10-22&masterName=Анна").json().get("slots")
                .forEach(n -> slots.add(n.asText()));
        assertThat(slots).contains("10:00", "13:00").doesNotContain("10:30", "11:00", "12:30");
    }

    @Test
    void cancelledBookingFreesSlot() {
        long id = idOf(book("Борода", "Анна", THURSDAY, "12:00"));
        assertThat(book("Борода", "Анна", THURSDAY, "12:00").status()).isEqualTo(409);
        assertThat(post("/api/v1/bookings/" + id + "/cancel", null).status()).isEqualTo(200);
        Resp slots = get("/api/v1/slots?serviceName=Борода&date=2026-10-22&masterName=Анна");
        assertThat(slots.body()).contains("\"12:00\"");
        Resp again = book("Борода", "Анна", THURSDAY, "12:00");
        assertThat(again.status()).isEqualTo(201);
        assertThat(idOf(again)).isNotEqualTo(id);
        assertThat(allBookings()).hasSize(2);
    }

    @Test
    void parallelCancelOfSameBookingSucceedsOnceAndNotifiesOnce() throws Exception {
        Message sent = mock(Message.class);
        when(sent.getMessageId()).thenReturn(1);
        when(telegramClient.execute(any(SendMessage.class))).thenReturn(sent);
        Booking b = seed("Клиент", "+37491000001", "Борода", "Анна", THURSDAY, "12:00", 777, "cl");
        List<Integer> statuses = runParallel(8, () -> post("/api/v1/bookings/" + b.getId() + "/cancel", null).status());
        assertThat(count(statuses, 200)).as("статусы: %s", statuses).isEqualTo(1);
        assertThat(count(statuses, 409)).isEqualTo(7);
        org.mockito.Mockito.verify(telegramClient, org.mockito.Mockito.times(1)).execute(any(SendMessage.class));
    }

    // ------------------------------------------------------------ видимость между API и ботом

    @Test
    void apiBookingVisibleToBotLogic() {
        Resp r = book("Борода", "Анна", THURSDAY, "12:00");
        long id = idOf(r);
        assertThat(repository.getAllBookings()).extracting(Booking::getId).contains(id);
        assertThat(bookingService.findBooking(id)).isPresent();
        assertThat(bookingService.findBooking(id).get().getSource()).isEqualTo(com.salon.bot.service.BookingService.ADMIN_SOURCE);
        assertThat(bookingService.activeBookingsOn(THURSDAY)).extracting(Booking::getId).containsExactly(id);
        assertThat(bookingService.freeSlots(master("Анна"), THURSDAY, 30)).doesNotContain(LocalTime.of(12, 0));
        assertThat(repository.getBookings("Анна", THURSDAY)).hasSize(1);
        assertThat(bookingService.upcomingBookings(0)).as("админская запись без Telegram ID").hasSize(1);
    }

    @Test
    void botBookingVisibleToApi() {
        Booking b = seed("Бот Клиент", "+37491000009", "Борода", "Анна", THURSDAY, "12:00", 321, "botuser");
        Resp r = get("/api/v1/bookings/" + b.getId());
        assertThat(r.status()).isEqualTo(200);
        assertThat(r.json().get("telegramId").asLong()).isEqualTo(321);
        assertThat(r.json().get("username").asText()).isEqualTo("botuser");
        assertThat(r.json().get("source").asText()).isEqualTo("start_test");
        assertThat(book("Борода", "Анна", THURSDAY, "12:00").status()).isEqualTo(409);
        assertThat(get("/api/v1/slots?serviceName=Борода&date=2026-10-22&masterName=Анна").body())
                .doesNotContain("\"12:00\"");
    }

    // ------------------------------------------------------------ напоминания

    @Test
    void remindersAreNotSentForAdminBookings() throws Exception {
        Message sent = mock(Message.class);
        when(sent.getMessageId()).thenReturn(5);
        when(telegramClient.execute(any(SendMessage.class))).thenReturn(sent);

        Booking tg = seed("Клиент", "+37491000001", "Борода", "Анна", TODAY, "10:00", 777, "cl");
        long adminId = idOf(book("Борода", "Анна", TODAY, "10:30"));
        long edge = seed("Граница", "+37491000002", "Борода", "Анна", TODAY, "11:00", 888, "e").getId();   // ровно +2 ч
        long far = seed("Далеко", "+37491000003", "Борода", "Анна", TODAY, "11:30", 999, "f").getId();     // +2.5 ч

        reminderScheduler.sendReminders();

        org.mockito.ArgumentCaptor<SendMessage> captor = org.mockito.ArgumentCaptor.forClass(SendMessage.class);
        org.mockito.Mockito.verify(telegramClient, atLeast(1)).execute(captor.capture());
        List<String> chats = captor.getAllValues().stream().map(SendMessage::getChatId).distinct().toList();
        assertThat(chats).containsExactlyInAnyOrder("777", "888");
        assertThat(repository.getAllBookings()).filteredOn(b -> b.getId() == tg.getId())
                .singleElement().satisfies(b -> assertThat(b.isReminderSent()).isTrue());
        assertThat(get("/api/v1/bookings/" + adminId).json().get("reminderSent").asBoolean()).isFalse();
        assertThat(get("/api/v1/bookings/" + far).json().get("reminderSent").asBoolean()).isFalse();
        assertThat(get("/api/v1/bookings/" + edge).json().get("reminderSent").asBoolean()).isTrue();

        // повторный запуск не шлёт то же напоминание ещё раз
        reminderScheduler.sendReminders();
        org.mockito.Mockito.verify(telegramClient, org.mockito.Mockito.times(2)).execute(any(SendMessage.class));
    }

    // ------------------------------------------------------------ часовой пояс и границы суток

    @Test
    void todayFollowsBotTimezoneNotUtc() {
        // 20:30 UTC 21.10 == 00:30 22.10 в Asia/Yerevan (UTC+4)
        clock.set(Instant.parse("2026-10-21T20:30:00Z"));
        Resp health = call("GET", "/api/v1/health", null, null);
        assertThat(health.json().get("now").asText()).isEqualTo("2026-10-22T00:30");

        Resp yesterday = book("Борода", "Мариам", TODAY, "12:00");
        assertThat(yesterday.status()).as("21.10 для бота уже вчера").isEqualTo(400);
        assertThat(yesterday.json().get("fields").has("date")).isTrue();

        Resp slotsPast = get("/api/v1/slots?serviceName=Борода&date=2026-10-21&masterName=Мариам");
        assertThat(slotsPast.json().get("slots")).isEmpty();

        Resp ok = book("Борода", "Анна", THURSDAY, "10:00");
        assertThat(ok.status()).isEqualTo(201);
        assertThat(ok.json().get("createdAt").asText()).isEqualTo("2026-10-22T00:30");

        assertThat(get("/api/v1/dates?days=1").json().get(0).asText()).isEqualTo("2026-10-22");
    }

    @Test
    void justBeforeMidnightStillToday() {
        clock.set(Instant.parse("2026-10-21T19:59:59Z"));   // 23:59:59 21.10 в Yerevan
        assertThat(get("/api/v1/dates?days=1").json().get(0).asText()).isEqualTo("2026-10-21");
        assertThat(book("Борода", "Мариам", TODAY, "12:00").status()).as("время сегодня уже прошло").isEqualTo(400);
        Resp tomorrow = book("Борода", "Анна", THURSDAY, "10:00");
        assertThat(tomorrow.status()).isEqualTo(201);
        assertThat(tomorrow.json().get("createdAt").asText()).isEqualTo("2026-10-21T23:59");
    }

    @Test
    void exactlyMidnightNewDayStarts() {
        clock.set(LocalDateTime.of(2026, 10, 22, 0, 0));
        assertThat(book("Борода", "Анна", TODAY, "12:00").status()).isEqualTo(400);
        assertThat(get("/api/v1/slots?serviceName=Борода&date=2026-10-22&masterName=Анна").json().get("slots").get(0)
                .asText()).isEqualTo("10:00");
        assertThat(book("Борода", "Анна", THURSDAY, "10:00").status()).isEqualTo(201);
    }

    @Test
    void bookingStartingExactlyNowIsRejectedAndNextSlotAccepted() {
        clock.set(LocalDateTime.of(2026, 10, 21, 12, 0));
        assertThat(book("Борода", "Анна", TODAY, "12:00").status()).isEqualTo(400);
        assertThat(book("Борода", "Анна", TODAY, "12:30").status()).isEqualTo(201);
        clock.set(LocalDateTime.of(2026, 10, 21, 12, 29, 59));
        assertThat(book("Борода", "Арам", TODAY, "12:30").status()).isEqualTo(201);
    }

    // ------------------------------------------------------------ рабочий день vs выходной

    @Test
    void workingVsDayOffForEachMaster() {
        // Анна: Пн-Пт; Арам: Вт-Сб; Мариам: Пн,Ср,Пт,Сб
        LocalDate monday = TODAY.plusDays(5);
        assertThat(book("Борода", "Арам", monday, "12:00").status()).as("Арам в пн выходной").isEqualTo(400);
        assertThat(book("Борода", "Анна", monday, "12:00").status()).isEqualTo(201);
        assertThat(book("Борода", "Мариам", monday, "12:00").status()).isEqualTo(201);
        assertThat(book("Борода", "Мариам", THURSDAY, "12:00").status()).as("Мариам в чт выходной").isEqualTo(400);
        assertThat(book("Борода", "Мариам", SATURDAY, "12:00").status()).isEqualTo(201);
        assertThat(book("Борода", "Анна", SATURDAY, "12:00").status()).isEqualTo(400);
        assertThat(allBookings()).hasSize(3);
    }

    @Test
    void createdBookingIsConfirmedWithExpectedStorageFields() {
        Resp r = book("Борода", "Анна", THURSDAY, "12:00");
        Booking stored = allBookings().get(0);
        assertThat(stored.getStatus()).isEqualTo(BookingStatus.CONFIRMED);
        assertThat(stored.getTelegramId()).isZero();
        assertThat(stored.getPhone()).isEqualTo("+37491123456");
        assertThat(stored.getCreatedAt()).isEqualTo(NOW);
        assertThat(stored.getId()).isEqualTo(idOf(r));
        assertThat(stored.isReminderSent()).isFalse();
    }
}
