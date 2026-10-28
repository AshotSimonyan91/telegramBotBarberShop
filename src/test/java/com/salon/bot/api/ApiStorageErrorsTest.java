package com.salon.bot.api;

import com.salon.bot.model.Booking;
import com.salon.bot.storage.BookingRepository;
import com.salon.bot.storage.StorageBusyException;
import com.salon.bot.storage.StorageException;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.io.IOException;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;

/** Сбои хранилища: 503/500 с фиксированным текстом, без утечки путей и деталей исключений. */
class ApiStorageErrorsTest extends ApiTestBase {

    private static final String SECRET = "SECRET-/Users/hidden/path/salon_bookings.xlsx";

    @MockitoSpyBean
    BookingRepository spyRepository;

    private static RuntimeException busy() {
        return new StorageBusyException("Excel-файл занят: " + SECRET, new IOException("locked " + SECRET));
    }

    private static RuntimeException failure() {
        return new StorageException("Ошибка обработки Excel-файла " + SECRET, new IllegalStateException(SECRET));
    }

    private void breakEverything(RuntimeException e) {
        doThrow(e).when(spyRepository).getServices();
        doThrow(e).when(spyRepository).getMasters();
        doThrow(e).when(spyRepository).getAllBookings();
        doThrow(e).when(spyRepository).getBookings(any(java.time.LocalDate.class));
        doThrow(e).when(spyRepository).getBookings(any(String.class), any(java.time.LocalDate.class));
        doThrow(e).when(spyRepository).addBooking(any(Booking.class));
        doThrow(e).when(spyRepository).cancelBooking(anyLong());
    }

    private void assertBusy(Resp r) {
        assertThat(r.status()).as(r.body()).isEqualTo(503);
        assertThat(r.json().get("error").asText()).isEqualTo("storage_busy");
        assertThat(r.json().get("message").asText()).isNotBlank();
        assertThat(r.body()).doesNotContain("SECRET", "/Users", "xlsx", "Exception", "IOException", "locked");
    }

    private void assertFailure(Resp r, String code) {
        assertThat(r.status()).as(r.body()).isEqualTo(500);
        assertThat(r.json().get("error").asText()).isEqualTo(code);
        assertThat(r.body()).doesNotContain("SECRET", "/Users", "xlsx", "Exception", "IllegalState", "at com.");
    }

    private void forEachEndpoint(Consumer<Resp> check) {
        check.accept(get("/api/v1/services"));
        check.accept(get("/api/v1/masters"));
        check.accept(get("/api/v1/bookings"));
        check.accept(get("/api/v1/bookings?date=2026-10-22"));
        check.accept(get("/api/v1/bookings/1"));
        check.accept(get("/api/v1/slots?serviceName=Борода&date=2026-10-22"));
        check.accept(get("/api/v1/dates"));
        check.accept(book("Борода", "Анна", THURSDAY, "10:00"));
        check.accept(post("/api/v1/bookings/1/cancel", null));
    }

    @Test
    void storageBusyIs503OnEveryStorageEndpoint() {
        breakEverything(busy());
        forEachEndpoint(this::assertBusy);
    }

    @Test
    void storageFailureIs500StorageError() {
        breakEverything(failure());
        forEachEndpoint(r -> assertFailure(r, "storage_error"));
    }

    @Test
    void unexpectedRuntimeExceptionIs500Internal() {
        breakEverything(new NullPointerException(SECRET));
        forEachEndpoint(r -> assertFailure(r, "internal"));
    }

    @Test
    void healthStaysUpWhenStorageIsDown() {
        breakEverything(busy());
        assertThat(call("GET", "/api/v1/health", null, null).status()).isEqualTo(200);
    }

    @Test
    void statusReportsStorageDownWithoutErrorStatus() {
        doThrow(busy()).when(spyRepository).getServices();
        Resp r = get("/api/v1/status");
        assertThat(r.status()).isEqualTo(200);
        assertThat(r.json().at("/storage/ok").asBoolean()).isFalse();
        assertThat(r.body()).doesNotContain("SECRET");
    }

    @Test
    void failedWriteDoesNotLeaveBooking() {
        doThrow(busy()).when(spyRepository).addBooking(any(Booking.class));
        assertBusy(book("Борода", "Анна", THURSDAY, "10:00"));
    }

    @Test
    void failedCancelKeepsBookingActive() {
        Booking b = seed("Клиент", "+37491000001", "Борода", "Анна", THURSDAY, "10:00", 5, "u");
        doThrow(busy()).when(spyRepository).cancelBooking(anyLong());
        assertBusy(post("/api/v1/bookings/" + b.getId() + "/cancel", null));
        org.mockito.Mockito.reset(spyRepository);
        assertThat(get("/api/v1/bookings/" + b.getId()).json().get("status").asText()).isEqualTo("CONFIRMED");
    }

    @Test
    void validationErrorsWinOverStorageErrorsWhereStorageIsNotNeeded() {
        // 401 не зависит от хранилища
        doThrow(busy()).when(spyRepository).getServices();
        assertThat(call("GET", "/api/v1/services", "bad", null).status()).isEqualTo(401);
    }
}
