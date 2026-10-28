import { existsSync, readdirSync } from 'node:fs';
import { join } from 'node:path';
import { expect, test } from '@playwright/test';
import { fillForm, launchApp, openNewBooking, row, statusList, toasts, type Launched } from './helpers/app';
import { API_TOKEN, Backend } from './helpers/backend';
import { allBookings, nextFriday, SERVICE, xlsxText } from './helpers/data';

test.describe('2. Создание записи через форму', () => {
  let backend: Backend;
  let launched: Launched;

  test.beforeAll(async () => {
    backend = await Backend.start();
  });
  test.afterAll(async () => {
    await backend.dispose();
  });
  test.beforeEach(async () => {
    await backend.reset();
    launched = await launchApp({ apiUrl: backend.url, token: API_TOKEN });
    await expect(statusList(launched.page)).toContainText('Хранилище');
  });
  test.afterEach(async () => {
    await launched.close();
  });

  test('услуга → дата → мастер → слот → клиент: запись в таблице, телефон нормализован, источник «администратор»', async () => {
    const { page } = launched;
    const date = await nextFriday(backend);
    await openNewBooking(page);
    const d = await fillForm(page, {
      service: SERVICE,
      date,
      master: 'Анна',
      slot: '12:00',
      name: 'Гаяне Петросян',
      phone: '+374 (91) 12-34-56',
    });
    await d.getByRole('button', { name: 'Записать' }).click();

    // диалог закрылся, тост и строка в таблице
    await expect(page.getByRole('dialog')).toHaveCount(0);
    await expect(toasts(page)).toContainText('Запись №1 сохранена.');
    await expect(toasts(page)).toContainText('Гаяне Петросян');
    const r = row(page, 'Гаяне Петросян');
    await expect(r).toHaveCount(1);
    await expect(r).toContainText('12:00');
    await expect(r).toContainText('Анна');
    await expect(r).toContainText(SERVICE);
    await expect(r).toContainText('Подтверждена');
    await expect(r).toContainText('администратор');
    await expect(r).toContainText('+374 91 123 456'); // группировка только для показа
    await expect(page.getByRole('status').filter({ hasText: 'Показано' })).toContainText(
      'Показано: 1 запись (активных 1)',
    );

    // в хранилище: нормализованный телефон, источник, без Telegram
    const stored = await allBookings(backend);
    expect(stored).toHaveLength(1);
    expect(stored[0]).toMatchObject({
      id: 1,
      clientName: 'Гаяне Петросян',
      phone: '+37491123456',
      source: 'администратор',
      status: 'CONFIRMED',
      masterName: 'Анна',
      serviceName: SERVICE,
      date,
      time: '12:00',
      telegramId: 0,
      durationMinutes: 30,
    });
    expect(stored[0]?.createdAt).toMatch(/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}/);

    // и физически в Excel-файле, рядом создан бэкап
    expect(existsSync(backend.excelFile)).toBe(true);
    const xml = xlsxText(backend.excelFile);
    expect(xml).toContain('+37491123456');
    expect(xml).toContain('Гаяне Петросян');
    expect(xml).not.toContain('12-34-56');
  });

  test('шаги формы зависят друг от друга: дата недоступна без услуги, слоты появляются после даты', async () => {
    const { page } = launched;
    const d = await openNewBooking(page);
    await expect(d.getByLabel('Дата')).toBeDisabled();
    await expect(d.getByLabel('Мастер')).toBeDisabled();
    await expect(d.getByText('Сначала выберите услугу и дату.')).toBeVisible();
    await d.getByLabel('Услуга').selectOption({ value: SERVICE });
    await expect(d.getByLabel('Дата')).toBeEnabled();
    const date = await nextFriday(backend);
    await d.getByLabel('Дата').selectOption({ value: date });
    await expect(d.getByLabel('Мастер')).toBeEnabled();
    // в пятницу работают все трое + «Любой мастер»
    await expect(d.getByLabel('Мастер').locator('option')).toHaveText([
      'Любой мастер',
      'Анна (10:00–19:00)',
      'Арам (11:00–20:00)',
      'Мариам (09:00–18:00)',
    ]);
    await expect(d.locator('label').filter({ hasText: /^12:00$/ })).toBeVisible();
    // выбор конкретного мастера сужает слоты: у Арама рабочий день с 11:00
    await d.getByLabel('Мастер').selectOption({ value: 'Арам' });
    await expect(d.locator('label').filter({ hasText: /^10:00$/ })).toHaveCount(0);
    await expect(d.locator('label').filter({ hasText: /^11:00$/ })).toBeVisible();
    // смена услуги сбрасывает выбранное время
    await d.locator('label').filter({ hasText: /^12:00$/ }).click();
    await d.getByLabel('Услуга').selectOption({ value: 'Стрижка женская' });
    await expect(d.getByRole('radio', { checked: true })).toHaveCount(0);
  });

  test('отмена в диалоге не создаёт запись', async () => {
    const { page } = launched;
    const date = await nextFriday(backend);
    await openNewBooking(page);
    const d = await fillForm(page, { service: SERVICE, date, slot: '12:00', name: 'Не создавать', phone: '+37491000000' });
    await d.getByRole('button', { name: 'Отмена' }).click();
    await expect(page.getByRole('dialog')).toHaveCount(0);
    expect(await allBookings(backend)).toHaveLength(0);
  });

  test('бэкап перед изменением: после записи в backups/ появляется копия', async () => {
    const date = await nextFriday(backend);
    const { page } = launched;
    await openNewBooking(page);
    const d = await fillForm(page, { service: SERVICE, date, slot: '13:00', name: 'Бэкап Тест', phone: '+37491000001' });
    await d.getByRole('button', { name: 'Записать' }).click();
    await expect(toasts(page)).toContainText('сохранена');
    const backups = join(backend.dir, 'backups');
    expect(existsSync(backups)).toBe(true);
    expect(readdirSync(backups).length).toBeGreaterThan(0);
  });
});
