# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

Telegram-бот @AshotSimonyanBot для записи клиентов в парикмахерский салон.
Клиент сканирует QR-код → deep link `/start <source>` → пошаговая запись → строка в Excel-файле.

## Стек

Java 21, Maven, Spring Boot 3.5, telegrambots 10 (long polling через `telegrambots-springboot-longpolling-starter`),
Apache POI (xlsx), ZXing (QR), dotenv-java, Lombok. Тесты — JUnit 5, AssertJ, Mockito.

## Команды

- `mvn package` — сборка `target/salon-bot.jar` (с тестами)
- `mvn test` — тесты; один класс/метод: `mvn test -Dtest=SlotCalculatorTest` / `-Dtest='BookingScenarioTest#имяМетода'`
- `mvn package -DskipTests` — быстрая сборка jar
- `./start.sh` / `start.bat` — запуск бота из jar (скрипт делает `cd` в папку проекта, где `.env`)
- `./desktop.sh` / `desktop.command` / `desktop.bat` — настольное приложение (нужен собранный jar)
- `mvn -q compile exec:java -Dexec.args="--source=salon_1 --out=qr_salon_1.png"` — QR-код

Перед запуском бота убедиться, что другой экземпляр не работает (`start.sh`, приложение, IDE) —
два long polling на один токен дают Telegram 409 Conflict. `mvn package` бота не останавливает.

## Конфигурация

`.env` → System properties (`BotApplication.loadDotenv`: реальные env-переменные важнее, **пустые значения
пропускаются**) → плейсхолдеры `${VAR:default}` в `application.yml` → `BotProperties` (`bot.*`).
Если в `.env` переменная пустая, берётся default из `application.yml`. Excel по умолчанию —
`salon_bookings.xlsx` в рабочей папке (рядом лежат `backups/` и `.salon_bookings.xlsx.lock`);
запасной путь «рабочий стол» в `BotProperties.excelFile()` срабатывает только при пустом `bot.excel-path`.

## Структура (`com.salon.bot`)

- `BotApplication` — точка входа, переносит `.env` в System properties
- `config/` — `BotProperties` (настройки из env), `AppConfig` (Clock, TelegramClient, BookingRepository)
- `bot/` — `SalonBot` (приём Update, обработка ошибок хранилища), `UpdateRouter`, `Messenger` (обёртка Telegram API), `AdminNotifier`
- `handler/` — обработчики (`@Order` задаёт приоритет): Start, Help, Admin, MyBookings, BookingFlow, Fallback; `Texts` — форматирование
- `keyboard/` — `KeyboardFactory`, `Callbacks` (короткие callback data)
- `state/` — `UserState`, `UserSession`, `SessionStorage` (ConcurrentHashMap, в памяти)
- `storage/` — `BookingRepository` и `ExcelBookingRepository`
- `service/` — `SlotCalculator` (без зависимостей от хранилища), `BookingService`, `ReminderScheduler`
- `model/` — `Booking`, `BookingStatus`, `Service`, `Master`
- `qr/` — `QrGenerator` (отдельный main)

`com.salon.desktop` — настольное приложение на Swing (`./desktop.sh`, запускается из того же jar через
`PropertiesLauncher`): `BotProcess` (бот как дочерний процесс), `BookingsPanel`, `NewBookingDialog`, `LogPanel`.
Свой Spring-контекст `DesktopConfig` без long polling; данные — через `BookingRepository`/`BookingService`.
Записи из приложения — источник «администратор», без Telegram ID, напоминания по ним не шлются.

## Тесты

- `BookingScenarioTest` — сквозной сценарий через настоящие обработчики и Excel во временной папке,
  `TelegramClient` замокан. При изменении диалога записи (шаги, тексты кнопок, callback data) обновлять его.
- `ExcelBookingRepositoryTest` проверяет межпроцессную запись: запускает несколько JVM с
  `ConcurrentWriterMain` (тестовый main) на classpath тестов.

## Правила

- **Секреты только в `.env`** / переменных окружения. Токены, ID чатов и пути в коде не хардкодить. `.env` в git не коммитить.
- **Интерфейс бота — на русском**: все тексты, кнопки и сообщения об ошибках для пользователя.
- **Данные только через `BookingRepository`.** Остальной код не должен знать про Excel/POI —
  в будущем появится реализация для Google Sheets. Новые операции с данными сначала добавлять в интерфейс.
- `SlotCalculator` не зависит от хранилища: получает записи параметром.
- Время — через `java.time` и бин `Clock` (зона из `TIMEZONE`), не `LocalDateTime.now()` без часов.
- Смена шагов по нажатию кнопки — `EditMessageText`, а не новое сообщение.
- Ошибки хранилища — `StorageException`/`StorageBusyException`; пользователю вежливое сообщение, в лог через SLF4J.
- Работа с Excel-файлом: один `ReentrantLock` на файл, плюс блокировка lock-файла на изменения (бот и приложение — разные процессы), бэкап перед изменением, запись во временный файл и атомарная замена.
- Swing: обращения к хранилищу и Telegram — через `Background`, не в потоке Swing.

## Команда агентов

Субагенты проекта — в `.claude/agents/`; основная сессия работает как `orchestrator` (`.claude/settings.json`).

- `orchestrator` (sonnet) — планирует, фиксирует контракты, делегирует и контролирует; код не пишет.
- `backend-developer` (opus) — сервисы, хранилище, конфигурация, интеграции.
- `frontend-developer` (opus) — тексты, клавиатуры и шаги диалога в Telegram, Swing-приложение.
- `tester` (sonnet) — пишет и запускает тесты; баги не чинит, а возвращает отчёт.
- `code-reviewer` (sonnet) — ревью изменений, вердикт APPROVE / CHANGES REQUESTED; код не правит.

Порядок: orchestrator → backend/frontend (параллельно по общему контракту) → tester → code-reviewer.
Найденные проблемы возвращаются исполнителю, максимум 3 итерации, затем эскалация пользователю.
