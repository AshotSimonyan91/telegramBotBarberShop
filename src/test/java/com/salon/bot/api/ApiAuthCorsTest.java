package com.salon.bot.api;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/** Авторизация по Bearer-токену, CORS, 404/405 и разбор «плохих» запросов. */
class ApiAuthCorsTest extends ApiTestBase {

    // ---------------------------------------------------------- авторизация

    @ParameterizedTest
    @ValueSource(strings = {"/api/v1/services", "/api/v1/masters", "/api/v1/bookings", "/api/v1/bookings/1",
            "/api/v1/slots", "/api/v1/dates", "/api/v1/status"})
    void protectedGetWithoutTokenIs401(String path) {
        Resp r = call("GET", path, null, null);
        assertThat(r.status()).isEqualTo(401);
        assertThat(r.json().get("error").asText()).isEqualTo("unauthorized");
        assertThat(r.json().get("message").asText()).isNotBlank();
        assertThat(r.header("WWW-Authenticate")).isEqualTo("Bearer");
        assertThat(r.header("Content-Type")).contains("application/json");
    }

    @Test
    void postsWithoutTokenAre401() {
        assertThat(call("POST", "/api/v1/bookings", null, "{}", "Content-Type", "application/json").status())
                .isEqualTo(401);
        assertThat(call("POST", "/api/v1/bookings/1/cancel", null, null).status()).isEqualTo(401);
    }

    @Test
    void wrongTokenIs401() {
        assertThat(call("GET", "/api/v1/services", "wrong", null).status()).isEqualTo(401);
        assertThat(call("GET", "/api/v1/services", TOKEN + "x", null).status()).isEqualTo(401);
        assertThat(call("GET", "/api/v1/services", TOKEN.substring(1), null).status()).isEqualTo(401);
        assertThat(call("GET", "/api/v1/services", "", null).status()).isEqualTo(401);
    }

    @Test
    void otherSchemesAndQueryTokenAreRejected() {
        assertThat(call("GET", "/api/v1/services", null, null, "Authorization", "Basic " + TOKEN).status())
                .isEqualTo(401);
        assertThat(call("GET", "/api/v1/services", null, null, "Authorization", TOKEN).status()).isEqualTo(401);
        assertThat(call("GET", "/api/v1/services?token=" + TOKEN, null, null).status()).isEqualTo(401);
        assertThat(call("GET", "/api/v1/services", null, null, "X-Api-Token", TOKEN).status()).isEqualTo(401);
    }

    @Test
    void validTokenIsAccepted() {
        assertThat(get("/api/v1/services").status()).isEqualTo(200);
        assertThat(call("GET", "/api/v1/services", null, null, "Authorization", "bearer " + TOKEN).status())
                .as("схема Bearer без учёта регистра").isEqualTo(200);
    }

    @Test
    void healthDoesNotNeedToken() {
        Resp r = call("GET", "/api/v1/health", null, null);
        assertThat(r.status()).isEqualTo(200);
        assertThat(r.json().get("status").asText()).isEqualTo("ok");
        assertThat(r.json().get("timezone").asText()).isEqualTo("Asia/Yerevan");
        assertThat(r.json().get("now").asText()).isEqualTo("2026-10-21T09:00");
        // и с неверным токеном — тоже
        assertThat(call("GET", "/api/v1/health", "wrong", null).status()).isEqualTo(200);
    }

    @Test
    void healthExemptionIsOnlyForGetAndExactPath() {
        assertThat(call("POST", "/api/v1/health", null, null).status()).isEqualTo(401);
        assertThat(call("GET", "/api/v1/health/", null, null).status()).isEqualTo(401);
        assertThat(call("GET", "/api/v1/health/../bookings", null, null).status())
                .as("обход токена через ..").isNotEqualTo(200);
        assertThat(call("GET", "/api/v1/health;x=1/../bookings", null, null).status()).isNotEqualTo(200);
    }

    @Test
    void unknownPathsWithoutTokenAreAlso401NotLeaking404() {
        assertThat(call("GET", "/", null, null).status()).isEqualTo(401);
        assertThat(call("GET", "/actuator/env", null, null).status()).isEqualTo(401);
        assertThat(call("GET", "/api/v1/nothing", null, null).status()).isEqualTo(401);
    }

    // ---------------------------------------------------------- 404/405 и тело

    @Test
    void unknownPathIs404Json() {
        Resp r = get("/api/v1/nothing");
        assertThat(r.status()).isEqualTo(404);
        assertThat(r.json().get("error").asText()).isEqualTo("not_found");
        Resp root = get("/");
        assertThat(root.status()).isEqualTo(404);
        assertThat(root.json().get("error").asText()).isEqualTo("not_found");
    }

    @Test
    void trailingSlashIs404() {
        assertThat(get("/api/v1/bookings/").status()).isEqualTo(404);
    }

    @Test
    void wrongMethodIs405Json() {
        Resp r = call("POST", "/api/v1/services", TOKEN, "{}", "Content-Type", "application/json");
        assertThat(r.status()).isEqualTo(405);
        assertThat(r.json().get("error").asText()).isEqualTo("method_not_allowed");
        assertThat(call("DELETE", "/api/v1/bookings/1", TOKEN, null).status()).isEqualTo(405);
        assertThat(call("PUT", "/api/v1/bookings", TOKEN, "{}", "Content-Type", "application/json").status())
                .isEqualTo(405);
        assertThat(call("GET", "/api/v1/bookings/1/cancel", TOKEN, null).status()).isEqualTo(405);
        assertThat(call("POST", "/api/v1/status", TOKEN, null).status()).isEqualTo(405);
    }

    @Test
    void brokenJsonIs400WithoutInternals() {
        for (String body : new String[]{"{", "not json", "[1,2]", "{\"clientName\":{\"a\":1}}", ""}) {
            Resp r = post("/api/v1/bookings", body);
            assertThat(r.status()).as(body).isEqualTo(400);
            assertThat(r.json().get("error").asText()).as(body).isEqualTo("validation");
            assertThat(r.body()).as(body).doesNotContain("Exception", "jackson", "com.salon");
        }
    }

    @Test
    void jsonNullBodyIsValidationNotServerError() {
        Resp r = post("/api/v1/bookings", "null");
        assertThat(r.status()).isEqualTo(400);
        assertThat(r.json().get("error").asText()).isEqualTo("validation");
    }

    @Test
    void wrongContentTypeIs400() {
        Resp r = call("POST", "/api/v1/bookings", TOKEN, "x", "Content-Type", "text/plain");
        assertThat(r.status()).isEqualTo(400);
        assertThat(r.json().get("error").asText()).isEqualTo("validation");
    }

    @Test
    void unknownJsonFieldsAreIgnored() {
        Resp r = post("/api/v1/bookings", "{\"clientName\":\"Иван\",\"phone\":\"+37491123456\","
                + "\"serviceName\":\"Борода\",\"masterName\":\"Анна\",\"date\":\"2026-10-22\","
                + "\"time\":\"12:00\",\"status\":\"CANCELLED\",\"id\":777,\"telegramId\":5}");
        assertThat(r.status()).isEqualTo(201);
        assertThat(r.json().get("status").asText()).isEqualTo("CONFIRMED");
        assertThat(r.json().get("telegramId").asLong()).isZero();
        assertThat(r.json().get("id").asLong()).isNotEqualTo(777);
    }

    // ---------------------------------------------------------- CORS

    @ParameterizedTest
    @ValueSource(strings = {"http://localhost:5173", "http://127.0.0.1:5173", "null", "app://salon",
            "app://-", "file://localhost"})
    void preflightAllowedOrigins(String origin) {
        Resp r = call("OPTIONS", "/api/v1/bookings", null, null,
                "Origin", origin,
                "Access-Control-Request-Method", "POST",
                "Access-Control-Request-Headers", "authorization,content-type");
        assertThat(r.status()).as(origin).isEqualTo(200);
        assertThat(r.header("Access-Control-Allow-Origin")).as(origin).isEqualTo(origin);
        assertThat(r.header("Access-Control-Allow-Methods")).contains("POST", "GET");
        assertThat(r.header("Access-Control-Allow-Headers").toLowerCase()).contains("authorization", "content-type");
        assertThat(r.header("Access-Control-Allow-Credentials")).isNull();
    }

    /** Контракт: разрешены Origin file:// и app:// (без хоста) — фактически 403 (шаблоны file://* / app://* требуют хост). */
    @ParameterizedTest
    @ValueSource(strings = {"file://", "app://"})
    void preflightOriginsWithEmptyHostAreAllowedPerContract(String origin) {
        Resp r = call("OPTIONS", "/api/v1/bookings", null, null,
                "Origin", origin, "Access-Control-Request-Method", "POST");
        assertThat(r.status()).as(origin).isEqualTo(200);
        assertThat(r.header("Access-Control-Allow-Origin")).as(origin).isEqualTo(origin);
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://evil.example", "http://localhost:5174", "http://localhost",
            "https://localhost:5173", "http://localhost:5173.evil.example", "http://evil.example/localhost:5173",
            "ftp://x"})
    void preflightForeignOriginIs403(String origin) {
        Resp r = call("OPTIONS", "/api/v1/bookings", null, null,
                "Origin", origin, "Access-Control-Request-Method", "GET");
        assertThat(r.status()).as(origin).isEqualTo(403);
        assertThat(r.header("Access-Control-Allow-Origin")).isNull();
    }

    @Test
    void preflightWithDisallowedMethodOrHeaderIs403() {
        assertThat(call("OPTIONS", "/api/v1/bookings/1", null, null, "Origin", "http://localhost:5173",
                "Access-Control-Request-Method", "DELETE").status()).isEqualTo(403);
        assertThat(call("OPTIONS", "/api/v1/bookings", null, null, "Origin", "http://localhost:5173",
                "Access-Control-Request-Method", "POST", "Access-Control-Request-Headers", "x-evil").status())
                .isEqualTo(403);
    }

    @Test
    void actualRequestFromAllowedOriginCarriesCorsHeaders() {
        Resp r = call("GET", "/api/v1/services", TOKEN, null, "Origin", "http://localhost:5173");
        assertThat(r.status()).isEqualTo(200);
        assertThat(r.header("Access-Control-Allow-Origin")).isEqualTo("http://localhost:5173");
    }

    @Test
    void unauthorizedResponseAlsoCarriesCorsHeaders() {
        Resp r = call("GET", "/api/v1/services", null, null, "Origin", "http://localhost:5173");
        assertThat(r.status()).isEqualTo(401);
        assertThat(r.header("Access-Control-Allow-Origin")).isEqualTo("http://localhost:5173");
    }

    @Test
    void actualRequestFromForeignOriginIsRejected() {
        Resp r = call("GET", "/api/v1/services", TOKEN, null, "Origin", "http://evil.example");
        assertThat(r.status()).isEqualTo(403);
        assertThat(r.body()).doesNotContain("Анна");
    }

    @Test
    void locationIsExposedToBrowser() {
        Resp r = call("GET", "/api/v1/services", TOKEN, null, "Origin", "http://localhost:5173");
        assertThat(r.header("Access-Control-Expose-Headers")).contains("Location");
    }

    /**
     * Баг: заголовок X-Client-Notified не добавлен в exposedHeaders — рендерер (Origin localhost:5173/null/app://)
     * не сможет прочитать результат уведомления клиента.
     */
    @Test
    void clientNotifiedHeaderIsExposedToBrowser() {
        Resp r = call("GET", "/api/v1/services", TOKEN, null, "Origin", "http://localhost:5173");
        assertThat(r.header("Access-Control-Expose-Headers"))
                .as("X-Client-Notified должен быть в Access-Control-Expose-Headers")
                .contains("X-Client-Notified");
    }
}
