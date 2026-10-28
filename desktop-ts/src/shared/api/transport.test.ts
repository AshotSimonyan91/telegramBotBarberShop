import { describe, expect, it, vi } from 'vitest';
import { buildUrl, createFetchTransport, isAllowedRequest, normalizeBaseUrl } from './transport';

describe('isAllowedRequest', () => {
  it.each([
    ['GET', '/bookings'],
    ['GET', '/bookings/12'],
    ['POST', '/bookings/12/cancel'],
    ['GET', '/dates'],
  ] as const)('%s %s разрешён', (method, path) => {
    expect(isAllowedRequest({ method, path })).toBe(true);
  });

  it.each([
    ['POST', '/services'],
    ['GET', '/../admin'],
    ['GET', '/bookings/abc'],
    ['GET', 'http://evil.example/'],
  ] as const)('%s %s запрещён', (method, path) => {
    expect(isAllowedRequest({ method, path })).toBe(false);
  });
});

describe('normalizeBaseUrl', () => {
  it('убирает завершающий слеш', () => {
    expect(normalizeBaseUrl(' http://127.0.0.1:8787/ ')).toBe('http://127.0.0.1:8787');
  });
  it('отклоняет другие протоколы и мусор', () => {
    expect(normalizeBaseUrl('file:///etc/passwd')).toBeNull();
    expect(normalizeBaseUrl('не адрес')).toBeNull();
    expect(normalizeBaseUrl('')).toBeNull();
    expect(normalizeBaseUrl('http://user:pass@host')).toBeNull();
  });
});

describe('buildUrl', () => {
  it('добавляет префикс /api/v1 и кодирует параметры', () => {
    expect(
      buildUrl('http://h:1', {
        method: 'GET',
        path: '/slots',
        query: { serviceName: 'Стрижка', date: '2026-10-30' },
      }),
    ).toBe('http://h:1/api/v1/slots?serviceName=%D0%A1%D1%82%D1%80%D0%B8%D0%B6%D0%BA%D0%B0&date=2026-10-30');
  });
});

describe('createFetchTransport', () => {
  it('передаёт токен везде, кроме /health', async () => {
    const fetchImpl = vi.fn<typeof fetch>(() => Promise.resolve(new Response('[]', { status: 200 })));
    const transport = createFetchTransport({
      getConnection: () => ({ baseUrl: 'http://h', token: 'secret' }),
      fetchImpl,
    });
    await transport({ method: 'GET', path: '/services' });
    await transport({ method: 'GET', path: '/health' });
    const headers = fetchImpl.mock.calls.map(([, init]) => (init?.headers ?? {}) as Record<string, string>);
    expect(headers[0]?.Authorization).toBe('Bearer secret');
    expect(headers[1]?.Authorization).toBeUndefined();
  });

  it('пустое тело ответа — null', async () => {
    const fetchImpl = vi.fn<typeof fetch>(() => Promise.resolve(new Response(null, { status: 503 })));
    const transport = createFetchTransport({
      getConnection: () => ({ baseUrl: 'http://h', token: 't' }),
      fetchImpl,
    });
    expect(await transport({ method: 'GET', path: '/status' })).toEqual({
      kind: 'response',
      status: 503,
      body: null,
      headers: {},
    });
  });

  it('передаёт только разрешённые заголовки ответа', async () => {
    const fetchImpl = vi.fn<typeof fetch>(() =>
      Promise.resolve(
        new Response('{}', {
          status: 200,
          headers: { 'X-Client-Notified': 'false', 'Set-Cookie': 'a=b', 'X-Other': '1' },
        }),
      ),
    );
    const transport = createFetchTransport({
      getConnection: () => ({ baseUrl: 'http://h', token: 't' }),
      fetchImpl,
    });
    const result = await transport({ method: 'POST', path: '/bookings/1/cancel' });
    expect(result).toMatchObject({ kind: 'response', headers: { 'x-client-notified': 'false' } });
    expect(result.kind === 'response' && Object.keys(result.headers)).toEqual(['x-client-notified']);
  });
});
