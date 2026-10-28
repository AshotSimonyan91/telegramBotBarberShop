package com.salon.bot.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.salon.bot.BotApplication;
import com.salon.bot.model.Booking;
import com.salon.bot.model.BookingStatus;
import com.salon.bot.model.Master;
import com.salon.bot.service.BookingService;
import com.salon.bot.storage.BookingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.telegram.telegrambots.longpolling.TelegramBotsLongPollingApplication;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpHeaders;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import static org.mockito.Mockito.when;

/**
 * Общая основа интеграционных тестов REST API: настоящий Spring-контекст на случайном порту,
 * Excel во временной папке, Telegram и long polling заменены заглушками, время управляется.
 * <p>
 * .env не читается ({@code BotApplication.loadDotenv} вызывается только из main), токен бота — заглушка.
 */
@SpringBootTest(classes = BotApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "bot.token=1:F",
                "bot.username=TestSalonBot",
                "bot.timezone=Asia/Yerevan",
                "bot.admin-chat-id=",
                "api.enabled=true",
                "api.token=" + ApiTestBase.TOKEN,
                "server.port=0"
        })
@Import(ApiTestBase.ClockConfig.class)
public abstract class ApiTestBase {

    public static final String TOKEN = "test-token-0123456789abcdef";
    public static final ZoneId ZONE = ZoneId.of("Asia/Yerevan");
    /** Среда, 21.10.2026 09:00. */
    public static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 21, 9, 0);
    public static final LocalDate TODAY = NOW.toLocalDate();
    public static final LocalDate THURSDAY = TODAY.plusDays(1);
    public static final LocalDate SATURDAY = TODAY.plusDays(3);
    public static final LocalDate SUNDAY = TODAY.plusDays(4);

    /** Папка с Excel-файлом: общая на все тесты JVM (один кэшируемый контекст), в корень проекта не пишет. */
    static final Path DATA_DIR;

    static {
        try {
            DATA_DIR = Files.createTempDirectory("salon-api-test-");
        } catch (IOException e) {
            throw new ExceptionInInitializerError(e);
        }
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try (Stream<Path> walk = Files.walk(DATA_DIR)) {
                walk.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            } catch (IOException ignored) {
                // временная папка
            }
        }));
    }

    static Path excelFile() {
        return DATA_DIR.resolve("salon_bookings.xlsx");
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("bot.excel-path", () -> excelFile().toString());
    }

    @TestConfiguration
    static class ClockConfig {
        @Bean
        @Primary
        MutableClock testClock() {
            return new MutableClock(ZONE, NOW);
        }
    }

    @MockitoBean
    protected TelegramClient telegramClient;
    @MockitoBean
    protected TelegramBotsLongPollingApplication longPolling;

    @LocalServerPort
    protected int port;
    @Autowired
    protected MutableClock clock;
    @Autowired
    protected BookingRepository repository;
    @Autowired
    protected BookingService bookingService;

    protected final ObjectMapper json = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();

    @BeforeEach
    void resetData() throws IOException {
        clock.set(NOW);
        Files.deleteIfExists(excelFile());
        repository.init();
        when(longPolling.isRunning()).thenReturn(true);
    }

    // ------------------------------------------------------------ HTTP

    public record Resp(int status, HttpHeaders headers, String body, ObjectMapper mapper) {
        public JsonNode json() {
            try {
                return mapper.readTree(body);
            } catch (IOException e) {
                throw new AssertionError("Ответ не JSON: " + body, e);
            }
        }

        public String header(String name) {
            return headers.firstValue(name).orElse(null);
        }
    }

    /** Запрос с токеном. */
    protected Resp get(String path) {
        return call("GET", path, TOKEN, null);
    }

    protected Resp post(String path, String body) {
        return call("POST", path, TOKEN, body, "Content-Type", "application/json");
    }

    protected Resp call(String method, String path, String token, String body, String... headers) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path.replace(" ", "%20")));
            if (token != null) {
                builder.header("Authorization", "Bearer " + token);
            }
            for (int i = 0; i < headers.length; i += 2) {
                builder.header(headers[i], headers[i + 1]);
            }
            builder.method(method, body == null
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(body));
            HttpResponse<String> response = http.send(builder.build(),
                    HttpResponse.BodyHandlers.ofString());
            return new Resp(response.statusCode(), response.headers(), response.body(), json);
        } catch (IOException | InterruptedException e) {
            throw new AssertionError("HTTP-запрос не выполнен: " + method + " " + path, e);
        }
    }

    // ------------------------------------------------------------ данные

    protected static String bookingJson(String name, String phone, String service, String master,
                                        LocalDate date, String time) {
        return "{" + field("clientName", name) + "," + field("phone", phone) + ","
                + field("serviceName", service) + "," + field("masterName", master) + ","
                + field("date", date == null ? null : date.toString()) + "," + field("time", time) + "}";
    }

    protected static String field(String key, String value) {
        return "\"" + key + "\":" + (value == null ? "null" : "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"");
    }

    protected Resp book(String service, String master, LocalDate date, String time) {
        return post("/api/v1/bookings", bookingJson("Иван Петров", "+37491123456", service, master, date, time));
    }

    protected Master master(String name) {
        return bookingService.findActiveMaster(name).orElseThrow();
    }

    /** Запись напрямую через сервис бота (с Telegram ID и username). */
    protected Booking seed(String client, String phone, String service, String master, LocalDate date, String time,
                           long telegramId, String username) {
        int duration = bookingService.findService(service).orElseThrow().getDurationMinutes();
        Booking draft = Booking.builder()
                .clientName(client).telegramId(telegramId).username(username).phone(phone)
                .serviceName(service).date(date).time(LocalTime.parse(time)).source("start_test")
                .durationMinutes(duration).build();
        return bookingService.book(draft, master(master));
    }

    protected Booking seedAndCancel(String client, String service, String master, LocalDate date, String time) {
        Booking b = seed(client, "+37400000000", service, master, date, time, 5, "u");
        repository.cancelBooking(b.getId());
        return b.withStatus(BookingStatus.CANCELLED);
    }

    protected List<Booking> allBookings() {
        return repository.getAllBookings();
    }
}
