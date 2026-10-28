package com.salon.bot.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.salon.bot.model.Booking;
import com.salon.bot.model.BookingStatus;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.message.Message;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** GET/POST /bookings, GET /bookings/{id}, POST /bookings/{id}/cancel. */
class ApiBookingsTest extends ApiTestBase {

    private static final String LONG_PHONE_20 = "+3749112345678901234"; // 20 символов

    private List<Long> ids(Resp r) {
        List<Long> result = new ArrayList<>();
        r.json().forEach(n -> result.add(n.get("id").asLong()));
        return result;
    }

    // ------------------------------------------------------------ создание: успех

    @Test
    void createReturns201LocationAndNormalizedPhone() {
        Resp r = post("/api/v1/bookings", bookingJson("  Иван Петров  ", "+374 (91) 12-34-56",
                "стрижка МУЖСКАЯ", "анна", THURSDAY, "10:30"));
        assertThat(r.status()).as(r.body()).isEqualTo(201);
        JsonNode b = r.json();
        assertThat(b.get("phone").asText()).isEqualTo("+37491123456");
        assertThat(b.get("clientName").asText()).isEqualTo("Иван Петров");
        assertThat(b.get("serviceName").asText()).isEqualTo("Стрижка мужская");
        assertThat(b.get("masterName").asText()).isEqualTo("Анна");
        assertThat(b.get("date").asText()).isEqualTo("2026-10-22");
        assertThat(b.get("time").asText()).isEqualTo("10:30");
        assertThat(b.get("durationMinutes").asInt()).isEqualTo(45);
        assertThat(b.get("source").asText()).isEqualTo("администратор");
        assertThat(b.get("telegramId").asLong()).isZero();
        assertThat(b.get("username").asText()).isEmpty();
        assertThat(b.get("status").asText()).isEqualTo("CONFIRMED");
        assertThat(b.get("reminderSent").asBoolean()).isFalse();
        assertThat(b.get("createdAt").asText()).isEqualTo("2026-10-21T09:00");
        assertThat(r.header("Location")).endsWith("/api/v1/bookings/" + b.get("id").asLong());

        Resp fetched = get("/api/v1/bookings/" + b.get("id").asLong());
        assertThat(fetched.status()).isEqualTo(200);
        assertThat(fetched.json()).isEqualTo(b);
    }

    @Test
    void createWithoutMasterPicksAnyFreeMaster() {
        Resp r = post("/api/v1/bookings", bookingJson("Иван", "+37491123456", "Борода", null, THURSDAY, "12:00"));
        assertThat(r.status()).isEqualTo(201);
        assertThat(r.json().get("masterName").asText()).isIn("Анна", "Арам");
        Resp blank = post("/api/v1/bookings", bookingJson("Иван", "+37491123456", "Борода", "  ", THURSDAY, "13:00"));
        assertThat(blank.status()).isEqualTo(201);
        Resp empty = post("/api/v1/bookings", bookingJson("Иван", "+37491123456", "Борода", "", THURSDAY, "14:00"));
        assertThat(empty.status()).isEqualTo(201);
    }

    // ------------------------------------------------------------ создание: валидация

    private Resp createWith(String name, String phone, String service, String master, String date, String time) {
        String body = "{" + field("clientName", name) + "," + field("phone", phone) + ","
                + field("serviceName", service) + "," + field("masterName", master) + ","
                + field("date", date) + "," + field("time", time) + "}";
        return post("/api/v1/bookings", body);
    }

    private void assertField(Resp r, String field) {
        assertThat(r.status()).as(r.body()).isEqualTo(400);
        assertThat(r.json().get("error").asText()).isEqualTo("validation");
        assertThat(r.json().get("fields").has(field)).as("fields.%s в %s", field, r.body()).isTrue();
    }

    @ParameterizedTest
    @CsvSource({"1,false", "2,true", "100,true", "101,false"})
    void clientNameLengthBoundaries(int length, boolean ok) {
        Resp r = createWith("И".repeat(length), "+37491123456", "Борода", "Анна", THURSDAY.toString(), "10:00");
        if (ok) {
            assertThat(r.status()).isEqualTo(201);
        } else {
            assertField(r, "clientName");
        }
    }

    @Test
    void clientNameIsTrimmedBeforeCheck() {
        assertField(createWith("   И   ", "+37491123456", "Борода", "Анна", THURSDAY.toString(), "10:00"), "clientName");
        assertField(createWith("      ", "+37491123456", "Борода", "Анна", THURSDAY.toString(), "10:00"), "clientName");
        assertField(createWith(null, "+37491123456", "Борода", "Анна", THURSDAY.toString(), "10:00"), "clientName");
    }

    @ParameterizedTest
    @CsvSource({"1234,false", "12345,true", "+3749112345678901234,true", "+37491123456789012345,false"})
    void phoneLengthBoundaries(String phone, boolean ok) {
        Resp r = createWith("Иван", phone, "Борода", "Анна", THURSDAY.toString(), "10:00");
        if (ok) {
            assertThat(r.status()).as(r.body()).isEqualTo(201);
        } else {
            assertField(r, "phone");
        }
    }

    @Test
    void phoneBoundaryLengthsAreExact() {
        assertThat(LONG_PHONE_20).hasSize(20);
        assertThat(LONG_PHONE_20 + "5").hasSize(21);
    }

    @ParameterizedTest
    @ValueSource(strings = {"abcde12345", "+++++", "(((((", "12 34", "+7 (9", "tel:12345", "12345<script>"})
    void phoneWithBadCharsOrTooFewDigitsRejected(String phone) {
        assertField(createWith("Иван", phone, "Борода", "Анна", THURSDAY.toString(), "10:00"), "phone");
    }

    @Test
    void phoneMissingRejectedAndNormalizationVariants() {
        assertField(createWith("Иван", null, "Борода", "Анна", THURSDAY.toString(), "10:00"), "phone");
        Resp r = createWith("Иван", "8 (091) 12-34-56", "Борода", "Анна", THURSDAY.toString(), "10:00");
        assertThat(r.status()).isEqualTo(201);
        assertThat(r.json().get("phone").asText()).isEqualTo("8091123456");
    }

    @Test
    void serviceAndMasterValidation() {
        assertField(createWith("Иван", "+37491123456", null, "Анна", THURSDAY.toString(), "10:00"), "serviceName");
        assertField(createWith("Иван", "+37491123456", "", "Анна", THURSDAY.toString(), "10:00"), "serviceName");
        assertField(createWith("Иван", "+37491123456", "Нет такой", "Анна", THURSDAY.toString(), "10:00"), "serviceName");
        assertField(createWith("Иван", "+37491123456", "Борода", "Нет такого", THURSDAY.toString(), "10:00"), "masterName");
    }

    @Test
    void allErrorsAreReportedTogether() {
        Resp r = post("/api/v1/bookings", "{}");
        assertThat(r.status()).isEqualTo(400);
        JsonNode fields = r.json().get("fields");
        assertThat(fields.has("clientName")).isTrue();
        assertThat(fields.has("phone")).isTrue();
        assertThat(fields.has("serviceName")).isTrue();
        assertThat(fields.has("date")).isTrue();
        assertThat(fields.has("time")).isTrue();
        assertThat(fields.has("masterName")).as("мастер необязателен").isFalse();
        assertThat(allBookings()).isEmpty();
    }

    @Test
    void dateBoundaries() {
        assertField(createWith("Иван", "+37491123456", "Борода", "Анна", "2026-10-20", "12:00"), "date");
        assertField(createWith("Иван", "+37491123456", "Борода", "Анна", "2020-01-01", "12:00"), "date");
        assertField(createWith("Иван", "+37491123456", "Борода", "Анна", null, "12:00"), "date");
        assertField(createWith("Иван", "+37491123456", "Борода", "Анна", "22.10.2026", "12:00"), "date");
        assertField(createWith("Иван", "+37491123456", "Борода", "Анна", "2026-13-40", "12:00"), "date");
        // сегодня — можно, если время в будущем
        assertThat(createWith("Иван", "+37491123456", "Борода", "Анна", "2026-10-21", "12:00").status()).isEqualTo(201);
    }

    /** Баг: 2027-02-30 молча превращается в 2027-02-28 (ResolverStyle.SMART). */
    @Test
    void impossibleDateIsRejectedNotShifted() {
        Resp r = createWith("Иван", "+37491123456", "Борода", "Анна", "2027-02-30", "12:00");
        assertThat(r.status()).as("ответ: " + r.body()).isEqualTo(400);
        assertThat(allBookings()).isEmpty();
    }

    /** Баг (мелкий): ResolverStyle.SMART принимает 24:00 как 00:00. */
    @Test
    void time2400IsRejected() {
        Resp r = createWith("Иван", "+37491123456", "Борода", "Анна", THURSDAY.toString(), "24:00");
        assertThat(r.status()).as(r.body()).isEqualTo(400);
        assertThat(r.json().get("fields").has("time")).isTrue();
    }

    @Test
    void timeFormatAndPastTimeToday() {
        for (String bad : new String[]{"25:00", "10-00", "9", "ab:cd", "10:00:00", "10:60"}) {
            assertField(createWith("Иван", "+37491123456", "Борода", "Анна", THURSDAY.toString(), bad), "time");
        }
        assertField(createWith("Иван", "+37491123456", "Борода", "Анна", THURSDAY.toString(), null), "time");
        // сейчас 09:00: ровно сейчас и раньше — «время прошло»
        assertField(createWith("Иван", "+37491123456", "Борода", null, TODAY.toString(), "09:00"), "time");
        assertField(createWith("Иван", "+37491123456", "Борода", null, TODAY.toString(), "08:30"), "time");
        assertField(createWith("Иван", "+37491123456", "Борода", "Мариам", TODAY.toString(), "00:00"), "time");
        // 09:30 — будущее; Мариам работает с 09:00
        assertThat(createWith("Иван", "+37491123456", "Борода", "Мариам", TODAY.toString(), "09:30").status())
                .isEqualTo(201);
    }

    @Test
    void masterDoesNotWorkOnDayIs400OnDate() {
        Resp r = createWith("Иван", "+37491123456", "Борода", "Анна", SATURDAY.toString(), "12:00");
        assertField(r, "date");
        assertThat(createWith("Иван", "+37491123456", "Борода", "Анна", SUNDAY.toString(), "12:00").status())
                .isEqualTo(400);
        assertThat(createWith("Иван", "+37491123456", "Борода", "Арам", SATURDAY.toString(), "12:00").status())
                .isEqualTo(201);
    }

    @Test
    void slotOutsideWorkingHoursOrOffGridIs409() {
        assertThat(createWith("Иван", "+37491123456", "Борода", "Анна", THURSDAY.toString(), "09:30").status())
                .isEqualTo(409);
        assertThat(createWith("Иван", "+37491123456", "Борода", "Анна", THURSDAY.toString(), "19:00").status())
                .isEqualTo(409);
        Resp offGrid = createWith("Иван", "+37491123456", "Борода", "Анна", THURSDAY.toString(), "10:15");
        assertThat(offGrid.status()).isEqualTo(409);
        assertThat(offGrid.json().get("error").asText()).isEqualTo("slot_taken");
        assertThat(allBookings()).isEmpty();
    }

    // ------------------------------------------------------------ чтение

    @Test
    void getUnknownAndMalformedId() {
        assertThat(get("/api/v1/bookings/999").status()).isEqualTo(404);
        assertThat(get("/api/v1/bookings/999").json().get("error").asText()).isEqualTo("not_found");
        assertThat(get("/api/v1/bookings/-1").status()).isEqualTo(404);
        assertThat(get("/api/v1/bookings/0").status()).isEqualTo(404);
        Resp bad = get("/api/v1/bookings/abc");
        assertThat(bad.status()).isEqualTo(400);
        assertThat(bad.json().get("fields").has("id")).isTrue();
        assertThat(get("/api/v1/bookings/99999999999999999999").status()).isEqualTo(400);
        assertThat(get("/api/v1/bookings/1.5").status()).isEqualTo(400);
    }

    @Test
    void getCancelledBookingStillReadable() {
        Booking b = seedAndCancel("Отмен", "Борода", "Анна", THURSDAY, "10:00");
        Resp r = get("/api/v1/bookings/" + b.getId());
        assertThat(r.status()).isEqualTo(200);
        assertThat(r.json().get("status").asText()).isEqualTo("CANCELLED");
    }

    @Test
    void listIsEmptyArrayWhenNoBookings() {
        Resp r = get("/api/v1/bookings");
        assertThat(r.status()).isEqualTo(200);
        assertThat(r.body().trim()).isEqualTo("[]");
    }

    @Test
    void listSortedByDateTimeThenId() {
        seed("Пётр", "+37491000001", "Борода", "Арам", THURSDAY, "15:00", 11, "petr_i");
        seed("Иван", "+37491000002", "Окрашивание", "Анна", THURSDAY, "10:00", 12, "ivan");
        seed("Мария", "+37455123123", "Стрижка женская", "Анна", TODAY, "12:00", 13, "maria");
        seed("Одинаковое время", "+37491000003", "Борода", "Анна", THURSDAY, "15:00", 14, "same");
        List<Long> ids = ids(get("/api/v1/bookings"));
        // TODAY 12:00 (id3) -> THURSDAY 10:00 (id2) -> 15:00: id1, затем id4
        assertThat(ids).containsExactly(3L, 2L, 1L, 4L);
    }

    @Test
    void filterByDateAndPeriod() {
        seed("A1", "+37491000001", "Борода", "Анна", TODAY, "12:00", 1, "a");
        seed("A2", "+37491000002", "Борода", "Анна", THURSDAY, "12:00", 1, "a");
        seed("A3", "+37491000003", "Борода", "Арам", SATURDAY, "12:00", 1, "a");
        assertThat(ids(get("/api/v1/bookings?date=2026-10-22"))).containsExactly(2L);
        assertThat(ids(get("/api/v1/bookings?from=2026-10-22"))).containsExactly(2L, 3L);
        assertThat(ids(get("/api/v1/bookings?to=2026-10-22"))).containsExactly(1L, 2L);
        assertThat(ids(get("/api/v1/bookings?from=2026-10-22&to=2026-10-22"))).as("границы включительно")
                .containsExactly(2L);
        assertThat(ids(get("/api/v1/bookings?from=2026-10-21&to=2026-10-24"))).containsExactly(1L, 2L, 3L);
        assertThat(ids(get("/api/v1/bookings?date=2030-01-01"))).isEmpty();
    }

    @Test
    void filterConflictsAndBadValues() {
        Resp both = get("/api/v1/bookings?date=2026-10-22&from=2026-10-21");
        assertField(both, "date");
        assertField(get("/api/v1/bookings?date=2026-10-22&to=2026-10-23"), "date");
        assertField(get("/api/v1/bookings?from=2026-10-23&to=2026-10-22"), "from");
        assertField(get("/api/v1/bookings?status=DONE"), "status");
        assertField(get("/api/v1/bookings?date=bad"), "date");
        assertField(get("/api/v1/bookings?from=bad"), "from");
        assertField(get("/api/v1/bookings?to=bad"), "to");
        Resp many = get("/api/v1/bookings?from=x&to=y&status=z");
        assertThat(many.json().get("fields").size()).isEqualTo(3);
    }

    @Test
    void filterByStatusCaseInsensitive() {
        seed("Живой", "+37491000001", "Борода", "Анна", THURSDAY, "10:00", 1, "a");
        seedAndCancel("Отменённый", "Борода", "Анна", THURSDAY, "11:00");
        assertThat(ids(get("/api/v1/bookings?status=CONFIRMED"))).containsExactly(1L);
        assertThat(ids(get("/api/v1/bookings?status=cancelled"))).containsExactly(2L);
        assertThat(ids(get("/api/v1/bookings?status="))).hasSize(2);
        assertThat(ids(get("/api/v1/bookings?status=Cancelled&date=2026-10-22"))).containsExactly(2L);
    }

    @Test
    void filterByTextIsCaseInsensitiveAcrossFields() {
        seed("Иван Сидоров", "+37491000002", "Окрашивание", "Анна", THURSDAY, "10:00", 12, "VanyaTG");
        seed("Мария Ли", "+37455123123", "Стрижка женская", "Арам", THURSDAY, "11:00", 13, "maria");
        assertThat(ids(get("/api/v1/bookings?q=" + enc("ИВАН")))).containsExactly(1L);
        assertThat(ids(get("/api/v1/bookings?q=" + enc("иван сид")))).containsExactly(1L);
        assertThat(ids(get("/api/v1/bookings?q=" + enc("мария")))).containsExactly(2L);
        assertThat(ids(get("/api/v1/bookings?q=" + enc("55123")))).as("по телефону").containsExactly(2L);
        assertThat(ids(get("/api/v1/bookings?q=" + enc("ОКРАШ")))).as("по услуге").containsExactly(1L);
        assertThat(ids(get("/api/v1/bookings?q=" + enc("vanyatg")))).as("по username").containsExactly(1L);
        assertThat(ids(get("/api/v1/bookings?q=" + enc("  иван  ")))).as("пробелы обрезаются").containsExactly(1L);
        assertThat(ids(get("/api/v1/bookings?q=" + enc("   ")))).as("пустой q = без фильтра").hasSize(2);
        assertThat(ids(get("/api/v1/bookings?q=" + enc("%")))).as("спецсимволы буквальные").isEmpty();
        assertThat(ids(get("/api/v1/bookings?q=" + enc(".*")))).isEmpty();
        assertThat(ids(get("/api/v1/bookings?q=" + enc("нет такого")))).isEmpty();
    }

    @Test
    void filterByMasterNameCaseInsensitive() {
        seed("A", "+37491000001", "Борода", "Анна", THURSDAY, "10:00", 1, "a");
        seed("B", "+37491000002", "Борода", "Арам", THURSDAY, "12:00", 1, "a");
        assertThat(ids(get("/api/v1/bookings?masterName=" + enc("анна")))).containsExactly(1L);
        assertThat(ids(get("/api/v1/bookings?masterName=" + enc("АРАМ")))).containsExactly(2L);
        assertThat(ids(get("/api/v1/bookings?masterName=" + enc("Неизвестный")))).isEmpty();
        assertThat(ids(get("/api/v1/bookings?masterName=" + enc("Ан")))).as("точное имя, не подстрока").isEmpty();
        assertThat(ids(get("/api/v1/bookings?masterName="))).hasSize(2);
    }

    @Test
    void filtersCombine() {
        seed("Иван А", "+37491000001", "Борода", "Анна", THURSDAY, "10:00", 1, "a");
        seed("Иван Б", "+37491000002", "Борода", "Арам", THURSDAY, "12:00", 1, "a");
        seed("Иван В", "+37491000003", "Борода", "Арам", SATURDAY, "12:00", 1, "a");
        assertThat(ids(get("/api/v1/bookings?q=" + enc("иван") + "&masterName=" + enc("арам")
                + "&from=2026-10-22&to=2026-10-22&status=CONFIRMED"))).containsExactly(2L);
    }

    // ------------------------------------------------------------ старые строки Excel

    private void writeLegacyRows() throws IOException {
        try (Workbook wb = new XSSFWorkbook(Files.newInputStream(excelFile()))) {
            Sheet sheet = wb.getSheet("Записи");
            // 1) Нет даты создания, нет username/напоминания/источника
            Row r1 = sheet.createRow(sheet.getLastRowNum() + 1);
            r1.createCell(0).setCellValue(50);
            r1.createCell(2).setCellValue("Старый Клиент");
            r1.createCell(3).setCellValue(0);
            r1.createCell(5).setCellValue("+37411111111");
            r1.createCell(6).setCellValue("Борода");
            r1.createCell(7).setCellValue("Анна");
            r1.createCell(8).setCellValue("22.10.2026");
            r1.createCell(9).setCellValue("13:00");
            // 2) Текстовые ячейки вместо дат, пустой статус, createdAt текстом
            Row r2 = sheet.createRow(sheet.getLastRowNum() + 1);
            r2.createCell(0).setCellValue("51");
            r2.createCell(1).setCellValue("01.10.2026 10:05");
            r2.createCell(2).setCellValue("Текстовые Даты");
            r2.createCell(3).setCellValue("77");
            r2.createCell(5).setCellValue("+37422222222");
            r2.createCell(6).setCellValue("Неизвестная услуга");
            r2.createCell(7).setCellValue("Анна");
            r2.createCell(8).setCellValue("23.10.2026");
            r2.createCell(9).setCellValue("9:30");
            r2.createCell(11).setCellValue("");
            // 3) Мусор в дате — строка пропускается, остальные читаются
            Row r3 = sheet.createRow(sheet.getLastRowNum() + 1);
            r3.createCell(0).setCellValue(52);
            r3.createCell(2).setCellValue("Битая");
            r3.createCell(3).setCellValue(0);
            r3.createCell(8).setCellValue("не дата");
            r3.createCell(9).setCellValue("10:00");
            try (OutputStream out = Files.newOutputStream(excelFile())) {
                wb.write(out);
            }
        }
        repository.init();
    }

    @Test
    void legacyRowsWithoutCreatedAtDoNotBreakList() throws IOException {
        seed("Новый", "+37491000001", "Борода", "Арам", THURSDAY, "12:00", 1, "a");
        writeLegacyRows();

        Resp list = get("/api/v1/bookings");
        assertThat(list.status()).as(list.body()).isEqualTo(200);
        assertThat(ids(list)).contains(1L, 50L, 51L).doesNotContain(52L);
        JsonNode old = null;
        for (JsonNode n : list.json()) {
            if (n.get("id").asLong() == 50) {
                old = n;
            }
        }
        assertThat(old).isNotNull();
        assertThat(old.get("createdAt").isNull()).isTrue();
        assertThat(old.get("username").asText()).isEmpty();
        assertThat(old.get("status").asText()).isEqualTo("CONFIRMED");
        assertThat(old.get("reminderSent").asBoolean()).isFalse();
        assertThat(old.get("durationMinutes").asInt()).isEqualTo(30);

        Resp one = get("/api/v1/bookings/50");
        assertThat(one.status()).isEqualTo(200);
        Resp text = get("/api/v1/bookings/51");
        assertThat(text.status()).isEqualTo(200);
        assertThat(text.json().get("createdAt").asText()).isEqualTo("2026-10-01T10:05");
        assertThat(text.json().get("time").asText()).isEqualTo("09:30");
        assertThat(text.json().get("durationMinutes").asInt()).as("неизвестная услуга = 60 по умолчанию")
                .isEqualTo(60);
        // фильтры и сортировка тоже работают
        assertThat(get("/api/v1/bookings?q=" + enc("старый")).status()).isEqualTo(200);
        assertThat(get("/api/v1/bookings?date=2026-10-22").status()).isEqualTo(200);
        // и создание новой записи рядом со старыми строками
        assertThat(book("Борода", "Арам", THURSDAY, "11:00").status()).isEqualTo(201);
        // отмена старой строки
        Resp cancel = post("/api/v1/bookings/50/cancel", null);
        assertThat(cancel.status()).isEqualTo(200);
        assertThat(cancel.json().get("createdAt").isNull()).isTrue();
        assertThat(cancel.header("X-Client-Notified")).isNull();
    }

    @Test
    void legacyRowOccupiesSlotOfItsMaster() throws IOException {
        writeLegacyRows();
        // строка 50: Анна, Борода, 22.10 13:00-13:30
        assertThat(book("Борода", "Анна", THURSDAY, "13:00").status()).isEqualTo(409);
        assertThat(book("Борода", "Анна", THURSDAY, "13:30").status()).isEqualTo(201);
    }

    // ------------------------------------------------------------ отмена

    @Test
    void cancelAdminBookingHasNoNotifiedHeader() {
        long id = book("Борода", "Анна", THURSDAY, "10:00").json().get("id").asLong();
        Resp r = post("/api/v1/bookings/" + id + "/cancel", null);
        assertThat(r.status()).isEqualTo(200);
        assertThat(r.json().get("status").asText()).isEqualTo("CANCELLED");
        assertThat(r.header("X-Client-Notified")).isNull();
        verifyNoTelegram();
        assertThat(get("/api/v1/bookings/" + id).json().get("status").asText()).isEqualTo("CANCELLED");
    }

    private void verifyNoTelegram() {
        try {
            verify(telegramClient, never()).execute(any(SendMessage.class));
        } catch (TelegramApiException e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void cancelTwiceIs409AlreadyCancelled() {
        long id = book("Борода", "Анна", THURSDAY, "10:00").json().get("id").asLong();
        assertThat(post("/api/v1/bookings/" + id + "/cancel", null).status()).isEqualTo(200);
        Resp again = post("/api/v1/bookings/" + id + "/cancel", null);
        assertThat(again.status()).isEqualTo(409);
        assertThat(again.json().get("error").asText()).isEqualTo("already_cancelled");
    }

    @Test
    void cancelUnknownAndMalformed() {
        Resp r = post("/api/v1/bookings/12345/cancel", null);
        assertThat(r.status()).isEqualTo(404);
        assertThat(r.json().get("error").asText()).isEqualTo("not_found");
        assertThat(post("/api/v1/bookings/abc/cancel", null).status()).isEqualTo(400);
        assertThat(post("/api/v1/bookings/-5/cancel", null).status()).isEqualTo(404);
    }

    @Test
    void cancelNotifiesTelegramClientHeaderTrue() throws Exception {
        Message sent = mock(Message.class);
        when(sent.getMessageId()).thenReturn(77);
        when(telegramClient.execute(any(SendMessage.class))).thenReturn(sent);
        Booking b = seed("Клиент", "+37491000001", "Борода", "Анна", THURSDAY, "10:00", 4242, "cl");

        Resp r = post("/api/v1/bookings/" + b.getId() + "/cancel", null);
        assertThat(r.status()).isEqualTo(200);
        assertThat(r.header("X-Client-Notified")).isEqualTo("true");
        org.mockito.ArgumentCaptor<SendMessage> captor = org.mockito.ArgumentCaptor.forClass(SendMessage.class);
        verify(telegramClient).execute(captor.capture());
        assertThat(captor.getValue().getChatId()).isEqualTo("4242");
        assertThat(captor.getValue().getText()).contains("№" + b.getId()).contains("отменена");
        assertThat(repository.getAllBookings().get(0).getStatus()).isEqualTo(BookingStatus.CANCELLED);
    }

    @Test
    void cancelSucceedsWhenTelegramFailsHeaderFalse() throws Exception {
        when(telegramClient.execute(any(SendMessage.class))).thenThrow(new TelegramApiException("blocked by user"));
        Booking b = seed("Клиент", "+37491000001", "Борода", "Анна", THURSDAY, "10:00", 4242, "cl");

        Resp r = post("/api/v1/bookings/" + b.getId() + "/cancel", null);
        assertThat(r.status()).isEqualTo(200);
        assertThat(r.header("X-Client-Notified")).isEqualTo("false");
        assertThat(r.body()).doesNotContain("blocked by user");
        assertThat(repository.getAllBookings().get(0).getStatus()).as("отмена не откатилась")
                .isEqualTo(BookingStatus.CANCELLED);
    }

    @Test
    void cancelSucceedsWhenTelegramThrowsRuntimeException() throws Exception {
        when(telegramClient.execute(any(SendMessage.class))).thenThrow(new IllegalStateException("boom"));
        Booking b = seed("Клиент", "+37491000001", "Борода", "Анна", THURSDAY, "10:00", 4242, "cl");

        Resp r = post("/api/v1/bookings/" + b.getId() + "/cancel", null);
        assertThat(r.status()).isEqualTo(200);
        assertThat(r.header("X-Client-Notified")).isEqualTo("false");
        assertThat(repository.getAllBookings().get(0).getStatus()).isEqualTo(BookingStatus.CANCELLED);
    }

    @Test
    void cancelTelegramReturnsNullMessageHeaderFalse() throws Exception {
        when(telegramClient.execute(any(SendMessage.class))).thenReturn(null);
        Booking b = seed("Клиент", "+37491000001", "Борода", "Анна", THURSDAY, "10:00", 4242, "cl");
        Resp r = post("/api/v1/bookings/" + b.getId() + "/cancel", null);
        assertThat(r.status()).isEqualTo(200);
        assertThat(r.header("X-Client-Notified")).isEqualTo("false");
    }

    @Test
    void cancelOfBookingWithNegativeTelegramIdDoesNotNotify() {
        Booking b = seed("Группа", "+37491000001", "Борода", "Анна", THURSDAY, "10:00", -100500, "g");
        Resp r = post("/api/v1/bookings/" + b.getId() + "/cancel", null);
        assertThat(r.status()).isEqualTo(200);
        assertThat(r.header("X-Client-Notified")).isNull();
        verifyNoTelegram();
    }

    @Test
    void cancelledBookingFreesSlotInApi() {
        long id = book("Борода", "Анна", THURSDAY, "10:00").json().get("id").asLong();
        assertThat(book("Борода", "Анна", THURSDAY, "10:00").status()).isEqualTo(409);
        post("/api/v1/bookings/" + id + "/cancel", null);
        assertThat(book("Борода", "Анна", THURSDAY, "10:00").status()).isEqualTo(201);
    }

    private static String enc(String s) {
        return java.net.URLEncoder.encode(s, java.nio.charset.StandardCharsets.UTF_8);
    }

    @SuppressWarnings("unused")
    private LocalDate unused() {
        return TODAY;
    }
}
