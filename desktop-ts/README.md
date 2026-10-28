# Салон — настольное приложение (TypeScript)

Приложение администратора салона: просмотр и отмена записей, запись клиента по телефону, состояние бота.
Работает с ботом через его HTTP API (`/api/v1`), с Excel-файлом напрямую не работает.

Стек: Electron + electron-vite, React 19, TypeScript (strict), TanStack Query, zod, React Hook Form,
Tailwind CSS 4, Vitest + Testing Library + MSW.

## Запуск

```bash
cd desktop-ts
npm ci
npm run dev        # с настоящим API бота (бот с включённым API должен быть запущен)
npm run dev:mock   # без бота: main-процесс отвечает моками MSW, токен не нужен
```

Требуется Node 22.12+. Установка — обычный `npm ci` без дополнительных флагов (`.npmrc` не нужен).
Бинарник Electron 45 скачивается не при установке, а при первом запуске (`npm run dev`) в кэш
`@electron/get`; `npm run package` берёт Electron из того же кэша или скачивает сам.

### Зависимости

В `dependencies` только то, что `require` делает main-процесс в собранном виде (сейчас — `zod`):
electron-builder кладёт в `app.asar` только их. React, TanStack Query, React Hook Form и остальное,
что нужно лишь странице, Vite встраивает в бандл renderer, поэтому они в `devDependencies`.
Новый пакет для main-процесса добавлять в `dependencies`, для renderer — в `devDependencies`.

## Настройки подключения

| Параметр  | Переменная окружения | По умолчанию            |
| --------- | -------------------- | ----------------------- |
| Адрес API | `SALON_API_URL`      | `http://127.0.0.1:8787` |
| Токен API | `SALON_API_TOKEN`    | — (обязателен)          |

Источники по приоритету: переменные окружения → настройки, сохранённые в приложении (вкладка «Настройки»,
файл `settings.json` в папке userData Electron) → значение по умолчанию. При `npm run dev` читается и
`desktop-ts/.env` (образец — `.env.example`; файл `.env` в git не коммитится). Если токен не задан,
приложение сразу открывает вкладку «Настройки».

Токен хранится только в main-процессе (в файле — зашифрованным через `safeStorage`, если ОС это
поддерживает) и в страницу не передаётся: все запросы к API делает main-процесс по IPC.

## Скрипты

| Команда                               | Что делает                                                    |
| ------------------------------------- | ------------------------------------------------------------- |
| `npm run dev` / `npm run dev:mock`    | Запуск в режиме разработки (с реальным API / с моками)        |
| `npm run build`                       | Проверка типов и production-сборка в `out/`                   |
| `npm run typecheck`                   | `tsc` для main/preload и renderer                             |
| `npm run lint`                        | ESLint (flat config, type-aware) + проверка Prettier          |
| `npm run format`                      | Форматирование Prettier                                       |
| `npm run test` / `npm run test:watch` | Тесты Vitest (jsdom, MSW)                                     |
| `npm run package`                     | Сборка приложения в папку `dist/` (electron-builder, `--dir`) |

## Структура

```
src/
  shared/        общий код main и renderer
    api/schemas.ts     zod-схемы контракта — единственный источник типов
    api/client.ts      типизированный клиент API, ApiError
    api/transport.ts   fetch-транспорт, белый список путей, сборка URL
    datetime.ts        даты ГГГГ-ММ-ДД / ЧЧ:ММ без часовых поясов, форматирование
    validation.ts      правила новой записи (совпадают с серверными)
    ipc.ts             каналы и типы моста preload
  main/          main-процесс: окно, безопасность, IPC, настройки, мок-API
  preload/       contextBridge → window.salon
  mocks/         MSW-обработчики и данные в памяти (тесты и dev:mock)
  renderer/src/
    api/           контекст зависимостей, хуки TanStack Query
    lib/           чистые функции: фильтры, сортировка, форматирование
    components/    Button, Modal (<dialog>), ConfirmDialog, Toasts, Tabs, Field…
    features/      bookings (таблица, фильтры, новая запись), status, settings
    test/          setup Vitest, MSW-сервер, рендер с провайдерами
```

## Особенности API, учтённые в приложении

- `GET /dates` возвращает рабочие дни мастеров, а не дни со свободным временем. Если `GET /slots`
  вернул пустой список, форма пишет «Нет свободного времени на этот день, выберите другую дату или мастера».
- Мастер не работает в выбранный день — `400 validation` с `fields.date`, ошибка показывается у поля «Дата».
- `POST /bookings/{id}/cancel` для записей из Telegram (`telegramId > 0`) возвращает заголовок
  `X-Client-Notified: true|false`. Main-процесс передаёт в renderer только заголовки из белого списка
  (`EXPOSED_HEADERS` в `transport.ts`); результат отмены — `{ booking, clientNotified: boolean | null }`.
  При `false` показывается предупреждение с телефоном клиента.
- `createdAt` у старых строк Excel может быть `null` или пустым — в таблице «—».
- Телефон сервер хранит нормализованным (`+37491123456`), группировка для показа — `lib/format.ts`.
- `404` на неизвестный путь и `405 method_not_allowed` показываются общим сообщением о несовпадении версий.

## Безопасность

`contextIsolation: true`, `nodeIntegration: false`, `sandbox: true`; строгий CSP в production-сборке
(в dev ослаблен для HMR Vite); новые окна, навигация, webview и запросы разрешений запрещены;
IPC принимает запросы только от своей страницы и только к путям из белого списка контракта.
