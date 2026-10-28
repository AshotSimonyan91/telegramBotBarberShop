package com.salon.bot.storage;

import com.salon.bot.model.Booking;
import com.salon.bot.model.BookingStatus;
import com.salon.bot.model.Master;
import com.salon.bot.model.Service;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Хранение данных в xlsx-файле.
 * <p>
 * Все операции идут через один {@link ReentrantLock} (общий для всех экземпляров с этим файлом).
 * Каждое изменение: чтение файла, резервная копия в {@code backups/}, запись во временный файл рядом
 * и атомарная замена основного. Изменения дополнительно защищены блокировкой lock-файла рядом с xlsx,
 * чтобы бот и настольное приложение (разные процессы) не затирали записи друг друга.
 * Если файл занят (открыт в Excel), операция повторяется несколько раз, затем бросается
 * {@link StorageBusyException}.
 */
@Slf4j
public class ExcelBookingRepository implements BookingRepository {

    static final String BOOKINGS_SHEET = "Записи";
    static final String SERVICES_SHEET = "Услуги";
    static final String MASTERS_SHEET = "Мастера";

    private static final String[] BOOKING_HEADERS = {
            "ID", "Дата создания", "Клиент", "Telegram ID", "Username", "Телефон", "Услуга",
            "Мастер", "Дата", "Время", "Источник", "Статус", "Напоминание отправлено"
    };
    private static final String[] SERVICE_HEADERS = {"Название", "Длительность (мин)", "Цена"};
    private static final String[] MASTER_HEADERS = {
            "Имя", "Рабочие дни (Пн,Вт,...)", "Начало", "Конец", "Активен (да/нет)"
    };

    private static final int COL_ID = 0;
    private static final int COL_CREATED = 1;
    private static final int COL_CLIENT = 2;
    private static final int COL_TELEGRAM_ID = 3;
    private static final int COL_USERNAME = 4;
    private static final int COL_PHONE = 5;
    private static final int COL_SERVICE = 6;
    private static final int COL_MASTER = 7;
    private static final int COL_DATE = 8;
    private static final int COL_TIME = 9;
    private static final int COL_SOURCE = 10;
    private static final int COL_STATUS = 11;
    private static final int COL_REMINDER = 12;

    private static final String FORMAT_DATE = "dd.mm.yyyy";
    private static final String FORMAT_TIME = "hh:mm";
    private static final String FORMAT_DATE_TIME = "dd.mm.yyyy hh:mm";
    private static final String FORMAT_INTEGER = "0";

    private static final DateTimeFormatter TEXT_DATE = DateTimeFormatter.ofPattern("d.M.yyyy");
    private static final DateTimeFormatter TEXT_DATE_TIME = DateTimeFormatter.ofPattern("d.M.yyyy H:mm");
    private static final DateTimeFormatter TEXT_TIME = DateTimeFormatter.ofPattern("H:mm");
    private static final DateTimeFormatter BACKUP_STAMP = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS");

    private static final Map<String, DayOfWeek> DAY_NAMES = new LinkedHashMap<>();

    static {
        String[] names = {"Пн", "Вт", "Ср", "Чт", "Пт", "Сб", "Вс"};
        for (int i = 0; i < names.length; i++) {
            DAY_NAMES.put(names[i].toLowerCase(), DayOfWeek.of(i + 1));
        }
    }

    private static final int MAX_BACKUPS = 20;
    private static final Duration CACHE_TTL = Duration.ofMinutes(1);
    private static final int DEFAULT_DURATION_MINUTES = 60;

    /** Блокировка файла ОС действует на весь процесс, поэтому потоки одного процесса делят один lock. */
    private static final Map<Path, ReentrantLock> LOCKS = new ConcurrentHashMap<>();

    private final Path file;
    private final Path backupDir;
    private final Path lockFile;
    private final Clock clock;
    private final int retries;
    private final Duration retryDelay;
    private final ReentrantLock lock;
    private final DataFormatter formatter = new DataFormatter();

    private List<Service> servicesCache;
    private List<Master> mastersCache;
    private Instant cacheLoadedAt;

    public ExcelBookingRepository(Path file, Clock clock) {
        this(file, clock, 3, Duration.ofSeconds(2));
    }

    public ExcelBookingRepository(Path file, Clock clock, int retries, Duration retryDelay) {
        this.file = file.toAbsolutePath();
        this.backupDir = this.file.getParent().resolve("backups");
        this.lockFile = this.file.getParent().resolve("." + this.file.getFileName() + ".lock");
        this.lock = LOCKS.computeIfAbsent(this.file, f -> new ReentrantLock());
        this.clock = clock;
        this.retries = retries;
        this.retryDelay = retryDelay;
    }

    public Path getFile() {
        return file;
    }

    // ---------------------------------------------------------------- API

    @Override
    public void init() {
        lock.lock();
        try {
            if (!Files.exists(file)) {
                retrying("создать", () -> {
                    Files.createDirectories(file.getParent());
                    return null;
                });
                withFileLock(() -> retrying("создать", () -> {
                    if (Files.exists(file)) {
                        return null;
                    }
                    try (Workbook workbook = new XSSFWorkbook()) {
                        createBookingsSheet(workbook);
                        createServicesSheet(workbook);
                        createMastersSheet(workbook);
                        writeAtomically(workbook);
                    }
                    return null;
                }));
                log.info("Создан Excel-файл {}", file);
            } else {
                modifyIf(this::addMissingSheets);
                log.info("Используется Excel-файл {}", file);
            }
            invalidateCache();
        } finally {
            lock.unlock();
        }
    }

    @Override
    public List<Service> getServices() {
        lock.lock();
        try {
            refreshCacheIfStale();
            return servicesCache;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public List<Master> getMasters() {
        lock.lock();
        try {
            refreshCacheIfStale();
            return mastersCache;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public List<Booking> getBookings(String masterName, LocalDate date) {
        return readBookings(b -> b.getMasterName().equalsIgnoreCase(masterName) && b.getDate().equals(date));
    }

    @Override
    public List<Booking> getBookings(LocalDate date) {
        return readBookings(b -> b.getDate().equals(date));
    }

    @Override
    public List<Booking> getAllBookings() {
        return readBookings(b -> true);
    }

    @Override
    public long addBooking(Booking booking) {
        lock.lock();
        try {
            return modify(workbook -> {
                Sheet sheet = workbook.getSheet(BOOKINGS_SHEET);
                long id = nextId(sheet);
                writeBookingRow(workbook, sheet.createRow(sheet.getLastRowNum() + 1), booking.withId(id));
                return id;
            });
        } finally {
            lock.unlock();
        }
    }

    @Override
    public List<Booking> getUserBookings(long telegramId) {
        return readBookings(b -> b.getTelegramId() == telegramId);
    }

    @Override
    public Optional<Booking> cancelBooking(long bookingId) {
        lock.lock();
        try {
            Optional<Booking> current = readBookings(b -> b.getId() == bookingId).stream().findFirst();
            if (current.isEmpty() || !current.get().isActive()) {
                return Optional.empty();
            }
            modify(workbook -> {
                findRow(workbook, bookingId).ifPresent(row ->
                        row.createCell(COL_STATUS).setCellValue(BookingStatus.CANCELLED.title()));
                return null;
            });
            return Optional.of(current.get().withStatus(BookingStatus.CANCELLED));
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void markReminderSent(long bookingId) {
        lock.lock();
        try {
            modify(workbook -> {
                findRow(workbook, bookingId).ifPresent(row -> row.createCell(COL_REMINDER).setCellValue("да"));
                return null;
            });
        } finally {
            lock.unlock();
        }
    }

    @Override
    public ExportedFile export() {
        lock.lock();
        try {
            byte[] content = retrying("прочитать", () -> Files.readAllBytes(file));
            return new ExportedFile(file.getFileName().toString(), content);
        } finally {
            lock.unlock();
        }
    }

    // ---------------------------------------------------------------- чтение

    private List<Booking> readBookings(java.util.function.Predicate<Booking> filter) {
        lock.lock();
        try {
            Map<String, Integer> durations = getServices().stream()
                    .collect(Collectors.toMap(s -> s.getName().toLowerCase(), Service::getDurationMinutes, (a, b) -> a));
            return read(workbook -> {
                Sheet sheet = workbook.getSheet(BOOKINGS_SHEET);
                List<Booking> result = new ArrayList<>();
                for (int i = 1; sheet != null && i <= sheet.getLastRowNum(); i++) {
                    Row row = sheet.getRow(i);
                    if (row == null || text(row, COL_ID).isEmpty()) {
                        continue;
                    }
                    try {
                        Booking booking = toBooking(row, durations);
                        if (filter.test(booking)) {
                            result.add(booking);
                        }
                    } catch (RuntimeException e) {
                        log.warn("Пропущена некорректная строка {} на листе «{}»: {}", i + 1, BOOKINGS_SHEET, e.getMessage());
                    }
                }
                result.sort(Comparator.comparing(Booking::start));
                return result;
            });
        } finally {
            lock.unlock();
        }
    }

    private Booking toBooking(Row row, Map<String, Integer> durations) {
        String serviceName = text(row, COL_SERVICE);
        return Booking.builder()
                .id((long) number(row, COL_ID))
                .createdAt(dateTime(row, COL_CREATED))
                .clientName(text(row, COL_CLIENT))
                .telegramId((long) number(row, COL_TELEGRAM_ID))
                .username(text(row, COL_USERNAME))
                .phone(text(row, COL_PHONE))
                .serviceName(serviceName)
                .masterName(text(row, COL_MASTER))
                .date(date(row, COL_DATE))
                .time(time(row, COL_TIME))
                .source(text(row, COL_SOURCE))
                .status(BookingStatus.fromTitle(text(row, COL_STATUS)))
                .reminderSent(yes(text(row, COL_REMINDER)))
                .durationMinutes(durations.getOrDefault(serviceName.toLowerCase(), DEFAULT_DURATION_MINUTES))
                .build();
    }

    private void refreshCacheIfStale() {
        Instant now = clock.instant();
        if (cacheLoadedAt != null && Duration.between(cacheLoadedAt, now).compareTo(CACHE_TTL) < 0) {
            return;
        }
        read(workbook -> {
            servicesCache = List.copyOf(readServices(workbook.getSheet(SERVICES_SHEET)));
            mastersCache = List.copyOf(readMasters(workbook.getSheet(MASTERS_SHEET)));
            return null;
        });
        cacheLoadedAt = now;
    }

    private void invalidateCache() {
        cacheLoadedAt = null;
    }

    private List<Service> readServices(Sheet sheet) {
        List<Service> result = new ArrayList<>();
        for (int i = 1; sheet != null && i <= sheet.getLastRowNum(); i++) {
            Row row = sheet.getRow(i);
            if (row == null || text(row, 0).isEmpty()) {
                continue;
            }
            try {
                result.add(Service.builder()
                        .name(text(row, 0))
                        .durationMinutes((int) number(row, 1))
                        .price((int) number(row, 2))
                        .build());
            } catch (RuntimeException e) {
                log.warn("Пропущена некорректная строка {} на листе «{}»: {}", i + 1, SERVICES_SHEET, e.getMessage());
            }
        }
        return result;
    }

    private List<Master> readMasters(Sheet sheet) {
        List<Master> result = new ArrayList<>();
        for (int i = 1; sheet != null && i <= sheet.getLastRowNum(); i++) {
            Row row = sheet.getRow(i);
            if (row == null || text(row, 0).isEmpty()) {
                continue;
            }
            try {
                result.add(Master.builder()
                        .name(text(row, 0))
                        .workDays(parseDays(text(row, 1)))
                        .workStart(time(row, 2))
                        .workEnd(time(row, 3))
                        .active(yes(text(row, 4)))
                        .build());
            } catch (RuntimeException e) {
                log.warn("Пропущена некорректная строка {} на листе «{}»: {}", i + 1, MASTERS_SHEET, e.getMessage());
            }
        }
        return result;
    }

    static Set<DayOfWeek> parseDays(String value) {
        Set<DayOfWeek> days = EnumSet.noneOf(DayOfWeek.class);
        for (String part : value.split("[,;\\s]+")) {
            DayOfWeek day = DAY_NAMES.get(part.trim().toLowerCase());
            if (day != null) {
                days.add(day);
            }
        }
        return days;
    }

    static String formatDays(Set<DayOfWeek> days) {
        return DAY_NAMES.entrySet().stream()
                .filter(e -> days.contains(e.getValue()))
                .map(e -> Character.toUpperCase(e.getKey().charAt(0)) + e.getKey().substring(1))
                .collect(Collectors.joining(","));
    }

    // ---------------------------------------------------------------- запись

    private void writeBookingRow(Workbook workbook, Row row, Booking b) {
        number(workbook, row, COL_ID, b.getId());
        dateCell(workbook, row, COL_CREATED, DateUtil.getExcelDate(b.getCreatedAt()), FORMAT_DATE_TIME);
        row.createCell(COL_CLIENT).setCellValue(b.getClientName());
        number(workbook, row, COL_TELEGRAM_ID, b.getTelegramId());
        row.createCell(COL_USERNAME).setCellValue(b.getUsername() == null ? "" : b.getUsername());
        row.createCell(COL_PHONE).setCellValue(b.getPhone());
        row.createCell(COL_SERVICE).setCellValue(b.getServiceName());
        row.createCell(COL_MASTER).setCellValue(b.getMasterName());
        dateCell(workbook, row, COL_DATE, DateUtil.getExcelDate(b.getDate()), FORMAT_DATE);
        dateCell(workbook, row, COL_TIME, excelTime(b.getTime()), FORMAT_TIME);
        row.createCell(COL_SOURCE).setCellValue(b.getSource());
        row.createCell(COL_STATUS).setCellValue(b.getStatus().title());
        row.createCell(COL_REMINDER).setCellValue(b.isReminderSent() ? "да" : "нет");
    }

    private void createBookingsSheet(Workbook workbook) {
        Sheet sheet = workbook.createSheet(BOOKINGS_SHEET);
        header(workbook, sheet, BOOKING_HEADERS);
        autosize(sheet, BOOKING_HEADERS.length);
    }

    private void createServicesSheet(Workbook workbook) {
        Sheet sheet = workbook.createSheet(SERVICES_SHEET);
        header(workbook, sheet, SERVICE_HEADERS);
        List<Service> samples = List.of(
                Service.builder().name("Стрижка мужская").durationMinutes(45).price(5000).build(),
                Service.builder().name("Стрижка женская").durationMinutes(60).price(8000).build(),
                Service.builder().name("Окрашивание").durationMinutes(120).price(20000).build(),
                Service.builder().name("Борода").durationMinutes(30).price(3000).build());
        int r = 1;
        for (Service s : samples) {
            Row row = sheet.createRow(r++);
            row.createCell(0).setCellValue(s.getName());
            number(workbook, row, 1, s.getDurationMinutes());
            number(workbook, row, 2, s.getPrice());
        }
        autosize(sheet, SERVICE_HEADERS.length);
    }

    private void createMastersSheet(Workbook workbook) {
        Sheet sheet = workbook.createSheet(MASTERS_SHEET);
        header(workbook, sheet, MASTER_HEADERS);
        Object[][] samples = {
                {"Анна", "Пн,Вт,Ср,Чт,Пт", LocalTime.of(10, 0), LocalTime.of(19, 0)},
                {"Арам", "Вт,Ср,Чт,Пт,Сб", LocalTime.of(11, 0), LocalTime.of(20, 0)},
                {"Мариам", "Пн,Ср,Пт,Сб", LocalTime.of(9, 0), LocalTime.of(18, 0)},
        };
        int r = 1;
        for (Object[] m : samples) {
            Row row = sheet.createRow(r++);
            row.createCell(0).setCellValue((String) m[0]);
            row.createCell(1).setCellValue((String) m[1]);
            dateCell(workbook, row, 2, excelTime((LocalTime) m[2]), FORMAT_TIME);
            dateCell(workbook, row, 3, excelTime((LocalTime) m[3]), FORMAT_TIME);
            row.createCell(4).setCellValue("да");
        }
        autosize(sheet, MASTER_HEADERS.length);
    }

    private boolean addMissingSheets(Workbook workbook) {
        boolean changed = false;
        if (workbook.getSheet(BOOKINGS_SHEET) == null) {
            createBookingsSheet(workbook);
            changed = true;
        }
        if (workbook.getSheet(SERVICES_SHEET) == null) {
            createServicesSheet(workbook);
            changed = true;
        }
        if (workbook.getSheet(MASTERS_SHEET) == null) {
            createMastersSheet(workbook);
            changed = true;
        }
        return changed;
    }

    private void header(Workbook workbook, Sheet sheet, String[] headers) {
        CellStyle style = workbook.createCellStyle();
        Font bold = workbook.createFont();
        bold.setBold(true);
        style.setFont(bold);
        Row row = sheet.createRow(0);
        for (int i = 0; i < headers.length; i++) {
            Cell cell = row.createCell(i);
            cell.setCellValue(headers[i]);
            cell.setCellStyle(style);
        }
        sheet.createFreezePane(0, 1);
    }

    private void autosize(Sheet sheet, int columns) {
        for (int i = 0; i < columns; i++) {
            sheet.autoSizeColumn(i);
            sheet.setColumnWidth(i, Math.min(sheet.getColumnWidth(i) + 3 * 256, 60 * 256));
        }
    }

    private Optional<Row> findRow(Workbook workbook, long bookingId) {
        Sheet sheet = workbook.getSheet(BOOKINGS_SHEET);
        for (int i = 1; i <= sheet.getLastRowNum(); i++) {
            Row row = sheet.getRow(i);
            if (row != null && !text(row, COL_ID).isEmpty() && (long) number(row, COL_ID) == bookingId) {
                return Optional.of(row);
            }
        }
        return Optional.empty();
    }

    private long nextId(Sheet sheet) {
        long max = 0;
        for (int i = 1; i <= sheet.getLastRowNum(); i++) {
            Row row = sheet.getRow(i);
            if (row != null && !text(row, COL_ID).isEmpty()) {
                try {
                    max = Math.max(max, (long) number(row, COL_ID));
                } catch (RuntimeException ignored) {
                    // нечисловой ID, введённый вручную
                }
            }
        }
        return max + 1;
    }

    // ---------------------------------------------------------------- файл

    private <T> T read(Function<Workbook, T> reader) {
        return retrying("прочитать", () -> {
            byte[] bytes = Files.readAllBytes(file);
            try (Workbook workbook = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
                return reader.apply(workbook);
            }
        });
    }

    private <T> T modify(Function<Workbook, T> change) {
        return withFileLock(() -> retrying("изменить", () -> {
            byte[] bytes = Files.readAllBytes(file);
            try (Workbook workbook = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
                T result = change.apply(workbook);
                backup();
                writeAtomically(workbook);
                return result;
            }
        }));
    }

    private void modifyIf(Function<Workbook, Boolean> change) {
        withFileLock(() -> retrying("изменить", () -> {
            byte[] bytes = Files.readAllBytes(file);
            try (Workbook workbook = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
                if (change.apply(workbook)) {
                    backup();
                    writeAtomically(workbook);
                }
                return null;
            }
        }));
    }

    /**
     * Выполняет операцию под блокировкой lock-файла: ждёт, пока другой процесс закончит своё изменение.
     * Вызывать только под {@link #lock}.
     */
    private <T> T withFileLock(java.util.function.Supplier<T> op) {
        try (FileChannel channel = FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             FileLock ignored = channel.lock()) {
            return op.get();
        } catch (IOException e) {
            throw new StorageException("Не удалось заблокировать файл " + lockFile + ": " + e.getMessage(), e);
        }
    }

    private void writeAtomically(Workbook workbook) throws IOException {
        Path tmp = Files.createTempFile(file.getParent(), file.getFileName() + ".", ".tmp");
        try {
            try (OutputStream out = Files.newOutputStream(tmp)) {
                workbook.write(out);
            }
            try {
                Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    private void backup() throws IOException {
        if (!Files.exists(file)) {
            return;
        }
        Files.createDirectories(backupDir);
        String name = file.getFileName().toString();
        String base = name.endsWith(".xlsx") ? name.substring(0, name.length() - 5) : name;
        String stamp = LocalDateTime.now(clock).format(BACKUP_STAMP);
        Path target = backupDir.resolve(base + "_" + stamp + ".xlsx");
        for (int n = 1; Files.exists(target); n++) {
            target = backupDir.resolve(base + "_" + stamp + "_" + n + ".xlsx");
        }
        Files.copy(file, target);

        try (Stream<Path> files = Files.list(backupDir)) {
            List<Path> backups = files
                    .filter(p -> p.getFileName().toString().startsWith(base + "_"))
                    .sorted(Comparator.comparing((Path p) -> p.toFile().lastModified())
                            .thenComparing(p -> p.getFileName().toString()))
                    .toList();
            for (int i = 0; i < backups.size() - MAX_BACKUPS; i++) {
                Files.deleteIfExists(backups.get(i));
            }
        }
    }

    @FunctionalInterface
    private interface IoAction<T> {
        T run() throws IOException;
    }

    /**
     * Выполняет операцию с файлом; при ошибке ввода-вывода (файл занят) повторяет {@code retries} раз.
     */
    private <T> T retrying(String action, IoAction<T> op) {
        IOException last = null;
        for (int attempt = 0; attempt <= retries; attempt++) {
            if (attempt > 0) {
                log.warn("Не удалось {} файл {} ({}). Повтор {}/{} через {} с",
                        action, file, last.getMessage(), attempt, retries, retryDelay.toSeconds());
                sleep();
            }
            try {
                return op.run();
            } catch (IOException e) {
                last = e;
            } catch (RuntimeException e) {
                throw new StorageException("Ошибка обработки Excel-файла " + file + ": " + e.getMessage(), e);
            }
        }
        log.error("Не удалось {} Excel-файл {}: файл занят другой программой (вероятно, открыт в Excel). "
                + "Закройте файл.", action, file, last);
        throw new StorageBusyException("Excel-файл занят: " + file, last);
    }

    private void sleep() {
        try {
            Thread.sleep(retryDelay);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new StorageException("Операция с файлом прервана", e);
        }
    }

    // ---------------------------------------------------------------- ячейки

    private String text(Row row, int col) {
        Cell cell = row.getCell(col);
        return cell == null ? "" : formatter.formatCellValue(cell).trim();
    }

    private double number(Row row, int col) {
        Cell cell = row.getCell(col);
        if (cell != null && cell.getCellType() == CellType.NUMERIC) {
            return cell.getNumericCellValue();
        }
        return Double.parseDouble(text(row, col).replace(',', '.').replaceAll("\\s", ""));
    }

    private LocalDate date(Row row, int col) {
        Cell cell = row.getCell(col);
        if (cell != null && cell.getCellType() == CellType.NUMERIC) {
            return DateUtil.getLocalDateTime(cell.getNumericCellValue()).toLocalDate();
        }
        return LocalDate.parse(text(row, col), TEXT_DATE);
    }

    private LocalTime time(Row row, int col) {
        Cell cell = row.getCell(col);
        if (cell != null && cell.getCellType() == CellType.NUMERIC) {
            double value = cell.getNumericCellValue();
            long seconds = Math.round((value - Math.floor(value)) * 86_400) % 86_400;
            return LocalTime.ofSecondOfDay(seconds);
        }
        return LocalTime.parse(text(row, col), TEXT_TIME);
    }

    private LocalDateTime dateTime(Row row, int col) {
        Cell cell = row.getCell(col);
        if (cell != null && cell.getCellType() == CellType.NUMERIC) {
            return DateUtil.getLocalDateTime(cell.getNumericCellValue());
        }
        String value = text(row, col);
        return value.isEmpty() ? null : LocalDateTime.parse(value, TEXT_DATE_TIME);
    }

    private static boolean yes(String value) {
        String v = value.trim().toLowerCase();
        return v.equals("да") || v.equals("yes") || v.equals("1") || v.equals("true");
    }

    private static double excelTime(LocalTime time) {
        return time.toSecondOfDay() / 86_400.0;
    }

    private void number(Workbook workbook, Row row, int col, double value) {
        Cell cell = row.createCell(col);
        cell.setCellValue(value);
        cell.setCellStyle(style(workbook, FORMAT_INTEGER));
    }

    private void dateCell(Workbook workbook, Row row, int col, double excelValue, String format) {
        Cell cell = row.createCell(col);
        cell.setCellValue(excelValue);
        cell.setCellStyle(style(workbook, format));
    }

    /** Переиспользует стиль с нужным форматом, чтобы не плодить стили при каждой записи. */
    private CellStyle style(Workbook workbook, String format) {
        short formatIndex = workbook.createDataFormat().getFormat(format);
        for (int i = 0; i < workbook.getNumCellStyles(); i++) {
            CellStyle style = workbook.getCellStyleAt(i);
            if (style.getDataFormat() == formatIndex && style.getFontIndex() == 0) {
                return style;
            }
        }
        CellStyle style = workbook.createCellStyle();
        style.setDataFormat(formatIndex);
        return style;
    }
}
