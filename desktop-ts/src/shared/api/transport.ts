import { z } from 'zod';

/**
 * Транспорт HTTP-запросов к API. В приложении запрос выполняет main-процесс (там же хранится токен),
 * renderer получает только статус и разобранное тело ответа. В тестах тот же код работает через MSW.
 */

export const API_PREFIX = '/api/v1';
export const REQUEST_TIMEOUT_MS = 10_000;

export const apiRequestSchema = z.object({
  method: z.enum(['GET', 'POST']),
  path: z.string().max(200),
  query: z.record(z.string(), z.string().max(500)).optional(),
  body: z.unknown().optional(),
});

export type ApiRequest = z.infer<typeof apiRequestSchema>;

export type TransportResult =
  | { kind: 'response'; status: number; body: unknown }
  | { kind: 'network-error'; message: string }
  | { kind: 'not-configured' };

export type Transport = (request: ApiRequest) => Promise<TransportResult>;

export interface ApiConnection {
  baseUrl: string;
  token: string | null;
}

/** Разрешённые операции: main-процесс не проксирует произвольные адреса. */
const ALLOWED: ReadonlyArray<{ method: ApiRequest['method']; path: RegExp }> = [
  { method: 'GET', path: /^\/health$/ },
  { method: 'GET', path: /^\/status$/ },
  { method: 'GET', path: /^\/services$/ },
  { method: 'GET', path: /^\/masters$/ },
  { method: 'GET', path: /^\/bookings$/ },
  { method: 'GET', path: /^\/bookings\/\d{1,12}$/ },
  { method: 'POST', path: /^\/bookings$/ },
  { method: 'POST', path: /^\/bookings\/\d{1,12}\/cancel$/ },
  { method: 'GET', path: /^\/slots$/ },
  { method: 'GET', path: /^\/dates$/ },
];

export function isAllowedRequest(request: ApiRequest): boolean {
  return ALLOWED.some((rule) => rule.method === request.method && rule.path.test(request.path));
}

/** Адрес API без завершающего «/»; допускаются только http и https. */
export function normalizeBaseUrl(raw: string): string | null {
  const trimmed = raw.trim();
  if (trimmed === '') {
    return null;
  }
  try {
    const url = new URL(trimmed);
    if (url.protocol !== 'http:' && url.protocol !== 'https:') {
      return null;
    }
    if (url.search !== '' || url.hash !== '' || url.username !== '' || url.password !== '') {
      return null;
    }
    return url.toString().replace(/\/+$/, '');
  } catch {
    return null;
  }
}

export function buildUrl(baseUrl: string, request: ApiRequest): string {
  const url = new URL(`${baseUrl.replace(/\/+$/, '')}${API_PREFIX}${request.path}`);
  for (const [key, value] of Object.entries(request.query ?? {})) {
    url.searchParams.set(key, value);
  }
  return url.toString();
}

async function readBody(response: Response): Promise<unknown> {
  const text = await response.text();
  if (text.trim() === '') {
    return null;
  }
  try {
    return JSON.parse(text) as unknown;
  } catch {
    return text;
  }
}

function describeNetworkError(error: unknown): string {
  if (error instanceof Error && (error.name === 'TimeoutError' || error.name === 'AbortError')) {
    return 'Сервер не ответил вовремя.';
  }
  return 'Не удалось подключиться к серверу.';
}

export interface FetchTransportOptions {
  getConnection: () => ApiConnection | null;
  fetchImpl?: typeof fetch;
  timeoutMs?: number;
}

export function createFetchTransport(options: FetchTransportOptions): Transport {
  const timeoutMs = options.timeoutMs ?? REQUEST_TIMEOUT_MS;
  return async (request) => {
    const connection = options.getConnection();
    if (connection === null) {
      return { kind: 'not-configured' };
    }
    const fetchImpl = options.fetchImpl ?? fetch;
    const headers: Record<string, string> = { Accept: 'application/json' };
    if (connection.token !== null && connection.token !== '' && request.path !== '/health') {
      headers.Authorization = `Bearer ${connection.token}`;
    }
    let body: string | undefined;
    if (request.method === 'POST') {
      headers['Content-Type'] = 'application/json; charset=utf-8';
      body = JSON.stringify(request.body ?? {});
    }
    try {
      const response = await fetchImpl(buildUrl(connection.baseUrl, request), {
        method: request.method,
        headers,
        body,
        redirect: 'error',
        signal: AbortSignal.timeout(timeoutMs),
      });
      return { kind: 'response', status: response.status, body: await readBody(response) };
    } catch (error) {
      return { kind: 'network-error', message: describeNetworkError(error) };
    }
  };
}
