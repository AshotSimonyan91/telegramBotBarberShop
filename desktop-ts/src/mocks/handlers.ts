import { http, HttpResponse, type HttpResponseResolver } from 'msw';
import { z } from 'zod';
import type { ApiErrorBody, ApiErrorCode, Booking, BookingStatus } from '@shared/api/schemas';
import { createBookingRequestSchema, isValidLocalDate, isValidLocalTime } from '@shared/api/schemas';
import { datePart } from '@shared/datetime';
import { clientNameSchema, phoneSchema } from '@shared/validation';
import {
  activeMasters,
  availableDates,
  freeSlots,
  normalizePhone,
  pickMaster,
  worksOn,
  type MockDb,
} from './db';

/**
 * Обработчики MSW по контракту `/api/v1`. Адрес сервера любой (`*`), поэтому одни и те же
 * обработчики подходят и для тестов, и для режима `dev:mock`.
 */

export const MOCK_TOKEN = 'mock-token';
const API = '*/api/v1';

/**
 * Тело POST /bookings без проверки формата даты и времени: как сервер, мок разбирает их сам
 * и отвечает 400 с `fields.date` / `fields.time` (2027-02-30, 24:00), а не общей ошибкой.
 */
const createBookingBodySchema = createBookingRequestSchema.extend({
  date: z.string(),
  time: z.string(),
});

const STATUS: Readonly<Record<ApiErrorCode, number>> = {
  unauthorized: 401,
  validation: 400,
  not_found: 404,
  method_not_allowed: 405,
  slot_taken: 409,
  already_cancelled: 409,
  storage_busy: 503,
  storage_error: 500,
  internal: 500,
};

export function apiError(code: ApiErrorCode, message: string, fields?: Record<string, string>) {
  const body: ApiErrorBody =
    fields === undefined ? { error: code, message } : { error: code, message, fields };
  return HttpResponse.json(body, { status: STATUS[code] });
}

function authorized(request: Request, token: string): boolean {
  return request.headers.get('Authorization') === `Bearer ${token}`;
}

const KNOWN_PATHS: readonly RegExp[] = [
  /^\/health$/,
  /^\/status$/,
  /^\/services$/,
  /^\/masters$/,
  /^\/bookings$/,
  /^\/bookings\/\d+$/,
  /^\/bookings\/\d+\/cancel$/,
  /^\/slots$/,
  /^\/dates$/,
];

export interface MockHandlersOptions {
  token?: string;
}

export function createHandlers(db: MockDb, options: MockHandlersOptions = {}) {
  const token = options.token ?? MOCK_TOKEN;

  /** Проверка токена для всех методов, кроме /health. */
  const guard =
    (resolver: HttpResponseResolver): HttpResponseResolver =>
    (info) => {
      if (!authorized(info.request, token)) {
        return apiError('unauthorized', 'Неверный или отсутствующий токен.');
      }
      return resolver(info);
    };

  const findBooking = (rawId: unknown): Booking | undefined => {
    const id = Number(rawId);
    return db.bookings.find((b) => b.id === id);
  };

  return [
    http.get(`${API}/health`, () =>
      HttpResponse.json({ status: 'ok', timezone: db.timezone, now: db.now() }),
    ),

    http.get(
      `${API}/status`,
      guard(() =>
        HttpResponse.json({
          bot: { ...db.bot },
          storage: { ...db.storage },
          time: db.now(),
        }),
      ),
    ),

    http.get(
      `${API}/services`,
      guard(() => HttpResponse.json(db.services)),
    ),
    http.get(
      `${API}/masters`,
      guard(() => HttpResponse.json(db.masters)),
    ),

    http.get(
      `${API}/bookings`,
      guard(({ request }) => {
        const params = new URL(request.url).searchParams;
        const date = params.get('date');
        const from = params.get('from');
        const to = params.get('to');
        if (date !== null && (from !== null || to !== null)) {
          return apiError('validation', 'Параметр date нельзя указывать вместе с from/to.');
        }
        const status = params.get('status') as BookingStatus | null;
        const masterName = params.get('masterName');
        const q = params.get('q')?.trim().toLowerCase() ?? '';
        const digits = q.replace(/\D/g, '');
        const result = db.bookings.filter(
          (b) =>
            (date === null || b.date === date) &&
            (from === null || b.date >= from) &&
            (to === null || b.date <= to) &&
            (status === null || b.status === status) &&
            (masterName === null || b.masterName.toLowerCase() === masterName.toLowerCase()) &&
            (q === '' ||
              b.clientName.toLowerCase().includes(q) ||
              b.username.toLowerCase().includes(q) ||
              (digits !== '' && b.phone.replace(/\D/g, '').includes(digits)) ||
              String(b.id) === q),
        );
        return HttpResponse.json(result);
      }),
    ),

    http.get(
      `${API}/bookings/:id`,
      guard(({ params }) => {
        const booking = findBooking(params.id);
        return booking === undefined
          ? apiError('not_found', 'Запись не найдена.')
          : HttpResponse.json(booking);
      }),
    ),

    http.post(
      `${API}/bookings`,
      guard(async ({ request }) => {
        const parsed = createBookingBodySchema.safeParse(await request.json().catch(() => null));
        if (!parsed.success) {
          return apiError('validation', 'Некорректный запрос.');
        }
        const body = parsed.data;
        const fields: Record<string, string> = {};
        const name = clientNameSchema.safeParse(body.clientName);
        const phone = phoneSchema.safeParse(body.phone);
        if (!name.success) {
          fields.clientName = name.error.issues[0]?.message ?? 'Некорректное имя.';
        }
        if (!phone.success) {
          fields.phone = phone.error.issues[0]?.message ?? 'Некорректный телефон.';
        }
        const dateValid = isValidLocalDate(body.date);
        const timeValid = isValidLocalTime(body.time);
        if (!dateValid) {
          fields.date = 'Некорректная дата.';
        } else if (body.date < datePart(db.now())) {
          fields.date = 'Дата не может быть в прошлом.';
        }
        if (!timeValid) {
          fields.time = 'Некорректное время.';
        }
        const service = db.services.find((s) => s.name === body.serviceName);
        if (service === undefined) {
          fields.serviceName = 'Услуга не найдена.';
        }
        const master =
          body.masterName === null ? undefined : db.masters.find((m) => m.name === body.masterName);
        if (body.masterName !== null && (master === undefined || !master.active)) {
          fields.masterName = 'Мастер не найден.';
        } else if (!dateValid) {
          // У несуществующей даты нет дня недели: ошибка уже в fields.date.
        } else if (master !== undefined && !worksOn(master, body.date)) {
          fields.date = `Мастер ${master.name} не работает в этот день.`;
        } else if (body.masterName === null && !activeMasters(db).some((m) => worksOn(m, body.date))) {
          fields.date = 'В этот день мастера не работают.';
        }
        if (
          Object.keys(fields).length > 0 ||
          service === undefined ||
          !name.success ||
          !phone.success ||
          !dateValid ||
          !timeValid
        ) {
          return apiError('validation', 'Проверьте данные записи.', fields);
        }
        const picked = pickMaster(db, body.date, body.time, service.durationMinutes, body.masterName);
        if (picked === null) {
          return apiError('slot_taken', 'Это время уже заняли. Выберите другое.');
        }
        const booking: Booking = {
          id: db.nextId++,
          createdAt: db.now(),
          clientName: name.data,
          telegramId: 0,
          username: '',
          phone: normalizePhone(phone.data),
          serviceName: service.name,
          masterName: picked.name,
          date: body.date,
          time: body.time,
          durationMinutes: service.durationMinutes,
          source: 'администратор',
          status: 'CONFIRMED',
          reminderSent: false,
        };
        db.bookings.push(booking);
        return HttpResponse.json(booking, { status: 201 });
      }),
    ),

    http.post(
      `${API}/bookings/:id/cancel`,
      guard(({ params }) => {
        const booking = findBooking(params.id);
        if (booking === undefined) {
          return apiError('not_found', 'Запись не найдена.');
        }
        if (booking.status === 'CANCELLED') {
          return apiError('already_cancelled', 'Запись уже отменена.');
        }
        booking.status = 'CANCELLED';
        // Как на сервере: заголовок есть только у записей из Telegram.
        const headers: Record<string, string> =
          booking.telegramId > 0 ? { 'X-Client-Notified': String(db.telegram.notifyOk) } : {};
        return HttpResponse.json(booking, { headers });
      }),
    ),

    http.get(
      `${API}/slots`,
      guard(({ request }) => {
        const params = new URL(request.url).searchParams;
        const service = db.services.find((s) => s.name === params.get('serviceName'));
        const date = params.get('date');
        if (service === undefined || date === null || !isValidLocalDate(date)) {
          return apiError('validation', 'Укажите услугу и дату.');
        }
        return HttpResponse.json({
          date,
          serviceName: service.name,
          durationMinutes: service.durationMinutes,
          slots: freeSlots(db, date, service.durationMinutes, params.get('masterName')),
        });
      }),
    ),

    http.get(
      `${API}/dates`,
      guard(({ request }) => {
        const params = new URL(request.url).searchParams;
        const serviceName = params.get('serviceName');
        if (serviceName === null || !db.services.some((s) => s.name === serviceName)) {
          return apiError('validation', 'Укажите услугу.');
        }
        const days = Math.min(Math.max(Number(params.get('days') ?? '14') || 14, 1), 60);
        return HttpResponse.json(availableDates(db, serviceName, params.get('masterName'), days));
      }),
    ),

    // Известный путь с другим методом — 405, неизвестный путь — 404 (как у сервера).
    http.all(`${API}/*`, ({ request }) => {
      const path = new URL(request.url).pathname.replace(/^.*\/api\/v1/, '');
      return KNOWN_PATHS.some((pattern) => pattern.test(path))
        ? apiError('method_not_allowed', 'Метод не поддерживается.')
        : apiError('not_found', 'Неизвестный адрес API.');
    }),
  ];
}
