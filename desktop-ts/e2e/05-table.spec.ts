import { expect, test, type Page } from '@playwright/test';
import { addDays, booking, createViaApi, nextFriday, serverToday, type BookingDto } from './helpers/data';
import { launchApp, refresh, row, selectFilter, statusList, type Launched } from './helpers/app';
import { API_TOKEN, Backend } from './helpers/backend';

test.describe('5. Таблица: фильтры, поиск, сортировка, автообновление', () => {
  let backend: Backend;
  let launched: Launched;
  let f1: string;
  let f2: string;
  let cancelledId: number;

  test.beforeAll(async () => {
    backend = await Backend.start();
  });
  test.afterAll(async () => {
    await backend.dispose();
  });
  test.beforeEach(async () => {
    await backend.reset();
    f1 = await nextFriday(backend);
    f2 = await nextFriday(backend, 1);
    const rows = [
      booking({ clientName: 'Гаяне Овсепян', phone: '+37491000001', serviceName: 'Борода', masterName: 'Анна', date: f1, time: '10:00' }),
      booking({ clientName: 'Борис Иванов', phone: '+37499222333', serviceName: 'Стрижка мужская', masterName: 'Арам', date: f1, time: '11:00' }),
      booking({ clientName: 'Давид Саркисян', phone: '+37455123456', serviceName: 'Окрашивание', masterName: 'Мариам', date: f1, time: '09:00' }),
      booking({ clientName: 'Анаит Мкртчян', phone: '+37477000009', serviceName: 'Стрижка женская', masterName: 'Анна', date: f2, time: '14:00' }),
      booking({ clientName: 'Отменённая Ольга', phone: '+37491555555', serviceName: 'Борода', masterName: 'Арам', date: f2, time: '12:00' }),
    ];
    let lastId = 0;
    for (const r of rows) {
      const res = await createViaApi(backend, r);
      expect(res.status).toBe(201);
      lastId = (res.body as BookingDto).id;
    }
    cancelledId = lastId;
    expect((await backend.api('POST', `/bookings/${cancelledId}/cancel`)).status).toBe(200);
    launched = await launchApp({ apiUrl: backend.url, token: API_TOKEN });
    await expect(statusList(launched.page)).toContainText('Хранилище');
    await expect(row(launched.page, 'Гаяне')).toHaveCount(1);
  });
  test.afterEach(async () => {
    await launched.close();
  });

  const names = (page: Page) => page.locator('tbody tr').evaluateAll((trs) => trs.map((tr) => tr.querySelectorAll('td')[2]?.textContent ?? ''));
  const footer = (page: Page) => page.getByRole('status').filter({ hasText: 'Показано' });

  test('по умолчанию: «Все предстоящие» + «Активные», сортировка по дате и времени', async () => {
    const { page } = launched;
    expect(await names(page)).toEqual(['Давид Саркисян', 'Гаяне Овсепян', 'Борис Иванов', 'Анаит Мкртчян']);
    await expect(footer(page)).toHaveText('Показано: 4 записи (активных 4)');
    await expect(page.getByRole('columnheader', { name: /Дата и время/ })).toHaveAttribute('aria-sort', 'ascending');
  });

  test('статус: активные / отменённые / все', async () => {
    const { page } = launched;
    await selectFilter(page, 'Статус', 'Отменённые');
    await expect(row(page, 'Отменённая Ольга')).toHaveCount(1);
    expect(await names(page)).toEqual(['Отменённая Ольга']);
    await expect(footer(page)).toHaveText('Показано: 1 запись (активных 0)');
    await selectFilter(page, 'Статус', 'Все статусы');
    await expect(page.locator('tbody tr')).toHaveCount(5);
    await expect(footer(page)).toHaveText('Показано: 5 записей (активных 4)');
    // у отменённой нет кнопки «Отменить»
    await expect(row(page, 'Отменённая Ольга').getByRole('button')).toHaveCount(0);
  });

  test('мастер', async () => {
    const { page } = launched;
    await selectFilter(page, 'Статус', 'Все статусы');
    await selectFilter(page, 'Мастер', 'Арам');
    await expect(page.locator('tbody tr')).toHaveCount(2);
    expect((await names(page)).sort()).toEqual(['Борис Иванов', 'Отменённая Ольга']);
    await selectFilter(page, 'Мастер', 'Мариам');
    await expect(page.locator('tbody tr')).toHaveCount(1);
    await expect(row(page, 'Давид')).toHaveCount(1);
  });

  test('поиск по имени, телефону, номеру; регистр; пустой результат', async () => {
    const { page } = launched;
    const search = page.getByRole('searchbox', { name: 'Поиск' });
    await search.fill('гаяне');
    await expect(page.locator('tbody tr')).toHaveCount(1);
    await expect(row(page, 'Гаяне Овсепян')).toHaveCount(1);

    await search.fill('99222333');
    await expect(page.locator('tbody tr')).toHaveCount(1);
    await expect(row(page, 'Борис Иванов')).toHaveCount(1);

    await search.fill('+374 55 123456');
    await expect(row(page, 'Давид Саркисян')).toHaveCount(1);
    await expect(page.locator('tbody tr')).toHaveCount(1);

    await search.fill('3'); // номер записи №3 (и, возможно, телефоны с тройкой)
    await expect(row(page, 'Давид Саркисян')).toHaveCount(1);

    await search.fill('несуществующий');
    await expect(page.getByText('Записей не найдено')).toBeVisible();
    await expect(footer(page)).toHaveText('Показано: 0 записей (активных 0)');

    await search.fill('');
    await expect(page.locator('tbody tr')).toHaveCount(4);
  });

  test('период: конкретный день, диапазон, «Прошедшие», неверный диапазон', async () => {
    const { page } = launched;
    const { today } = await serverToday(backend);

    await selectFilter(page, 'Период', 'Период…');
    const range = page.getByRole('search', { name: 'Фильтры записей' });
    await range.getByLabel('С', { exact: true }).fill(f1);
    await range.getByLabel('По', { exact: true }).fill(f1);
    await expect(page.locator('tbody tr')).toHaveCount(3);
    expect((await names(page)).sort()).toEqual(['Борис Иванов', 'Гаяне Овсепян', 'Давид Саркисян']);

    await range.getByLabel('С', { exact: true }).fill(f2);
    await range.getByLabel('По', { exact: true }).fill(f2);
    await expect(page.locator('tbody tr')).toHaveCount(1);
    await expect(row(page, 'Анаит')).toHaveCount(1);

    // «с» позже «по»
    await range.getByLabel('С', { exact: true }).fill(f2);
    await range.getByLabel('По', { exact: true }).fill(f1);
    await expect(page.getByRole('alert').filter({ hasText: 'Дата «с» позже даты «по».' })).toBeVisible();
    await expect(page.getByRole('button', { name: 'Обновить' })).toBeDisabled();
    await expect(page.getByText('Исправьте период')).toBeVisible();

    await selectFilter(page, 'Период', 'Прошедшие');
    await expect(page.getByText('Записей не найдено')).toBeVisible();

    await selectFilter(page, 'Период', 'Все');
    await expect(page.locator('tbody tr')).toHaveCount(4);

    await selectFilter(page, 'Период', 'Сегодня');
    await expect(page.getByText('Записей не найдено')).toBeVisible();

    await selectFilter(page, 'Период', 'Ближайшие 7 дней');
    if (f1 <= addDays(today, 6)) {
      await expect(page.locator('tbody tr')).toHaveCount(3);
    } else {
      await expect(page.getByText('Записей не найдено')).toBeVisible();
    }
  });

  test('сортировка по клиенту (по возрастанию/убыванию), номеру и дате', async () => {
    const { page } = launched;
    const header = (name: string) => page.getByRole('columnheader', { name });
    await header('Клиент').getByRole('button').click();
    await expect(header('Клиент')).toHaveAttribute('aria-sort', 'ascending');
    expect(await names(page)).toEqual(['Анаит Мкртчян', 'Борис Иванов', 'Гаяне Овсепян', 'Давид Саркисян']);
    await header('Клиент').getByRole('button').click();
    await expect(header('Клиент')).toHaveAttribute('aria-sort', 'descending');
    expect(await names(page)).toEqual(['Давид Саркисян', 'Гаяне Овсепян', 'Борис Иванов', 'Анаит Мкртчян']);

    await header('№').getByRole('button').click();
    expect(await names(page)).toEqual(['Гаяне Овсепян', 'Борис Иванов', 'Давид Саркисян', 'Анаит Мкртчян']);
    await header('№').getByRole('button').click();
    expect(await names(page)).toEqual(['Анаит Мкртчян', 'Давид Саркисян', 'Борис Иванов', 'Гаяне Овсепян']);

    await header('Дата и время').getByRole('button').click();
    await header('Дата и время').getByRole('button').click();
    expect(await names(page)).toEqual(['Анаит Мкртчян', 'Борис Иванов', 'Гаяне Овсепян', 'Давид Саркисян']);

    await header('Мастер').getByRole('button').click();
    const masters = await page.locator('tbody tr').evaluateAll((trs) => trs.map((tr) => tr.querySelectorAll('td')[5]?.textContent ?? ''));
    expect(masters).toEqual([...masters].sort((a, b) => a.localeCompare(b, 'ru')));
  });

  test('кнопка «Обновить» подхватывает запись, созданную через API', async () => {
    const { page } = launched;
    const res = await createViaApi(backend, booking({ clientName: 'Через Апи', date: f1, time: '15:00', masterName: 'Анна' }));
    expect(res.status).toBe(201);
    await expect(row(page, 'Через Апи')).toHaveCount(0);
    await refresh(page);
    await expect(row(page, 'Через Апи')).toHaveCount(1);
    await expect(footer(page)).toHaveText('Показано: 5 записей (активных 5)');
  });

  test('автообновление (раз в 30 с) подхватывает запись, созданную через API', async () => {
    test.setTimeout(90_000);
    const { page } = launched;
    await createViaApi(backend, booking({ clientName: 'Автообновление', date: f1, time: '16:00', masterName: 'Анна' }));
    await expect(row(page, 'Автообновление')).toHaveCount(1, { timeout: 45_000 });
  });

  test('отмена через API видна после обновления: строка исчезает из «Активных»', async () => {
    const { page } = launched;
    await backend.api('POST', '/bookings/1/cancel');
    await refresh(page);
    await expect(row(page, 'Гаяне')).toHaveCount(0);
    await expect(footer(page)).toHaveText('Показано: 3 записи (активных 3)');
  });
});
