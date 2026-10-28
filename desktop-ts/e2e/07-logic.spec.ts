import { expect, test } from '@playwright/test';
import { fillForm, launchApp, openNewBooking, row, statusList, toasts, dialog, type Launched } from './helpers/app';
import { API_TOKEN, Backend } from './helpers/backend';
import {
  addDays,
  allBookings,
  booking,
  createViaApi,
  freeSlots,
  LONG_SERVICE,
  MASTERS,
  nextFriday,
  serverToday,
  SERVICE,
  type BookingDto,
} from './helpers/data';

test.describe('7. Логика на стыке (REST API реального бэкенда)', () => {
  let backend: Backend;
  let date: string;

  test.beforeAll(async () => {
    backend = await Backend.start();
  });
  test.afterAll(async () => {
    await backend.dispose();
  });
  test.beforeEach(async () => {
    await backend.reset();
    date = await nextFriday(backend);
  });

  test('10 одновременных записей на один слот одного мастера → ровно один 201', async () => {
    const results = await Promise.all(
      Array.from({ length: 10 }, (_, i) =>
        createViaApi(backend, booking({ date, time: '12:00', masterName: 'Анна', clientName: `Клиент ${i}` })),
      ),
    );
    const statuses = results.map((r) => r.status);
    expect(statuses.filter((s) => s === 201)).toHaveLength(1);
    expect(statuses.filter((s) => s === 409)).toHaveLength(9);
    for (const r of results.filter((x) => x.status === 409)) {
      expect(r.body).toMatchObject({ error: 'slot_taken' });
    }
    const stored = await allBookings(backend);
    expect(stored).toHaveLength(1);
    // ID не дублируются
    expect(new Set(stored.map((b) => b.id)).size).toBe(stored.length);
  });

  test('10 одновременных «любой мастер» на одно время → ровно 3 записи у 3 разных мастеров', async () => {
    const results = await Promise.all(
      Array.from({ length: 10 }, (_, i) =>
        createViaApi(backend, booking({ date, time: '11:00', masterName: null, clientName: `Клиент ${i}` })),
      ),
    );
    const ok = results.filter((r) => r.status === 201);
    expect(ok).toHaveLength(3);
    expect(results.filter((r) => r.status === 409)).toHaveLength(7);
    expect(new Set(ok.map((r) => (r.body as BookingDto).masterName))).toEqual(new Set(MASTERS));
    expect(new Set(ok.map((r) => (r.body as BookingDto).id)).size).toBe(3);
    expect(await allBookings(backend)).toHaveLength(3);
  });

  test('«любой мастер» ×3 подряд → 3 разных мастера, 4-я → 409 slot_taken', async () => {
    const masters: string[] = [];
    for (let i = 0; i < 3; i++) {
      const r = await createViaApi(backend, booking({ date, time: '11:00', masterName: null, clientName: `Клиент ${i}` }));
      expect(r.status).toBe(201);
      masters.push((r.body as BookingDto).masterName);
    }
    expect(new Set(masters).size).toBe(3);
    expect([...masters].sort()).toEqual([...MASTERS].sort());
    const fourth = await createViaApi(backend, booking({ date, time: '11:00', masterName: null, clientName: 'Четвёртый' }));
    expect(fourth.status).toBe(409);
    expect(fourth.body).toMatchObject({ error: 'slot_taken' });
    expect(await freeSlots(backend, SERVICE, date, null)).not.toContain('11:00');
  });

  test('UI: «любой мастер» на уже занятое тремя время → сообщение slot_taken, слот пропадает из списка', async () => {
    const launched: Launched = await launchApp({ apiUrl: backend.url, token: API_TOKEN });
    try {
      const { page } = launched;
      await expect(statusList(page)).toContainText('Хранилище');
      await openNewBooking(page);
      const d = await fillForm(page, { service: SERVICE, date, slot: '11:00', name: 'Четвёртый Клиент', phone: '+37491444444' });
      for (let i = 0; i < 3; i++) {
        expect((await createViaApi(backend, booking({ date, time: '11:00', masterName: null, clientName: `Клиент ${i}` }))).status).toBe(201);
      }
      await d.getByRole('button', { name: 'Записать' }).click();
      await expect(d.getByRole('alert')).toContainText('Список свободного времени обновлён.');
      await expect(dialog(page)).toBeVisible();
      await expect(d.locator('label').filter({ hasText: /^11:00$/ })).toHaveCount(0);
      expect(await allBookings(backend)).toHaveLength(3);
      await d.getByRole('button', { name: 'Отмена' }).click();
      await expect(row(page, 'Четвёртый Клиент')).toHaveCount(0);
      await expect(toasts(page)).not.toContainText('сохранена');
    } finally {
      await launched.close();
    }
  });

  test('пересечение по длительности: Окрашивание 120 мин блокирует следующие слоты мастера', async () => {
    expect((await createViaApi(backend, booking({ serviceName: LONG_SERVICE, date, time: '12:00', masterName: 'Анна' }))).status).toBe(201);
    // 12:00–14:00 занято; 11:30 + 30 мин = 12:00 впритык — можно; 13:30 пересекается; 14:00 свободно
    expect((await createViaApi(backend, booking({ date, time: '13:30', masterName: 'Анна' }))).status).toBe(409);
    expect((await createViaApi(backend, booking({ date, time: '12:30', masterName: 'Анна' }))).status).toBe(409);
    expect((await createViaApi(backend, booking({ date, time: '11:30', masterName: 'Анна' }))).status).toBe(201);
    expect((await createViaApi(backend, booking({ date, time: '14:00', masterName: 'Анна' }))).status).toBe(201);
    const slots = await freeSlots(backend, SERVICE, date, 'Анна');
    for (const s of ['11:30', '12:00', '12:30', '13:00', '13:30', '14:00']) {
      expect(slots).not.toContain(s);
    }
  });

  test('границы рабочего дня: последний слот до конца смены можно, выходящий за конец — нет', async () => {
    // Анна работает до 19:00, Борода 30 мин
    expect((await createViaApi(backend, booking({ date, time: '18:30', masterName: 'Анна' }))).status).toBe(201);
    const late = await createViaApi(backend, booking({ date, time: '19:00', masterName: 'Анна', clientName: 'Поздно' }));
    expect([400, 409]).toContain(late.status);
    const early = await createViaApi(backend, booking({ date, time: '09:30', masterName: 'Анна', clientName: 'Рано' }));
    expect([400, 409]).toContain(early.status);
    expect(await allBookings(backend)).toHaveLength(1);
  });

  test('нормализация телефона и имени на сервере', async () => {
    const cases: [string, string][] = [
      ['+374 (91) 12-34-56', '+37491123456'],
      ['091 123 456', '091123456'],
      ['  +37455123456  ', '+37455123456'],
    ];
    let hour = 9;
    for (const [raw, expected] of cases) {
      const r = await createViaApi(backend, booking({ date, time: `${String(hour++).padStart(2, '0')}:00`, masterName: 'Мариам', phone: raw, clientName: '  Иван  ' }));
      expect(r.status, raw).toBe(201);
      expect((r.body as BookingDto).phone, raw).toBe(expected);
      expect((r.body as BookingDto).clientName).toBe('Иван');
      expect((r.body as BookingDto).source).toBe('администратор');
    }
  });

  test('серверная валидация: те же правила, что и в форме', async () => {
    const { today, now } = await serverToday(backend);
    const yesterday = addDays(today, -1);
    const bad = async (patch: Partial<ReturnType<typeof booking>>, field: string) => {
      const r = await createViaApi(backend, { ...booking({ date, time: '12:00', masterName: 'Анна' }), ...patch });
      expect(r.status, JSON.stringify(patch)).toBe(400);
      expect(r.body).toMatchObject({ error: 'validation' });
      expect((r.body as unknown as { fields: Record<string, string> }).fields).toHaveProperty(field);
    };
    await bad({ clientName: 'А' }, 'clientName');
    await bad({ clientName: 'Я'.repeat(101) }, 'clientName');
    await bad({ phone: '12+345' }, 'phone');
    await bad({ phone: '1234' }, 'phone');
    await bad({ phone: '(1) 2-34' }, 'phone');
    await bad({ phone: '1'.repeat(21) }, 'phone');
    await bad({ phone: 'abcde' }, 'phone');
    await bad({ serviceName: 'Нет такой' }, 'serviceName');
    await bad({ serviceName: '' }, 'serviceName');
    await bad({ masterName: 'Неизвестный' }, 'masterName');
    await bad({ date: yesterday }, 'date');
    await bad({ date: '2027-02-30' }, 'date');
    await bad({ date: 'вчера' }, 'date');
    await bad({ time: '24:00' }, 'time');
    await bad({ time: '9:5' }, 'time');
    // сегодняшний день, но время уже прошло
    await bad({ date: today, time: '00:00' }, 'time');
    // в воскресенье никто не работает; Арам не работает в понедельник
    let monday = addDays(today, 1);
    while (new Date(`${monday}T00:00:00Z`).getUTCDay() !== 1) {
      monday = addDays(monday, 1);
    }
    await bad({ date: monday, masterName: 'Арам' }, 'date');
    expect(await allBookings(backend)).toHaveLength(0);
    expect(now).toMatch(/^\d{4}-\d{2}-\d{2}T/);
  });

  test('безопасность API: без токена и с неверным токеном — 401, /health открыт', async () => {
    expect((await backend.api('GET', '/bookings', { token: null })).status).toBe(401);
    expect((await backend.api('GET', '/bookings', { token: 'nope' })).status).toBe(401);
    expect((await backend.api('POST', '/bookings', { token: 'nope', body: booking({ date, time: '12:00' }) })).status).toBe(401);
    expect((await backend.api('GET', '/health', { token: null })).status).toBe(200);
    expect(await allBookings(backend)).toHaveLength(0);
  });

  test('данные переживают перезапуск бэкенда (хранятся в xlsx)', async () => {
    const r = await createViaApi(backend, booking({ date, time: '12:00', masterName: 'Анна', phone: '+374 91 12-34-56' }));
    expect(r.status).toBe(201);
    await backend.stop();
    // перезапуск на тех же данных
    await backend.restartKeepingData();
    const stored = await allBookings(backend);
    expect(stored).toHaveLength(1);
    expect(stored[0]?.phone).toBe('+37491123456');
    // новая запись получает следующий номер
    const next = await createViaApi(backend, booking({ date, time: '13:00', masterName: 'Анна' }));
    expect((next.body as BookingDto).id).toBe(2);
  });
});
