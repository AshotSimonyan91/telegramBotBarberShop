package com.salon.bot.api;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** /services, /masters, /slots, /dates, /status (данные — примеры, которые создаёт ExcelBookingRepository.init). */
class ApiCatalogTest extends ApiTestBase {

    private static List<String> texts(JsonNode array) {
        List<String> result = new ArrayList<>();
        array.forEach(n -> result.add(n.asText()));
        return result;
    }

    @Test
    void services() {
        Resp r = get("/api/v1/services");
        assertThat(r.status()).isEqualTo(200);
        JsonNode first = r.json().get(0);
        assertThat(r.json()).hasSize(4);
        assertThat(first.get("name").asText()).isEqualTo("Стрижка мужская");
        assertThat(first.get("durationMinutes").asInt()).isEqualTo(45);
        assertThat(first.get("price").asInt()).isEqualTo(5000);
        assertThat(r.header("Content-Type")).contains("application/json");
    }

    @Test
    void mastersShape() {
        Resp r = get("/api/v1/masters");
        assertThat(r.status()).isEqualTo(200);
        assertThat(r.json()).hasSize(3);
        JsonNode anna = r.json().get(0);
        assertThat(anna.get("name").asText()).isEqualTo("Анна");
        assertThat(texts(anna.get("workDays"))).containsExactly("MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY");
        assertThat(anna.get("workStart").asText()).isEqualTo("10:00");
        assertThat(anna.get("workEnd").asText()).isEqualTo("19:00");
        assertThat(anna.get("active").asBoolean()).isTrue();
    }

    @Test
    void slotsForExplicitMasterCoverWorkingHoursOnThirtyMinuteGrid() {
        Resp r = get("/api/v1/slots?serviceName=Борода&date=2026-10-22&masterName=Анна");
        assertThat(r.status()).isEqualTo(200);
        assertThat(r.json().get("date").asText()).isEqualTo("2026-10-22");
        assertThat(r.json().get("serviceName").asText()).isEqualTo("Борода");
        assertThat(r.json().get("durationMinutes").asInt()).isEqualTo(30);
        List<String> slots = texts(r.json().get("slots"));
        assertThat(slots).startsWith("10:00", "10:30").endsWith("18:00", "18:30").hasSize(18);
    }

    @Test
    void lastSlotLeavesRoomForLongerService() {
        // 45 минут: 18:30 + 45 = 19:15 > 19:00, последний старт 18:00
        List<String> slots = texts(get("/api/v1/slots?serviceName=Стрижка мужская&date=2026-10-22&masterName=Анна")
                .json().get("slots"));
        assertThat(slots).endsWith("17:30", "18:00");
        // 120 минут: последний старт 17:00
        slots = texts(get("/api/v1/slots?serviceName=Окрашивание&date=2026-10-22&masterName=Анна")
                .json().get("slots"));
        assertThat(slots).endsWith("16:30", "17:00");
    }

    @Test
    void slotsWithoutMasterAreUnionOfAllMasters() {
        // четверг: Анна 10-19 и Арам 11-20; объединение 10:00..19:30 для 30 минут
        List<String> slots = texts(get("/api/v1/slots?serviceName=Борода&date=2026-10-22").json().get("slots"));
        assertThat(slots).startsWith("10:00").endsWith("19:30").doesNotHaveDuplicates().isSorted();
    }

    @Test
    void slotsEmptyOnNonWorkingDay() {
        Resp r = get("/api/v1/slots?serviceName=Борода&date=2026-10-25");
        assertThat(r.status()).isEqualTo(200);
        assertThat(r.json().get("slots")).isEmpty();
        Resp annaSaturday = get("/api/v1/slots?serviceName=Борода&date=2026-10-24&masterName=Анна");
        assertThat(annaSaturday.status()).isEqualTo(200);
        assertThat(annaSaturday.json().get("slots")).isEmpty();
    }

    @Test
    void slotsTodayHideThePast() {
        // сейчас 09:00 (среда); Мариам начинает в 09:00 — это уже не «будущее»
        List<String> slots = texts(get("/api/v1/slots?serviceName=Борода&date=2026-10-21&masterName=Мариам")
                .json().get("slots"));
        assertThat(slots).startsWith("09:30");
        clock.set(NOW.withHour(14).withMinute(10));
        slots = texts(get("/api/v1/slots?serviceName=Борода&date=2026-10-21&masterName=Мариам").json().get("slots"));
        assertThat(slots).startsWith("14:30");
    }

    @Test
    void slotsInThePastAreEmpty() {
        Resp r = get("/api/v1/slots?serviceName=Борода&date=2026-10-20");
        assertThat(r.status()).isEqualTo(200);
        assertThat(r.json().get("slots")).isEmpty();
    }

    @Test
    void slotsCaseInsensitiveNames() {
        Resp r = get("/api/v1/slots?serviceName=борода&date=2026-10-22&masterName=анна");
        assertThat(r.status()).isEqualTo(200);
        assertThat(r.json().get("serviceName").asText()).isEqualTo("Борода");
    }

    @Test
    void slotsParameterErrors() {
        Resp noDate = get("/api/v1/slots?serviceName=Борода");
        assertThat(noDate.status()).isEqualTo(400);
        assertThat(noDate.json().get("error").asText()).isEqualTo("validation");
        assertThat(noDate.json().get("fields").has("date")).isTrue();

        Resp badDate = get("/api/v1/slots?serviceName=Борода&date=22.10.2026");
        assertThat(badDate.status()).isEqualTo(400);
        assertThat(badDate.json().get("fields").has("date")).isTrue();

        Resp noService = get("/api/v1/slots?date=2026-10-22");
        assertThat(noService.status()).isEqualTo(400);
        assertThat(noService.json().get("fields").has("serviceName")).isTrue();

        assertThat(get("/api/v1/slots?serviceName=Борода&date=2026-10-22&masterName=Нет").status()).isEqualTo(404);
        Resp unknownService = get("/api/v1/slots?serviceName=Нет&date=2026-10-22");
        assertThat(unknownService.status()).isEqualTo(404);
        assertThat(unknownService.json().get("error").asText()).isEqualTo("not_found");
    }

    /** Баг: DateTimeFormatter в режиме SMART молча превращает 2027-02-30 в 2027-02-28. Ожидается 400. */
    @ParameterizedTest
    @ValueSource(strings = {"2027-02-30", "2027-02-31", "2027-04-31"})
    void impossibleCalendarDateIsRejected(String date) {
        Resp r = get("/api/v1/slots?serviceName=Борода&date=" + date);
        assertThat(r.status()).as("ответ: " + r.body()).isEqualTo(400);
        assertThat(r.json().get("fields").has("date")).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"2026-13-01", "2026-10-5", "2026/10/22", "abc", "2026-10-22T10:00", "'; DROP", "-1"})
    void malformedDatesAre400(String date) {
        Resp r = get("/api/v1/slots?serviceName=Борода&date=" + date.replace(" ", "%20").replace("'", "%27")
                .replace(";", "%3B"));
        assertThat(r.status()).as(date).isEqualTo(400);
        assertThat(r.json().get("fields").has("date")).isTrue();
    }

    // ---------------------------------------------------------- dates

    @Test
    void datesDefaultFourteenDaysExcludeDaysWithoutMasters() {
        Resp r = get("/api/v1/dates");
        assertThat(r.status()).isEqualTo(200);
        List<String> dates = texts(r.json());
        // 21.10 .. 03.11, воскресенья (25.10, 01.11) никто не работает
        assertThat(dates).hasSize(12).startsWith("2026-10-21").endsWith("2026-11-03")
                .doesNotContain("2026-10-25", "2026-11-01");
    }

    @Test
    void datesBoundaryDays() {
        assertThat(texts(get("/api/v1/dates?days=1").json())).containsExactly("2026-10-21");
        assertThat(get("/api/v1/dates?days=60").status()).isEqualTo(200);
        assertThat(get("/api/v1/dates?days=60").json().size()).isEqualTo(60 - 8);
        for (String bad : new String[]{"0", "61", "-1", "abc", "1.5", "99999999999"}) {
            Resp r = get("/api/v1/dates?days=" + bad);
            assertThat(r.status()).as(bad).isEqualTo(400);
            assertThat(r.json().get("fields").has("days")).as(bad).isTrue();
        }
        assertThat(get("/api/v1/dates?days=").json()).as("пусто = по умолчанию").hasSize(12);
    }

    @Test
    void datesForSingleMaster() {
        List<String> dates = texts(get("/api/v1/dates?masterName=Анна&days=7").json());
        assertThat(dates).containsExactly("2026-10-21", "2026-10-22", "2026-10-23", "2026-10-26", "2026-10-27");
    }

    @Test
    void datesUnknownMasterOrServiceIs404() {
        assertThat(get("/api/v1/dates?masterName=Нет").status()).isEqualTo(404);
        assertThat(get("/api/v1/dates?serviceName=Нет").status()).isEqualTo(404);
        assertThat(get("/api/v1/dates?serviceName=Борода").status()).isEqualTo(200);
    }

    // ---------------------------------------------------------- status

    @Test
    void status() {
        Resp r = get("/api/v1/status");
        assertThat(r.status()).isEqualTo(200);
        assertThat(r.json().at("/bot/running").asBoolean()).isTrue();
        assertThat(r.json().at("/bot/username").asText()).isEqualTo("TestSalonBot");
        assertThat(r.json().at("/storage/ok").asBoolean()).isTrue();
        assertThat(r.json().at("/storage/file").asText()).isEqualTo("salon_bookings.xlsx");
        assertThat(r.json().get("time").asText()).isEqualTo("2026-10-21T09:00");
        assertThat(r.body()).doesNotContain(DATA_DIR.toString());
    }
}
