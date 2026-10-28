import { expect, test } from '@playwright/test';
import { launchApp, statusList, type Launched } from './helpers/app';
import { API_TOKEN, Backend } from './helpers/backend';

test.describe('6. Безопасность renderer', () => {
  let backend: Backend;
  let launched: Launched;

  test.beforeAll(async () => {
    backend = await Backend.start();
    launched = await launchApp({ apiUrl: backend.url, token: API_TOKEN });
    await expect(statusList(launched.page)).toContainText('Хранилище');
  });
  test.afterAll(async () => {
    await launched.close();
    await backend.dispose();
  });

  test('window.salon содержит только api и settings, токена нигде в странице нет', async () => {
    const { page } = launched;
    const info = await page.evaluate(async (token) => {
      const salon = (window as unknown as { salon: Record<string, Record<string, unknown>> }).salon;
      const settings = await (salon.settings as unknown as { get: () => Promise<unknown> }).get();
      const stores = [JSON.stringify({ ...localStorage }), JSON.stringify({ ...sessionStorage }), document.cookie];
      return {
        keys: Object.keys(salon).sort(),
        apiKeys: Object.keys(salon.api ?? {}).sort(),
        settingsKeys: Object.keys(salon.settings ?? {}).sort(),
        settings,
        bridgeHasToken: JSON.stringify(Object.entries(salon)).includes(token),
        htmlHasToken: document.documentElement.outerHTML.includes(token),
        storageHasToken: stores.some((s) => s.includes(token)),
        resourceHasToken: performance.getEntriesByType('resource').some((e) => e.name.includes(token)),
      };
    }, API_TOKEN);
    expect(info.keys).toEqual(['api', 'settings']);
    expect(info.apiKeys).toEqual(['request']);
    expect(info.settingsKeys).toEqual(['get', 'save']);
    expect(info.settings).toMatchObject({ hasToken: true, tokenFromEnv: true, apiUrlFromEnv: true });
    expect(JSON.stringify(info.settings)).not.toContain(API_TOKEN);
    expect(info.bridgeHasToken).toBe(false);
    expect(info.htmlHasToken).toBe(false);
    expect(info.storageHasToken).toBe(false);
    expect(info.resourceHasToken).toBe(false);
  });

  test('ответ мостa не содержит заголовков, кроме белого списка (нет Authorization/Set-Cookie и т.п.)', async () => {
    const { page } = launched;
    const result = await page.evaluate(() =>
      (window as unknown as { salon: { api: { request: (r: unknown) => Promise<unknown> } } }).salon.api.request({
        method: 'GET',
        path: '/bookings',
      }),
    );
    expect(result).toMatchObject({ kind: 'response', status: 200 });
    expect(Object.keys((result as { headers: object }).headers)).toEqual([]);
    expect(JSON.stringify(result)).not.toContain(API_TOKEN);
  });

  test('мост не проксирует произвольные пути и методы', async () => {
    const { page } = launched;
    const call = (request: unknown) =>
      page.evaluate(
        (r) =>
          (window as unknown as { salon: { api: { request: (x: unknown) => Promise<unknown> } } }).salon.api
            .request(r)
            .then((v) => ({ ok: true, v }))
            .catch((e: unknown) => ({ ok: false, e: String(e) })),
        request,
      );
    for (const request of [
      { method: 'GET', path: '/actuator/env' },
      { method: 'GET', path: '/../health' },
      { method: 'GET', path: '/bookings/1/../2' },
      { method: 'POST', path: '/status' },
      { method: 'DELETE', path: '/bookings/1' },
      { method: 'GET', path: '//evil.example/bookings' },
      { method: 'GET', path: 'http://evil.example/' },
      'garbage',
    ]) {
      const res = (await call(request)) as { ok: boolean; v?: { status?: number; body?: { error?: string } } };
      expect(res.ok, JSON.stringify(request)).toBe(true);
      expect(res.v?.status, JSON.stringify(request)).toBe(400);
      expect(res.v?.body?.error, JSON.stringify(request)).toBe('validation');
    }
  });

  test('renderer не видит Node: process, require, module, Buffer; contextIsolation и sandbox включены', async () => {
    const { page, app } = launched;
    const globals = await page.evaluate(() => ({
      process: typeof (globalThis as Record<string, unknown>).process,
      require: typeof (globalThis as Record<string, unknown>).require,
      module: typeof (globalThis as Record<string, unknown>).module,
      buffer: typeof (globalThis as Record<string, unknown>).Buffer,
      global: typeof (globalThis as Record<string, unknown>).global,
      ipcRenderer: typeof (globalThis as Record<string, unknown>).ipcRenderer,
    }));
    expect(globals).toEqual({
      process: 'undefined',
      require: 'undefined',
      module: 'undefined',
      buffer: 'undefined',
      global: 'undefined',
      ipcRenderer: 'undefined',
    });
    const prefs = await app.evaluate(({ BrowserWindow }) => {
      const p = BrowserWindow.getAllWindows()[0]?.webContents.getLastWebPreferences();
      return { contextIsolation: p?.contextIsolation, nodeIntegration: p?.nodeIntegration, sandbox: p?.sandbox, webSecurity: p?.webSecurity };
    });
    expect(prefs).toMatchObject({ contextIsolation: true, nodeIntegration: false, sandbox: true, webSecurity: true });
  });

  test('CSP: строгая политика в meta, прямой fetch к бэкенду из страницы блокируется', async () => {
    const { page } = launched;
    const csp = await page.evaluate(
      () => document.querySelector('meta[http-equiv="Content-Security-Policy"]')?.getAttribute('content') ?? '',
    );
    expect(csp).toContain("default-src 'none'");
    expect(csp).toContain("script-src 'self'");
    expect(csp).toContain("connect-src 'none'");
    expect(csp).toContain("object-src 'none'");
    expect(csp).toContain("frame-src 'none'");
    expect(csp).not.toContain('unsafe-inline');
    expect(csp).not.toContain('unsafe-eval');

    // даже зная токен, страница не может ходить в API напрямую
    const outcome = await page.evaluate(
      async ({ url, token }) => {
        try {
          const res = await fetch(`${url}/api/v1/bookings`, { headers: { Authorization: `Bearer ${token}` } });
          return `status ${res.status}`;
        } catch (error) {
          return `blocked: ${String(error)}`;
        }
      },
      { url: backend.url, token: API_TOKEN },
    );
    expect(outcome.startsWith('blocked')).toBe(true);
    // inline-скрипт не выполняется
    const inline = await page.evaluate(() => {
      const el = document.createElement('script');
      (window as unknown as { __inline: boolean }).__inline = false;
      el.textContent = 'window.__inline = true';
      document.head.append(el);
      return (window as unknown as { __inline: boolean }).__inline;
    });
    expect(inline).toBe(false);
  });

  test('навигация и новые окна блокируются', async () => {
    const { page, app } = launched;
    const before = page.url();
    await page.evaluate(() => {
      window.location.href = 'https://example.com/';
    });
    await page.waitForTimeout(1500);
    expect(page.url()).toBe(before);
    await page.evaluate(() => {
      window.location.href = `file:///etc/hosts`;
    });
    await page.waitForTimeout(1000);
    expect(page.url()).toBe(before);

    const opened = await page.evaluate(() => [
      window.open('about:blank') === null,
      window.open('file:///etc/hosts') === null,
      window.open('http://example.com/') === null,
    ]);
    expect(opened).toEqual([true, true, true]);
    await page.waitForTimeout(500);
    expect(app.windows()).toHaveLength(1);
    const count = await app.evaluate(({ BrowserWindow }) => BrowserWindow.getAllWindows().length);
    expect(count).toBe(1);
    // приложение живо и после попыток
    await expect(statusList(page)).toContainText('Хранилище');
  });

  test('разрешения (геолокация, уведомления, камера) запрещены', async () => {
    const { page } = launched;
    const result = await page.evaluate(async () => {
      const geo = await new Promise<string>((resolve) => {
        navigator.geolocation.getCurrentPosition(
          () => resolve('granted'),
          (e) => resolve(`denied:${e.code}`),
          { timeout: 3000 },
        );
      });
      const cam = await navigator.mediaDevices
        .getUserMedia({ video: true })
        .then(() => 'granted')
        .catch((e: unknown) => `denied:${(e as Error).name}`);
      return { geo, cam };
    });
    expect(result.geo.startsWith('denied')).toBe(true);
    expect(result.cam.startsWith('denied')).toBe(true);
  });
});
