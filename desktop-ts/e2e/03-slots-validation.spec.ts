import { expect, test } from '@playwright/test';
import { fillForm, launchApp, openNewBooking, row, statusList, toasts, dialog, type Launched } from './helpers/app';
import { API_TOKEN, Backend } from './helpers/backend';
import {
  allBookings,
  booking,
  createViaApi,
  MASTERS,
  nextFriday,
  serverToday,
  SERVICE,
} from './helpers/data';

test.describe('3. «Любой мастер», занятый слот, валидация', () => {
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

  test('«Любой мастер»: запись получает конкретного мастера, повторная на то же время — другого', async () => {
    const { page } = launched;
    const date = await nextFriday(backend);
    for (const [i, name] of ['Первый Клиент', 'Второй Клиент'].entries()) {
      await openNewBooking(page);
      const d = await fillForm(page, { service: SERVICE, date, slot: '12:00', name, phone: `+3749100000${i}` });
      await expect(d.getByLabel('Мастер')).toHaveValue('');
      await d.getByRole('button', { name: 'Записать' }).click();
      await expect(toasts(page)).toContainText(`Запись №${i + 1} сохранена`);
    }
    const stored = await allBookings(backend);
    expect(stored).toHaveLength(2);
    const masters = stored.map((b) => b.masterName);
    expect(new Set(masters).size).toBe(2);
    for (const m of masters) {
      expect(MASTERS).toContain(m);
    }
    // таблица показывает мастера, назначенного сервером
    for (const b of stored) {
      await expect(row(page, b.clientName)).toContainText(b.masterName);
    }
  });

  test('занятый слот: запись, созданная через API между открытием и отправкой, даёт slot_taken и обновляет слоты', async () => {
    const { page } = launched;
    const date = await nextFriday(backend);
    await openNewBooking(page);
    const d = await fillForm(page, {
      service: SERVICE,
      date,
      master: 'Анна',
      slot: '12:00',
      name: 'Опоздавший',
      phone: '+37491111111',
    });
    // пока диалог открыт, слот занимает другой клиент
    const other = await createViaApi(
      backend,
      booking({ date, time: '12:00', masterName: 'Анна', clientName: 'Занявший Слот' }),
    );
    expect(other.status).toBe(201);

    await d.getByRole('button', { name: 'Записать' }).click();
    const alert = d.getByRole('alert');
    await expect(alert).toContainText('Список свободного времени обновлён.');
    // диалог остаётся открытым, слот пропал из списка, выбор времени сброшен
    await expect(dialog(page)).toBeVisible();
    await expect(d.locator('label').filter({ hasText: /^12:00$/ })).toHaveCount(0);
    await expect(d.getByRole('radio', { checked: true })).toHaveCount(0);
    // новой записи не появилось
    expect((await allBookings(backend)).map((b) => b.clientName)).toEqual(['Занявший Слот']);

    // пользователь выбирает другое время и успешно записывается
    await d.locator('label').filter({ hasText: /^13:00$/ }).click();
    await d.getByRole('button', { name: 'Записать' }).click();
    await expect(toasts(page)).toContainText('Запись №2 сохранена');
    await expect(row(page, 'Опоздавший')).toContainText('13:00');
  });

  test('валидация: короткое имя, телефон «12+345», пустые поля — ошибки у полей, запрос не уходит', async () => {
    const { page } = launched;
    const date = await nextFriday(backend);
    const d = await openNewBooking(page);

    // пустая форма
    await d.getByRole('button', { name: 'Записать' }).click();
    await expect(d.getByText('Выберите услугу.', { exact: true })).toBeVisible();
    await expect(d.getByText('Введите имя клиента.')).toBeVisible();
    await expect(d.getByText('Введите телефон.')).toBeVisible();

    await fillForm(page, { service: SERVICE, date, slot: '12:00', name: 'А', phone: '12+345' });
    await d.getByRole('button', { name: 'Записать' }).click();
    await expect(d.getByText('Имя — не короче 2 символов.')).toBeVisible();
    await expect(d.getByText('Телефон: от 5 до 20 символов', { exact: false }).first()).toBeVisible();
    await expect(d.getByLabel('Телефон')).toHaveAttribute('aria-invalid', 'true');
    expect(await allBookings(backend)).toHaveLength(0);

    // исправляем — запись проходит
    await d.getByLabel('Имя клиента').fill('Ая');
    await d.getByLabel('Телефон').fill('12345');
    await d.getByRole('button', { name: 'Записать' }).click();
    await expect(toasts(page)).toContainText('Запись №1 сохранена');
    expect((await allBookings(backend))[0]).toMatchObject({ clientName: 'Ая', phone: '12345' });
  });

  test('телефон с четырьмя цифрами и слишком длинное имя не принимаются', async () => {
    const { page } = launched;
    const date = await nextFriday(backend);
    await openNewBooking(page);
    const d = await fillForm(page, { service: SERVICE, date, slot: '12:00', name: 'Нормальное имя', phone: '(1) 2-34' });
    await d.getByRole('button', { name: 'Записать' }).click();
    await expect(d.getByText('В телефоне должно быть не меньше 5 цифр.')).toBeVisible();
    expect(await allBookings(backend)).toHaveLength(0);
  });

  test('даты в прошлом в форме недоступны (только сегодня и позже)', async () => {
    const { page } = launched;
    const { today } = await serverToday(backend);
    const d = await openNewBooking(page);
    await d.getByLabel('Услуга').selectOption({ value: SERVICE });
    await expect(d.getByLabel('Дата')).toBeEnabled();
    const values = await d.getByLabel('Дата').locator('option').evaluateAll((opts) =>
      opts.map((o) => (o as HTMLOptionElement).value).filter((v) => v !== ''),
    );
    expect(values.length).toBeGreaterThan(0);
    for (const v of values) {
      expect(v >= today).toBe(true);
    }
    // выбрать вчерашнюю дату нельзя
    const yesterday = new Date(Date.parse(`${today}T00:00:00Z`) - 86_400_000).toISOString().slice(0, 10);
    await expect(d.getByLabel('Дата').locator(`option[value="${yesterday}"]`)).toHaveCount(0);
  });

  test('день, где слотов нет (мастер не работает): мастер в списке не предлагается', async () => {
    const { page } = launched;
    const d = await openNewBooking(page);
    await d.getByLabel('Услуга').selectOption({ value: SERVICE });
    // понедельник: Арам не работает
    const { today } = await serverToday(backend);
    let monday = new Date(Date.parse(`${today}T00:00:00Z`) + 86_400_000);
    while (monday.getUTCDay() !== 1) {
      monday = new Date(monday.getTime() + 86_400_000);
    }
    const iso = monday.toISOString().slice(0, 10);
    await d.getByLabel('Дата').selectOption({ value: iso });
    await expect(d.getByLabel('Мастер').locator('option')).toHaveText(['Любой мастер', 'Анна (10:00–19:00)', 'Мариам (09:00–18:00)']);
  });
});
