import { expect, test } from '@playwright/test';
import { launchApp, statusList, type Launched } from './helpers/app';
import { API_TOKEN, Backend } from './helpers/backend';

test.describe('1. Старт и состояние подключения', () => {
  let backend: Backend;
  let launched: Launched | null = null;

  test.beforeAll(async () => {
    backend = await Backend.start();
  });
  test.afterAll(async () => {
    await backend.dispose();
  });
  test.afterEach(async () => {
    await launched?.close();
    launched = null;
  });

  test('с токеном: индикатор показывает бота и хранилище, баннера нет', async () => {
    launched = await launchApp({ apiUrl: backend.url, token: API_TOKEN });
    const { page } = launched;
    await expect(page).toHaveTitle('Салон — записи');
    await expect(page.getByRole('tab', { name: 'Записи' })).toHaveAttribute('aria-selected', 'true');
    const status = statusList(page);
    await expect(status).toContainText('Бот: @AshotSimonyanBot');
    await expect(status).toContainText('Хранилище: e2e.xlsx');
    await expect(page.getByRole('alert')).toHaveCount(0);
    // пустая база: таблицы нет, есть подсказка
    await expect(page.getByText('Записей не найдено')).toBeVisible();
  });

  test('без токена: открыты «Настройки», индикатора и баннера нет', async () => {
    launched = await launchApp({ apiUrl: backend.url, token: '' });
    const { page } = launched;
    await expect(page.getByRole('heading', { name: 'Подключение к API' })).toBeVisible();
    await expect(page.getByRole('tab', { name: 'Настройки' })).toHaveAttribute('aria-selected', 'true');
    await expect(page.getByText('Токен ещё не задан.')).toBeVisible();
    await expect(statusList(page)).toHaveCount(0);
    await expect(page.getByLabel('Адрес API')).toHaveValue(backend.url);
    await expect(page.getByLabel('Адрес API')).toHaveAttribute('readonly', '');
  });

  test('неверный токен: баннер 401 и кнопка «Открыть настройки» ведёт в настройки', async () => {
    launched = await launchApp({ apiUrl: backend.url, token: 'wrong-token' });
    const { page } = launched;
    const banner = page.getByRole('alert').filter({ hasText: 'Нет связи с API' });
    await expect(banner).toContainText('Сервер отклонил токен API');
    await expect(statusList(page)).toContainText('API: неверный токен');
    await banner.getByRole('button', { name: 'Открыть настройки' }).click();
    await expect(page.getByRole('heading', { name: 'Подключение к API' })).toBeVisible();
  });

  test('бэкенд остановлен: баннер «Нет связи», после запуска «Повторить» восстанавливает работу', async () => {
    const own = await Backend.start();
    try {
      launched = await launchApp({ apiUrl: own.url, token: API_TOKEN });
      const { page } = launched;
      await expect(statusList(page)).toContainText('Бот: @AshotSimonyanBot');
      await own.stop();
      // статус опрашивается раз в 15 с (STATUS_REFRESH_MS) — ждём очередного опроса
      const banner = page.getByRole('alert').filter({ hasText: 'Нет связи с API' });
      await expect(banner).toBeVisible({ timeout: 40_000 });
      await expect(banner).toContainText('Не удалось подключиться к серверу');
      await expect(statusList(page)).toContainText('API: недоступен');
      // кнопки настроек для сетевой ошибки нет
      await expect(banner.getByRole('button', { name: 'Открыть настройки' })).toHaveCount(0);

      // поднимаем бэкенд на тот же порт и жмём «Повторить»
      await own.reset();
      await banner.getByRole('button', { name: 'Повторить' }).click();
      await expect(page.getByRole('alert').filter({ hasText: 'Нет связи с API' })).toHaveCount(0);
      await expect(statusList(page)).toContainText('Хранилище: e2e.xlsx');
    } finally {
      await launched?.close();
      launched = null;
      await own.dispose();
    }
  });
});
