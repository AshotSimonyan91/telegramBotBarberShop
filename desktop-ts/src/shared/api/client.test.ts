import { http, HttpResponse } from 'msw';
import { beforeEach, describe, expect, it } from 'vitest';
import { apiError } from '@mocks/handlers';
import type { MockDb } from '@mocks/db';
import { resetMockDb, server, TEST_API_URL, TEST_TOKEN } from '@renderer/test/server';
import { ApiError, createApiClient, parseClientNotified, UNSUPPORTED_REQUEST_MESSAGE } from './client';
import { createFetchTransport } from './transport';

function client(token: string | null = TEST_TOKEN) {
  return createApiClient(createFetchTransport({ getConnection: () => ({ baseUrl: TEST_API_URL, token }) }));
}

async function caught(promise: Promise<unknown>): Promise<ApiError> {
  try {
    await promise;
  } catch (error) {
    expect(error).toBeInstanceOf(ApiError);
    return error as ApiError;
  }
  throw new Error('Ожидалась ошибка');
}

const NEW_BOOKING = {
  clientName: 'Гаяне',
  phone: '+374 (91) 00-00-00',
  serviceName: 'Мужская стрижка',
  masterName: null,
  date: '2026-10-29',
  time: '12:00',
};

describe('createApiClient', () => {
  let db: MockDb;
  beforeEach(() => {
    db = resetMockDb();
  });

  it('загружает и валидирует записи с фильтрами', async () => {
    const bookings = await client().bookings({ status: 'CONFIRMED', masterName: 'Анна' });
    expect(bookings.map((b) => b.id)).toEqual([1]);
    expect(bookings[0]?.status).toBe('CONFIRMED');
  });

  it('не отправляет пустые параметры запроса', async () => {
    let url = '';
    server.use(
      http.get('*/api/v1/bookings', ({ request }) => {
        url = request.url;
        return HttpResponse.json([]);
      }),
    );
    await client().bookings({ q: '', from: '2026-10-28' });
    expect(new URL(url).search).toBe('?from=2026-10-28');
  });

  it('/health доступен без токена', async () => {
    const health = await client(null).health();
    expect(health).toEqual({ status: 'ok', timezone: 'Asia/Yerevan', now: '2026-10-28T15:04' });
  });

  it('401 — ошибка unauthorized', async () => {
    const error = await caught(client('wrong').services());
    expect(error.status).toBe(401);
    expect(error.isUnauthorized).toBe(true);
  });

  it('409 slot_taken — код и русское сообщение сервера', async () => {
    server.use(http.post('*/api/v1/bookings', () => apiError('slot_taken', 'Это время уже заняли.')));
    const error = await caught(
      client().createBooking({
        clientName: 'Анна',
        phone: '+37491000000',
        serviceName: 'Мужская стрижка',
        masterName: null,
        date: '2026-10-29',
        time: '12:00',
      }),
    );
    expect(error.code).toBe('slot_taken');
    expect(error.message).toBe('Это время уже заняли.');
  });

  it('400 validation — ошибки полей', async () => {
    const error = await caught(
      client().createBooking({
        clientName: 'А',
        phone: 'abc',
        serviceName: 'Мужская стрижка',
        masterName: null,
        date: '2026-10-29',
        time: '12:00',
      }),
    );
    expect(error.code).toBe('validation');
    expect(Object.keys(error.fields).sort()).toEqual(['clientName', 'phone']);
  });

  it('сервер хранит телефон нормализованным', async () => {
    const booking = await client().createBooking(NEW_BOOKING);
    expect(booking.phone).toBe('+37491000000');
  });

  it('мастер не работает в этот день — 400 с fields.date', async () => {
    // 01.11.2026 — воскресенье, Анна не работает
    const error = await caught(
      client().createBooking({ ...NEW_BOOKING, masterName: 'Анна', date: '2026-11-01' }),
    );
    expect(error.status).toBe(400);
    expect(error.code).toBe('validation');
    expect(Object.keys(error.fields)).toEqual(['date']);
    expect(error.fields.date).toMatch(/не работает/);
  });

  it('/dates — рабочие дни, даже если свободного времени нет', async () => {
    db.now = () => '2026-10-28T19:30';
    const dates = await client().dates({ serviceName: 'Мужская стрижка', days: 3 });
    expect(dates).toEqual(['2026-10-28', '2026-10-29', '2026-10-30']);
    const slots = await client().slots({ serviceName: 'Мужская стрижка', date: '2026-10-28' });
    expect(slots.slots).toEqual([]);
  });

  it('createdAt: null, пустая строка и секунды допускаются', async () => {
    const base = db.bookings[0];
    server.use(
      http.get('*/api/v1/bookings', () =>
        HttpResponse.json([
          { ...base, id: 10, createdAt: null },
          { ...base, id: 11, createdAt: '' },
          { ...base, id: 12, createdAt: '2026-10-27T10:00:15' },
        ]),
      ),
    );
    const bookings = await client().bookings();
    expect(bookings.map((b) => b.createdAt)).toEqual([null, null, '2026-10-27T10:00:15']);
  });

  it('время записи строго ЧЧ:ММ', async () => {
    server.use(
      http.get('*/api/v1/bookings/1', () => HttpResponse.json({ ...db.bookings[0], time: '12:00:00' })),
    );
    const error = await caught(client().booking(1));
    expect(error.kind).toBe('invalid-response');
  });

  it('отмена записи из Telegram — клиент уведомлён', async () => {
    const result = await client().cancelBooking(1);
    expect(result.booking.status).toBe('CANCELLED');
    expect(result.clientNotified).toBe(true);
  });

  it('отмена записи из Telegram — уведомить не удалось', async () => {
    db.telegram.notifyOk = false;
    expect((await client().cancelBooking(1)).clientNotified).toBe(false);
  });

  it('отмена записи администратора — без заголовка, clientNotified null', async () => {
    expect((await client().cancelBooking(2)).clientNotified).toBeNull();
  });

  it('parseClientNotified', () => {
    expect(parseClientNotified({ 'x-client-notified': 'true' })).toBe(true);
    expect(parseClientNotified({ 'x-client-notified': ' FALSE ' })).toBe(false);
    expect(parseClientNotified({ 'x-client-notified': 'да' })).toBeNull();
    expect(parseClientNotified({})).toBeNull();
  });

  it('отмена несуществующей записи — 404 с сообщением сервера', async () => {
    const error = await caught(client().cancelBooking(999));
    expect(error.code).toBe('not_found');
    expect(error.message).toBe('Запись не найдена.');
  });

  it('404 на неизвестный путь и 405 — общее сообщение', async () => {
    server.use(
      http.get('*/api/v1/dates', () => apiError('not_found', 'Not Found')),
      http.get('*/api/v1/status', () => apiError('method_not_allowed', 'Method Not Allowed')),
    );
    const notFound = await caught(client().dates({ serviceName: 'Мужская стрижка' }));
    expect(notFound.status).toBe(404);
    expect(notFound.message).toBe(UNSUPPORTED_REQUEST_MESSAGE);
    const notAllowed = await caught(client().status());
    expect(notAllowed.status).toBe(405);
    expect(notAllowed.code).toBe('method_not_allowed');
    expect(notAllowed.message).toBe(UNSUPPORTED_REQUEST_MESSAGE);
  });

  it('мок отвечает 404/405 на неизвестный путь и метод', async () => {
    const unknown = await fetch(`${TEST_API_URL}/api/v1/unknown`);
    expect(unknown.status).toBe(404);
    expect(await unknown.json()).toMatchObject({ error: 'not_found' });
    const wrongMethod = await fetch(`${TEST_API_URL}/api/v1/services`, { method: 'DELETE' });
    expect(wrongMethod.status).toBe(405);
    expect(await wrongMethod.json()).toMatchObject({ error: 'method_not_allowed' });
  });

  it('повторная отмена — already_cancelled', async () => {
    const error = await caught(client().cancelBooking(3));
    expect(error.status).toBe(409);
    expect(error.code).toBe('already_cancelled');
  });

  it('503 без тела ошибки — понятное сообщение по статусу', async () => {
    server.use(http.get('*/api/v1/masters', () => new HttpResponse(null, { status: 503 })));
    const error = await caught(client().masters());
    expect(error.status).toBe(503);
    expect(error.message).toMatch(/Хранилище занято/);
  });

  it('ответ не по контракту — invalid-response', async () => {
    server.use(http.get('*/api/v1/services', () => HttpResponse.json([{ name: 'Стрижка' }])));
    const error = await caught(client().services());
    expect(error.kind).toBe('invalid-response');
  });

  it('сетевая ошибка — kind network', async () => {
    server.use(http.get('*/api/v1/status', () => HttpResponse.error()));
    const error = await caught(client().status());
    expect(error.kind).toBe('network');
    expect(error.message).toMatch(/бот запущен/);
  });

  it('без настроек — not-configured', async () => {
    const api = createApiClient(createFetchTransport({ getConnection: () => null }));
    const error = await caught(api.status());
    expect(error.kind).toBe('not-configured');
  });
});
