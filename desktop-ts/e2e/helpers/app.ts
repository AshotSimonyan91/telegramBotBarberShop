import { mkdtempSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import { _electron as electron, expect, type ElectronApplication, type Locator, type Page } from '@playwright/test';

const DESKTOP_ROOT = resolve(__dirname, '../..');

export interface LaunchOptions {
  apiUrl: string;
  /** Пустая строка — токен не задан. */
  token: string;
}

export interface Launched {
  app: ElectronApplication;
  page: Page;
  userData: string;
  close: () => Promise<void>;
}

/** Запускает собранное приложение (out/) с изолированной папкой userData и подключением к тестовому бэкенду. */
export async function launchApp({ apiUrl, token }: LaunchOptions): Promise<Launched> {
  const userData = mkdtempSync(join(tmpdir(), 'salon-e2e-userdata-'));
  const env: Record<string, string> = {};
  for (const [key, value] of Object.entries(process.env)) {
    if (value !== undefined && !key.startsWith('SALON_') && key !== 'ELECTRON_RENDERER_URL' && key !== 'ELECTRON_RUN_AS_NODE') {
      env[key] = value;
    }
  }
  env.SALON_API_URL = apiUrl;
  env.SALON_API_TOKEN = token;
  const app = await electron.launch({
    args: [`--user-data-dir=${userData}`, DESKTOP_ROOT],
    cwd: DESKTOP_ROOT,
    env,
  });
  const page = await app.firstWindow();
  await page.waitForLoadState('domcontentloaded');
  return {
    app,
    page,
    userData,
    close: async () => {
      await app.close().catch(() => undefined);
      rmSync(userData, { recursive: true, force: true });
    },
  };
}

// ---------- Страница «Записи» ----------

export function toasts(page: Page): Locator {
  return page.getByRole('region', { name: 'Уведомления' });
}

export function statusList(page: Page): Locator {
  return page.getByRole('list', { name: 'Состояние системы' });
}

export function row(page: Page, text: string | RegExp): Locator {
  return page.getByRole('row').filter({ hasText: text });
}

export async function refresh(page: Page): Promise<void> {
  await page.getByRole('button', { name: /Обновить|Обновление…/ }).click();
  await expect(page.getByRole('button', { name: 'Обновить', exact: true })).toBeEnabled();
}

export async function selectFilter(page: Page, label: string, option: string): Promise<void> {
  await page
    .getByRole('search', { name: 'Фильтры записей' })
    .locator('label')
    .filter({ hasText: label })
    .locator('select')
    .selectOption({ label: option });
}

export interface BookingForm {
  service: string;
  date: string;
  /** Имя мастера; не задано — «Любой мастер». */
  master?: string;
  /** Не задано — время не выбирается. */
  slot?: string;
  name: string;
  phone: string;
}

export function dialog(page: Page): Locator {
  return page.getByRole('dialog', { name: 'Новая запись' });
}

export async function openNewBooking(page: Page): Promise<Locator> {
  await page.getByRole('button', { name: 'Новая запись' }).click();
  const d = dialog(page);
  await expect(d.getByLabel('Услуга')).toBeVisible();
  return d;
}

/** Заполняет шаги формы по порядку: услуга → дата → мастер → время → имя/телефон. */
export async function fillForm(page: Page, form: BookingForm): Promise<Locator> {
  const d = dialog(page);
  await d.getByLabel('Услуга').selectOption({ value: form.service });
  await expect(d.getByLabel('Дата')).toBeEnabled();
  await d.getByLabel('Дата').selectOption({ value: form.date });
  if (form.master !== undefined) {
    await d.getByLabel('Мастер').selectOption({ value: form.master });
  }
  if (form.slot !== undefined) {
    // радиокнопка визуально скрыта (sr-only) — кликаем по её подписи
    await d.locator('label').filter({ hasText: new RegExp(`^${form.slot}$`) }).click();
  }
  await d.getByLabel('Имя клиента').fill(form.name);
  await d.getByLabel('Телефон').fill(form.phone);
  return d;
}
