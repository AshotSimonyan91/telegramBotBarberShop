package com.salon.bot.storage;

import com.salon.bot.model.Booking;
import com.salon.bot.model.BookingStatus;
import com.salon.bot.model.Master;
import com.salon.bot.model.Service;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class ExcelBookingRepositoryTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Yerevan");
    private static final LocalDate DATE = LocalDate.of(2026, 10, 22);

    @TempDir
    Path dir;

    private Path file;
    private ExcelBookingRepository repository;

    @BeforeEach
    void setUp() {
        file = dir.resolve("salon_bookings.xlsx");
        Clock clock = Clock.fixed(LocalDateTime.of(2026, 10, 21, 9, 0).atZone(ZONE).toInstant(), ZONE);
        repository = new ExcelBookingRepository(file, clock, 1, Duration.ofMillis(10));
        repository.init();
    }

    private static Booking booking(long telegramId, String master, String time) {
        return Booking.builder()
                .createdAt(LocalDateTime.of(2026, 10, 21, 9, 0))
                .clientName("Иван")
                .telegramId(telegramId)
                .username("ivan")
                .phone("+37491123456")
                .serviceName("Стрижка мужская")
                .masterName(master)
                .date(DATE)
                .time(LocalTime.parse(time))
                .source("salon_1")
                .status(BookingStatus.CONFIRMED)
                .build();
    }

    private Workbook open() throws Exception {
        try (InputStream in = Files.newInputStream(file)) {
            return new XSSFWorkbook(in);
        }
    }

    @Test
    void initCreatesFileWithSheetsHeadersAndSamples() throws Exception {
        assertThat(file).exists();
        try (Workbook wb = open()) {
            assertThat(wb.getSheetName(0)).isEqualTo("Записи");
            assertThat(wb.getSheetName(1)).isEqualTo("Услуги");
            assertThat(wb.getSheetName(2)).isEqualTo("Мастера");

            Sheet bookings = wb.getSheet("Записи");
            Row header = bookings.getRow(0);
            assertThat(header.getCell(0).getStringCellValue()).isEqualTo("ID");
            assertThat(header.getCell(12).getStringCellValue()).isEqualTo("Напоминание отправлено");
            assertThat(wb.getFontAt(header.getCell(0).getCellStyle().getFontIndex()).getBold()).isTrue();
            assertThat(bookings.getPaneInformation().isFreezePane()).isTrue();
            assertThat(bookings.getColumnWidth(12)).isGreaterThan(bookings.getColumnWidth(0));

            assertThat(wb.getSheet("Услуги").getLastRowNum()).isEqualTo(4);
            assertThat(wb.getSheet("Мастера").getLastRowNum()).isEqualTo(3);
        }

        assertThat(repository.getServices()).extracting(Service::getName)
                .containsExactly("Стрижка мужская", "Стрижка женская", "Окрашивание", "Борода");
        Master anna = repository.getMasters().get(0);
        assertThat(anna.getWorkDays()).containsExactlyInAnyOrder(DayOfWeek.MONDAY, DayOfWeek.TUESDAY,
                DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY);
        assertThat(anna.getWorkStart()).isEqualTo(LocalTime.of(10, 0));
        assertThat(anna.isActive()).isTrue();
    }

    @Test
    void initDoesNotOverwriteExistingFile() {
        long id = repository.addBooking(booking(1, "Анна", "10:00"));
        repository.init();
        assertThat(repository.getUserBookings(1)).extracting(Booking::getId).containsExactly(id);
    }

    @Test
    void addBookingWritesRealExcelDatesAndReturnsSequentialIds() throws Exception {
        long first = repository.addBooking(booking(1, "Анна", "10:00"));
        long second = repository.addBooking(booking(2, "Анна", "11:00"));

        assertThat(first).isEqualTo(1);
        assertThat(second).isEqualTo(2);

        try (Workbook wb = open()) {
            Row row = wb.getSheet("Записи").getRow(1);
            DataFormatter formatter = new DataFormatter();
            assertThat(row.getCell(8).getCellStyle().getDataFormatString()).isEqualTo("dd.mm.yyyy");
            assertThat(row.getCell(9).getCellStyle().getDataFormatString()).isEqualTo("hh:mm");
            assertThat(formatter.formatCellValue(row.getCell(8))).isEqualTo("22.10.2026");
            assertThat(formatter.formatCellValue(row.getCell(9))).isEqualTo("10:00");
            assertThat(row.getCell(10).getStringCellValue()).isEqualTo("salon_1");
            assertThat(row.getCell(11).getStringCellValue()).isEqualTo("Подтверждена");
            assertThat(row.getCell(12).getStringCellValue()).isEqualTo("нет");
        }

        Booking saved = repository.getBookings("Анна", DATE).get(0);
        assertThat(saved.getTime()).isEqualTo(LocalTime.of(10, 0));
        assertThat(saved.getDurationMinutes()).isEqualTo(45);
        assertThat(saved.getCreatedAt()).isEqualTo(LocalDateTime.of(2026, 10, 21, 9, 0));
    }

    @Test
    void cancelBookingSetsStatusOnlyOnce() {
        long id = repository.addBooking(booking(1, "Анна", "10:00"));

        assertThat(repository.cancelBooking(id)).hasValueSatisfying(b ->
                assertThat(b.getStatus()).isEqualTo(BookingStatus.CANCELLED));
        assertThat(repository.cancelBooking(id)).isEmpty();
        assertThat(repository.cancelBooking(999)).isEmpty();
        assertThat(repository.getUserBookings(1).get(0).getStatus()).isEqualTo(BookingStatus.CANCELLED);
    }

    @Test
    void getUserBookingsReturnsOnlyThatUser() {
        repository.addBooking(booking(1, "Анна", "12:00"));
        repository.addBooking(booking(2, "Арам", "11:00"));
        repository.addBooking(booking(1, "Арам", "10:00"));

        assertThat(repository.getUserBookings(1)).extracting(Booking::getTime)
                .containsExactly(LocalTime.of(10, 0), LocalTime.of(12, 0));
        assertThat(repository.getUserBookings(3)).isEmpty();
        assertThat(repository.getBookings("Арам", DATE)).hasSize(2);
        assertThat(repository.getBookings(DATE)).hasSize(3);
    }

    @Test
    void getAllBookingsReturnsEverythingIncludingCancelled() {
        repository.addBooking(booking(1, "Анна", "12:00"));
        long cancelled = repository.addBooking(booking(2, "Арам", "11:00"));
        repository.cancelBooking(cancelled);

        assertThat(repository.getAllBookings()).extracting(Booking::getTime)
                .containsExactly(LocalTime.of(11, 0), LocalTime.of(12, 0));
    }

    /** Бот и настольное приложение — разные процессы; ни одна запись не должна потеряться. */
    @Test
    void concurrentWritersInSeparateProcessesDoNotLoseBookings() throws Exception {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        List<Process> processes = new ArrayList<>();
        for (int p = 0; p < 2; p++) {
            processes.add(new ProcessBuilder(java, "-cp", System.getProperty("java.class.path"),
                    ConcurrentWriterMain.class.getName(), file.toString(), String.valueOf(100 + p), "8")
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .start());
        }
        for (int i = 0; i < 8; i++) {
            repository.addBooking(booking(1, "Анна", "10:00"));
        }
        for (Process process : processes) {
            assertThat(process.waitFor(60, TimeUnit.SECONDS)).isTrue();
            assertThat(process.exitValue()).isZero();
        }

        List<Booking> all = repository.getAllBookings();
        assertThat(all).hasSize(24);
        assertThat(all).extracting(Booking::getId).doesNotHaveDuplicates();
        assertThat(all).filteredOn(b -> b.getTelegramId() == 100).hasSize(8);
        assertThat(all).filteredOn(b -> b.getTelegramId() == 101).hasSize(8);
    }

    @Test
    void markReminderSent() {
        long id = repository.addBooking(booking(1, "Анна", "10:00"));
        repository.markReminderSent(id);
        assertThat(repository.getUserBookings(1).get(0).isReminderSent()).isTrue();
    }

    @Test
    void keepsLast20Backups() throws Exception {
        for (int i = 0; i < 25; i++) {
            repository.addBooking(booking(1, "Анна", "10:00"));
        }
        try (Stream<Path> backups = Files.list(dir.resolve("backups"))) {
            assertThat(backups.count()).isEqualTo(20);
        }
        try (Stream<Path> files = Files.list(dir)) {
            assertThat(files.map(p -> p.getFileName().toString())).noneMatch(n -> n.endsWith(".tmp"));
        }
    }

    @Test
    void busyFileFailsWithStorageBusyException() {
        assumeTrue(dir.toFile().setWritable(false), "нельзя сделать папку только для чтения");
        try {
            assumeTrue(!Files.isWritable(dir), "процесс запущен с правами администратора");
            assertThatThrownBy(() -> repository.addBooking(booking(1, "Анна", "10:00")))
                    .isInstanceOf(StorageBusyException.class);
        } finally {
            dir.toFile().setWritable(true);
        }
    }

    @Test
    void exportReturnsFileContent() throws Exception {
        repository.addBooking(booking(1, "Анна", "10:00"));
        ExportedFile export = repository.export();
        assertThat(export.fileName()).isEqualTo("salon_bookings.xlsx");
        assertThat(export.content()).isEqualTo(Files.readAllBytes(file));
    }
}
