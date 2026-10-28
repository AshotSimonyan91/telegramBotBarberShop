package com.salon.bot;

import com.salon.bot.bot.AdminNotifier;
import com.salon.bot.bot.Messenger;
import com.salon.bot.bot.SalonBot;
import com.salon.bot.bot.UpdateRouter;
import com.salon.bot.config.BotProperties;
import com.salon.bot.handler.AdminHandler;
import com.salon.bot.handler.BookingFlowHandler;
import com.salon.bot.handler.FallbackHandler;
import com.salon.bot.handler.HelpHandler;
import com.salon.bot.handler.MyBookingsHandler;
import com.salon.bot.handler.StartHandler;
import com.salon.bot.keyboard.KeyboardFactory;
import com.salon.bot.qr.QrGenerator;
import com.salon.bot.qr.QrGeneratorTest;
import com.salon.bot.service.BookingService;
import com.salon.bot.service.ReminderScheduler;
import com.salon.bot.service.SlotCalculator;
import com.salon.bot.state.SessionStorage;
import com.salon.bot.storage.ExcelBookingRepository;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.telegram.telegrambots.meta.api.methods.send.SendDocument;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.Contact;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.User;
import org.telegram.telegrambots.meta.api.objects.chat.Chat;
import org.telegram.telegrambots.meta.api.objects.message.Message;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboardMarkup;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.mock;

/**
 * Сквозная проверка сценария: Update → роутер → обработчики → настоящий Excel-файл.
 * Telegram API заменён заглушкой, которая запоминает отправленные методы.
 */
class BookingScenarioTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Yerevan");
    private static final long ADMIN = 999;
    /** Среда, 21.10.2026 09:00. */
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 21, 9, 0);

    @TempDir
    Path dir;

    private final List<Object> sent = new ArrayList<>();
    private final AtomicInteger messageIds = new AtomicInteger(100);
    private final AtomicInteger updateIds = new AtomicInteger();

    private Path excel;
    private ExcelBookingRepository repository;
    private Messenger messenger;
    private AdminNotifier adminNotifier;
    private SalonBot bot;

    private final User ivan = user(101, "Иван", "Петров", "ivan");
    private final User olga = user(102, "Ольга", null, null);
    private final User admin = user(ADMIN, "Админ", null, null);

    @BeforeEach
    void setUp() {
        TelegramClient client = mock(TelegramClient.class, invocation -> {
            if (!invocation.getMethod().getName().equals("execute")) {
                return null;
            }
            Object method = invocation.getArgument(0);
            sent.add(method);
            if (method instanceof SendMessage || method instanceof SendDocument) {
                Message m = new Message();
                m.setMessageId(messageIds.incrementAndGet());
                return m;
            }
            return null;
        });
        Clock clock = clockAt(NOW);
        excel = dir.resolve("salon_bookings.xlsx");
        repository = new ExcelBookingRepository(excel, clock, 1, Duration.ofMillis(10));

        BotProperties properties = new BotProperties("test-token", "AshotSimonyanBot", excel.toString(), ADMIN, "Asia/Yerevan");
        messenger = new Messenger(client);
        SessionStorage sessions = new SessionStorage();
        KeyboardFactory keyboards = new KeyboardFactory();
        BookingService bookingService = new BookingService(repository, new SlotCalculator(clock), clock);
        adminNotifier = new AdminNotifier(properties, messenger, clock);

        UpdateRouter router = new UpdateRouter(List.of(
                new StartHandler(messenger, sessions, keyboards),
                new HelpHandler(messenger, keyboards, adminNotifier),
                new AdminHandler(messenger, adminNotifier, bookingService, repository, clock),
                new MyBookingsHandler(messenger, keyboards, bookingService, adminNotifier),
                new BookingFlowHandler(messenger, sessions, keyboards, bookingService, adminNotifier),
                new FallbackHandler(messenger, keyboards)), messenger);
        bot = new SalonBot(properties, router, repository, messenger, adminNotifier);
        repository.init();
    }

    @Test
    void fullBookingThroughQrDeepLinkSavesSourceToExcel() throws Exception {
        Path qr = dir.resolve("qr_salon_1.png");
        QrGenerator.main(new String[]{"--source=salon_1", "--out=" + qr});
        String link = QrGeneratorTest.decode(qr);
        assertThat(link).isEqualTo("https://t.me/AshotSimonyanBot?start=salon_1");
        String payload = link.substring(link.indexOf("start=") + "start=".length());

        // Telegram при переходе по ссылке присылает «/start salon_1»
        text(ivan, "/start " + payload);
        assertThat(lastText()).contains("Добро пожаловать");
        assertThat(buttons(lastMarkup())).containsExactly("✂️ Записаться", "📋 Мои записи");

        click(ivan, "m:book");
        assertThat(last()).isInstanceOf(EditMessageText.class);
        assertThat(lastText()).contains("Шаг 1 из 7").contains("Стрижка мужская — 45 мин");
        assertThat(buttons(lastMarkup())).contains("Стрижка мужская — 5 000 ֏", "⬅️ Назад");

        click(ivan, "s:0");
        assertThat(lastText()).contains("Шаг 2 из 7");
        assertThat(buttons(lastMarkup())).containsExactly("Анна", "Арам", "Мариам", "🎲 Любой свободный", "⬅️ Назад");

        click(ivan, "w:0");
        assertThat(lastText()).contains("Шаг 3 из 7").contains("Мастер: Анна");
        // Анна работает Пн–Пт: из 14 дней с 21.10 — 10 рабочих
        assertThat(buttons(lastMarkup())).hasSize(11).contains("Чт 22.10").doesNotContain("Сб 24.10");

        click(ivan, "d:20261022");
        assertThat(lastText()).contains("Шаг 4 из 7").contains("22.10.2026 (чт)");
        assertThat(buttons(lastMarkup())).contains("10:00", "18:00").doesNotContain("18:30");

        click(ivan, "t:1000");
        assertThat(lastText()).contains("Шаг 5 из 7");
        assertThat(buttons(lastMarkup())).contains("👤 Иван Петров");

        click(ivan, "n:tg");
        List<Object> shown = shown();
        assertThat(shown.get(shown.size() - 2)).isInstanceOf(EditMessageText.class);
        assertThat(text(shown.get(shown.size() - 2))).contains("Шаг 6 из 7").contains("Имя: Иван Петров");
        assertThat(((SendMessage) last()).getReplyMarkup()).isInstanceOf(ReplyKeyboardMarkup.class);

        text(ivan, "123");
        assertThat(lastText()).contains("номер указан неверно");

        text(ivan, "+374 91 123-456");
        assertThat(lastText()).contains("Шаг 7 из 7").contains("Телефон: +37491123456").contains("Время: 10:00");

        click(ivan, "ok");
        assertThat(lastText()).contains("Вы записаны").contains("№1").contains("Мастер: Анна")
                .contains("22.10.2026").contains("10:00");
        assertThat(sentTo(ADMIN)).anySatisfy(t -> assertThat(t)
                .contains("Новая запись №1").contains("Иван Петров (@ivan)").contains("+37491123456")
                .contains("Стрижка мужская").contains("Анна").contains("Источник: salon_1"));

        Row row = bookingRow(1);
        assertThat(row.getCell(2).getStringCellValue()).isEqualTo("Иван Петров");
        assertThat((long) row.getCell(3).getNumericCellValue()).isEqualTo(101);
        assertThat(row.getCell(4).getStringCellValue()).isEqualTo("ivan");
        assertThat(row.getCell(7).getStringCellValue()).isEqualTo("Анна");
        assertThat(row.getCell(10).getStringCellValue()).isEqualTo("salon_1");
        assertThat(row.getCell(11).getStringCellValue()).isEqualTo("Подтверждена");
    }

    @Test
    void directStartWithAnyMasterAndContactButton() throws Exception {
        text(olga, "/start");
        click(olga, "m:book");
        click(olga, "s:3");
        click(olga, "w:*");
        assertThat(buttons(lastMarkup())).contains("Сб 24.10");   // Арам и Мариам работают в субботу
        click(olga, "d:20261024");
        assertThat(buttons(lastMarkup())).first().isEqualTo("09:00"); // Мариам с 09:00
        click(olga, "t:0900");
        text(olga, "Ольга");
        contact(olga, "37499000000");
        assertThat(lastText()).contains("Мастер: Мариам").contains("Телефон: +37499000000");
        click(olga, "ok");

        Row row = bookingRow(1);
        assertThat(row.getCell(7).getStringCellValue()).isEqualTo("Мариам");
        assertThat(row.getCell(10).getStringCellValue()).isEqualTo("direct");
    }

    @Test
    void backButtonReturnsToPreviousStep() {
        text(ivan, "/start");
        click(ivan, "m:book");
        click(ivan, "s:0");
        click(ivan, "w:0");
        click(ivan, "b");
        assertThat(lastText()).contains("Шаг 2 из 7");
        click(ivan, "b");
        assertThat(lastText()).contains("Шаг 1 из 7");
        click(ivan, "b");
        assertThat(lastText()).contains("Добро пожаловать");
    }

    @Test
    void dayWithoutFreeSlotsOffersAnotherDate() {
        // Окрашивание 120 мин; занимаем у Анны весь четверг
        for (String t : List.of("10:00", "12:00", "14:00", "16:00")) {
            repository.addBooking(com.salon.bot.model.Booking.builder()
                    .createdAt(NOW).clientName("X").telegramId(1).username("").phone("+37400000000")
                    .serviceName("Окрашивание").masterName("Анна").date(java.time.LocalDate.of(2026, 10, 22))
                    .time(java.time.LocalTime.parse(t)).source("direct")
                    .status(com.salon.bot.model.BookingStatus.CONFIRMED).build());
        }
        text(ivan, "/start");
        click(ivan, "m:book");
        click(ivan, "s:2");
        click(ivan, "w:0");
        click(ivan, "d:20261022");
        assertThat(lastText()).contains("свободного времени нет");
        assertThat(buttons(lastMarkup())).containsExactly("📅 Выбрать другую дату");
        click(ivan, "b");
        assertThat(lastText()).contains("Шаг 3 из 7");
    }

    @Test
    void slotIsCheckedAgainBeforeSaving() throws Exception {
        reachConfirmation(ivan);
        reachConfirmation(olga);

        click(ivan, "ok");
        click(olga, "ok");

        assertThat(lastText()).contains("только что заняли").contains("Шаг 4 из 7");
        assertThat(buttons(lastMarkup())).doesNotContain("10:00");
        try (Workbook wb = workbook()) {
            assertThat(wb.getSheet("Записи").getLastRowNum()).isEqualTo(1);
        }
    }

    @Test
    void myBookingsAndCancellation() throws Exception {
        reachConfirmation(ivan);
        click(ivan, "ok");

        click(ivan, "m:my");
        assertThat(lastText()).contains("№1 · Чт 22.10 10:00 · Стрижка мужская · Анна");
        assertThat(buttons(lastMarkup())).contains("❌ Отменить №1 (Чт 22.10 10:00)");

        click(olga, "x:1");    // чужую запись отменить нельзя
        assertThat(bookingRow(1).getCell(11).getStringCellValue()).isEqualTo("Подтверждена");

        click(ivan, "x:1");
        assertThat(lastText()).contains("Запись №1 отменена").contains("нет предстоящих записей");
        assertThat(bookingRow(1).getCell(11).getStringCellValue()).isEqualTo("Отменена");
        assertThat(sentTo(ADMIN)).anySatisfy(t -> assertThat(t).contains("Отмена записи №1").contains("Источник: direct"));

        // отменённый слот снова свободен
        click(ivan, "m:book");
        click(ivan, "s:0");
        click(ivan, "w:0");
        click(ivan, "d:20261022");
        assertThat(buttons(lastMarkup())).contains("10:00");
    }

    @Test
    void adminCommandsAreOnlyForAdmin() {
        reachConfirmation(ivan);
        click(ivan, "ok");

        text(ivan, "/admin");
        assertThat(lastText()).contains("только администратору");
        text(ivan, "/export");
        assertThat(sent).noneMatch(m -> m instanceof SendDocument);

        text(admin, "/admin");
        assertThat(adminLastText()).contains("Сегодня, 21.10.2026").contains("Записей нет")
                .contains("Завтра, 22.10.2026").contains("10:00 — Иван Петров, +37491123456");

        text(admin, "/export");
        assertThat(last()).isInstanceOf(SendDocument.class);
        assertThat(((SendDocument) last()).getDocument().getMediaName()).isEqualTo("salon_bookings.xlsx");

        text(admin, "/help");
        assertThat(adminLastText()).contains("/admin").contains("/export");
        text(ivan, "/help");
        assertThat(lastText()).contains("Мои записи").doesNotContain("/export");
    }

    @Test
    void reminderIsSentOnceTwoHoursBefore() {
        reachConfirmation(ivan);
        click(ivan, "ok");
        // 22.10 08:05 — до визита в 10:00 меньше 2 часов
        Clock clock = clockAt(LocalDateTime.of(2026, 10, 22, 8, 5));
        ReminderScheduler early = new ReminderScheduler(repository, messenger, adminNotifier,
                clockAt(LocalDateTime.of(2026, 10, 22, 7, 55)));
        ReminderScheduler scheduler = new ReminderScheduler(repository, messenger, adminNotifier, clock);

        early.sendReminders();
        assertThat(sentTo(101)).noneMatch(t -> t.contains("Напоминание"));

        scheduler.sendReminders();
        scheduler.sendReminders();
        assertThat(sentTo(101)).filteredOn(t -> t.contains("Напоминание")).hasSize(1);
        assertThat(repository.getUserBookings(101).get(0).isReminderSent()).isTrue();
    }

    @Test
    void busyStorageGivesPoliteMessageAndNotifiesAdmin() {
        reachConfirmation(ivan);
        assumeTrue(dir.toFile().setWritable(false));
        try {
            assumeTrue(!Files.isWritable(dir));
            click(ivan, "ok");
        } finally {
            dir.toFile().setWritable(true);
        }
        assertThat(sentTo(101)).last().asString().contains("попробуйте ещё раз");
        assertThat(sentTo(ADMIN)).anySatisfy(t -> assertThat(t).contains("закройте Excel-файл"));

        click(ivan, "ok"); // файл освободился — запись проходит
        assertThat(lastText()).contains("Вы записаны");
    }

    // ---------------------------------------------------------------- helpers

    private void reachConfirmation(User user) {
        text(user, "/start");
        click(user, "m:book");
        click(user, "s:0");
        click(user, "w:0");
        click(user, "d:20261022");
        click(user, "t:1000");
        click(user, "n:tg");
        text(user, "+37491123456");
        assertThat(lastText()).contains("Шаг 7 из 7");
    }

    private static Clock clockAt(LocalDateTime time) {
        return Clock.fixed(time.atZone(ZONE).toInstant(), ZONE);
    }

    private static User user(long id, String first, String last, String username) {
        User user = new User(id, first, false);
        user.setLastName(last);
        user.setUserName(username);
        return user;
    }

    private Message message(User from) {
        Message m = new Message();
        m.setMessageId(messageIds.incrementAndGet());
        m.setFrom(from);
        m.setChat(new Chat(from.getId(), "private"));
        return m;
    }

    private void text(User from, String text) {
        Message m = message(from);
        m.setText(text);
        Update update = new Update();
        update.setUpdateId(updateIds.incrementAndGet());
        update.setMessage(m);
        bot.consume(update);
    }

    private void contact(User from, String phone) {
        Message m = message(from);
        Contact contact = new Contact();
        contact.setPhoneNumber(phone);
        contact.setFirstName(from.getFirstName());
        contact.setUserId(from.getId());
        m.setContact(contact);
        Update update = new Update();
        update.setUpdateId(updateIds.incrementAndGet());
        update.setMessage(m);
        bot.consume(update);
    }

    private void click(User from, String data) {
        CallbackQuery query = new CallbackQuery();
        query.setId("cb" + updateIds.incrementAndGet());
        query.setFrom(from);
        query.setMessage(message(from));
        query.setData(data);
        Update update = new Update();
        update.setUpdateId(updateIds.incrementAndGet());
        update.setCallbackQuery(query);
        bot.consume(update);
    }

    /** Всё, что бот показал (без ответов на callback). */
    private List<Object> shown() {
        return sent.stream()
                .filter(m -> m instanceof SendMessage || m instanceof EditMessageText || m instanceof SendDocument)
                .toList();
    }

    private Object last() {
        return shown().getLast();
    }

    private static String text(Object method) {
        if (method instanceof SendMessage s) {
            return s.getText();
        }
        if (method instanceof EditMessageText e) {
            return e.getText();
        }
        return "";
    }

    private static String chatId(Object method) {
        return method instanceof SendMessage s ? s.getChatId()
                : method instanceof EditMessageText e ? e.getChatId() : "";
    }

    /** Текст последнего сообщения клиенту (уведомления администратору пропускаются). */
    private String lastText() {
        return text(shown().reversed().stream()
                .filter(m -> !chatId(m).equals(String.valueOf(ADMIN)))
                .findFirst().orElseThrow());
    }

    private String adminLastText() {
        return sentTo(ADMIN).getLast();
    }

    private InlineKeyboardMarkup lastMarkup() {
        return sent.reversed().stream()
                .map(m -> m instanceof EditMessageText e ? e.getReplyMarkup()
                        : m instanceof SendMessage s && s.getReplyMarkup() instanceof InlineKeyboardMarkup k ? k : null)
                .filter(java.util.Objects::nonNull)
                .findFirst().orElseThrow();
    }

    private static List<String> buttons(InlineKeyboardMarkup markup) {
        return markup.getKeyboard().stream().flatMap(List::stream).map(b -> b.getText()).toList();
    }

    private List<String> sentTo(long chatId) {
        return sent.stream()
                .filter(m -> m instanceof SendMessage s && s.getChatId().equals(String.valueOf(chatId)))
                .map(BookingScenarioTest::text)
                .toList();
    }

    private Workbook workbook() throws Exception {
        try (InputStream in = Files.newInputStream(excel)) {
            return new XSSFWorkbook(in);
        }
    }

    private Row bookingRow(int index) throws Exception {
        try (Workbook wb = workbook()) {
            Sheet sheet = wb.getSheet("Записи");
            return sheet.getRow(index);
        }
    }
}
