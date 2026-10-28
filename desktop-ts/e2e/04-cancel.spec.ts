import { expect, test } from '@playwright/test';
import { launchApp, refresh, row, selectFilter, statusList, toasts, openNewBooking, dialog, type Launched } from './helpers/app';
import { API_TOKEN, Backend } from './helpers/backend';
import { allBookings, booking, createViaApi, freeSlots, nextFriday, SERVICE, type BookingDto } from './helpers/data';

test.describe('4. Отмена записи', () => {
  let backend: Backend;
  let launched: Launched;
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
  test.afterEach(async () => {
    await launched.close();
  });

  async function open(): Promise<void> {
    launched = await launchApp({ apiUrl: backend.url, token: API_TOKEN });
    await expect(statusList(launched.page)).toContainText('Хранилище');
  }

  test('подтверждение, тост, статус «Отменена», слот снова свободен', async () => {
    const created = await createViaApi(backend, booking({ date, time: '12:00', masterName: 'Анна', clientName: 'Отменяемый' }));
    expect(created.status).toBe(201);
    expect(await freeSlots(backend, SERVICE, date, 'Анна')).not.toContain('12:00');
    await open();
    const { page } = launched;

    // отказ от отмены ничего не меняет
    await row(page, 'Отменяемый').getByRole('button', { name: /Отменить запись №1/ }).click();
    const confirm = page.getByRole('dialog', { name: 'Отменить запись №1?' });
    await expect(confirm).toContainText('Отменяемый');
    await expect(confirm).toContainText('+374 91 123 456');
    await expect(confirm.getByText('Клиент записывался через Telegram')).toHaveCount(0);
    await confirm.getByRole('button', { name: 'Не отменять' }).click();
    await expect(page.getByRole('dialog')).toHaveCount(0);
    expect((await allBookings(backend))[0]?.status).toBe('CONFIRMED');

    // подтверждение
    await row(page, 'Отменяемый').getByRole('button', { name: /Отменить запись №1/ }).click();
    await page.getByRole('dialog', { name: 'Отменить запись №1?' }).getByRole('button', { name: 'Отменить запись', exact: true }).click();
    await expect(toasts(page)).toContainText('Запись отменена.');
    await expect(toasts(page)).toContainText('№1');
    await expect(page.getByRole('dialog')).toHaveCount(0);

    // фильтр «Активные» (по умолчанию) её уже не показывает; в «Все статусы» — «Отменена» без кнопки отмены
    await expect(row(page, 'Отменяемый')).toHaveCount(0);
    await selectFilter(page, 'Статус', 'Все статусы');
    const r = row(page, 'Отменяемый');
    await expect(r).toContainText('Отменена');
    await expect(r.getByRole('button', { name: /Отменить/ })).toHaveCount(0);

    // в хранилище и в слотах
    expect((await allBookings(backend))[0]?.status).toBe('CANCELLED');
    expect(await freeSlots(backend, SERVICE, date, 'Анна')).toContain('12:00');

    // и в форме новой записи слот снова доступен
    await openNewBooking(page);
    const d = dialog(page);
    await d.getByLabel('Услуга').selectOption({ value: SERVICE });
    await d.getByLabel('Дата').selectOption({ value: date });
    await d.getByLabel('Мастер').selectOption({ value: 'Анна' });
    await expect(d.locator('label').filter({ hasText: /^12:00$/ })).toBeVisible();
  });

  test('повторная отмена: запись уже отменена через API → информационный тост, а не ошибка', async () => {
    await createViaApi(backend, booking({ date, time: '12:00', masterName: 'Анна', clientName: 'Двойная Отмена' }));
    await open();
    const { page } = launched;
    await row(page, 'Двойная Отмена').getByRole('button', { name: /Отменить запись №1/ }).click();
    // между открытием диалога и подтверждением запись отменяет кто-то другой
    const first = await backend.api('POST', '/bookings/1/cancel');
    expect(first.status).toBe(200);
    await page.getByRole('dialog', { name: 'Отменить запись №1?' }).getByRole('button', { name: 'Отменить запись', exact: true }).click();
    await expect(toasts(page)).toContainText('Запись №1 уже была отменена.');
    await expect(page.getByRole('dialog')).toHaveCount(0);
    // таблица всё равно обновлена: запись исчезла из активных
    await expect(row(page, 'Двойная Отмена')).toHaveCount(0, { timeout: 10_000 });
  });

  test('API: повторная отмена → 409 already_cancelled, несуществующая → 404; для записи салона нет заголовка X-Client-Notified', async () => {
    const created = await createViaApi(backend, booking({ date, time: '12:00', masterName: 'Анна' }));
    const id = (created.body as BookingDto).id;
    const first = await backend.api<BookingDto>('POST', `/bookings/${id}/cancel`);
    expect(first.status).toBe(200);
    expect(first.body.status).toBe('CANCELLED');
    expect(first.headers.has('x-client-notified')).toBe(false);
    const second = await backend.api<{ error: string }>('POST', `/bookings/${id}/cancel`);
    expect(second.status).toBe(409);
    expect(second.body.error).toBe('already_cancelled');
    const missing = await backend.api<{ error: string }>('POST', '/bookings/99999/cancel');
    expect(missing.status).toBe(404);
    expect(missing.body.error).toBe('not_found');
  });

  test('отменённая запись освобождает слот: тот же мастер и время можно занять снова', async () => {
    const a = await createViaApi(backend, booking({ date, time: '12:00', masterName: 'Анна' }));
    expect(a.status).toBe(201);
    expect((await createViaApi(backend, booking({ date, time: '12:00', masterName: 'Анна' }))).status).toBe(409);
    await backend.api('POST', `/bookings/${(a.body as BookingDto).id}/cancel`);
    expect((await createViaApi(backend, booking({ date, time: '12:00', masterName: 'Анна' }))).status).toBe(201);
    await open();
    await refresh(launched.page);
  });
});
