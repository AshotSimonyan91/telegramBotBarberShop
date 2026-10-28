import type { z } from 'zod';
import {
  apiErrorBodySchema,
  apiErrorCodeSchema,
  bookingSchema,
  datesSchema,
  healthSchema,
  masterSchema,
  serviceSchema,
  slotsSchema,
  statusSchema,
  type ApiErrorCode,
  type Booking,
  type BookingsQuery,
  type CreateBookingRequest,
  type DatesQuery,
  type SlotsQuery,
} from './schemas';
import type { ApiRequest, ResponseHeaders, Transport } from './transport';

export type ApiErrorKind = 'http' | 'network' | 'invalid-response' | 'not-configured';

/** Ошибка обращения к API. `message` — готовый русский текст для пользователя. */
export class ApiError extends Error {
  override readonly name = 'ApiError';

  constructor(
    readonly kind: ApiErrorKind,
    message: string,
    readonly status: number | null = null,
    readonly code: ApiErrorCode | null = null,
    readonly fields: Readonly<Record<string, string>> = {},
  ) {
    super(message);
  }

  get isUnauthorized(): boolean {
    return this.status === 401 || this.code === 'unauthorized';
  }
}

export function isApiError(error: unknown): error is ApiError {
  return error instanceof ApiError;
}

const STATUS_MESSAGES: Readonly<Record<number, string>> = {
  400: 'Проверьте введённые данные.',
  401: 'Неверный токен API. Проверьте настройки.',
  404: 'Запись не найдена.',
  409: 'Конфликт данных. Обновите список и повторите.',
  503: 'Хранилище занято. Повторите через несколько секунд.',
};

/**
 * Общее сообщение, когда сервер не знает запрошенный путь или метод (404 на неизвестный путь, 405):
 * обычно это значит, что версия бота не совпадает с версией приложения.
 */
export const UNSUPPORTED_REQUEST_MESSAGE =
  'Сервер не поддерживает этот запрос. Возможно, версия бота устарела — обновите бота и приложение.';

/** Пути, где 404 означает «записи нет», а не «неизвестный адрес». */
const BOOKING_PATH = /^\/bookings\/\d+(\/cancel)?$/;

function fallbackMessage(status: number): string {
  return (
    STATUS_MESSAGES[status] ??
    (status >= 500 ? 'Ошибка на сервере. Повторите позже.' : `Ошибка запроса (${status}).`)
  );
}

function isUnsupportedRequest(request: ApiRequest, status: number, code: ApiErrorCode | null): boolean {
  if (status === 405 || code === 'method_not_allowed') {
    return true;
  }
  return status === 404 && !BOOKING_PATH.test(request.path);
}

function toHttpError(request: ApiRequest, status: number, body: unknown): ApiError {
  const parsed = apiErrorBodySchema.safeParse(body);
  const codeResult = parsed.success ? apiErrorCodeSchema.safeParse(parsed.data.error) : null;
  const code = codeResult?.success === true ? codeResult.data : null;
  if (isUnsupportedRequest(request, status, code)) {
    return new ApiError('http', UNSUPPORTED_REQUEST_MESSAGE, status, code);
  }
  if (!parsed.success) {
    return new ApiError('http', fallbackMessage(status), status);
  }
  const message = parsed.data.message.trim() === '' ? fallbackMessage(status) : parsed.data.message;
  return new ApiError('http', message, status, code, parsed.data.fields ?? {});
}

/** Результат отмены. `clientNotified: null` — клиент не из Telegram, уведомлять было некого. */
export interface CancelBookingResult {
  booking: Booking;
  clientNotified: boolean | null;
}

/** Заголовок `X-Client-Notified: true|false`; без заголовка или с другим значением — `null`. */
export function parseClientNotified(headers: ResponseHeaders): boolean | null {
  const value = headers['x-client-notified']?.trim().toLowerCase();
  if (value === 'true') {
    return true;
  }
  if (value === 'false') {
    return false;
  }
  return null;
}

function compactQuery(query: Record<string, string | number | null | undefined>): Record<string, string> {
  const result: Record<string, string> = {};
  for (const [key, value] of Object.entries(query)) {
    if (value !== undefined && value !== null && String(value) !== '') {
      result[key] = String(value);
    }
  }
  return result;
}

export function createApiClient(transport: Transport) {
  async function callWithHeaders<S extends z.ZodType>(
    request: ApiRequest,
    schema: S,
  ): Promise<{ data: z.output<S>; headers: ResponseHeaders }> {
    const result = await transport(request);
    if (result.kind === 'not-configured') {
      throw new ApiError('not-configured', 'Не заданы адрес и токен API. Откройте настройки.');
    }
    if (result.kind === 'network-error') {
      throw new ApiError('network', `${result.message} Проверьте, что бот запущен и адрес API указан верно.`);
    }
    if (result.status < 200 || result.status >= 300) {
      throw toHttpError(request, result.status, result.body);
    }
    const parsed = schema.safeParse(result.body);
    if (!parsed.success) {
      throw new ApiError('invalid-response', 'Сервер вернул данные в неожиданном формате.', result.status);
    }
    return { data: parsed.data, headers: result.headers };
  }

  async function call<S extends z.ZodType>(request: ApiRequest, schema: S): Promise<z.output<S>> {
    return (await callWithHeaders(request, schema)).data;
  }

  return {
    health: () => call({ method: 'GET', path: '/health' }, healthSchema),
    status: () => call({ method: 'GET', path: '/status' }, statusSchema),
    services: () => call({ method: 'GET', path: '/services' }, serviceSchema.array()),
    masters: () => call({ method: 'GET', path: '/masters' }, masterSchema.array()),
    bookings: (query: BookingsQuery = {}) =>
      call({ method: 'GET', path: '/bookings', query: compactQuery({ ...query }) }, bookingSchema.array()),
    booking: (id: number) => call({ method: 'GET', path: `/bookings/${id}` }, bookingSchema),
    createBooking: (body: CreateBookingRequest) =>
      call({ method: 'POST', path: '/bookings', body }, bookingSchema),
    cancelBooking: async (id: number): Promise<CancelBookingResult> => {
      const { data, headers } = await callWithHeaders(
        { method: 'POST', path: `/bookings/${id}/cancel` },
        bookingSchema,
      );
      return { booking: data, clientNotified: parseClientNotified(headers) };
    },
    slots: (query: SlotsQuery) =>
      call({ method: 'GET', path: '/slots', query: compactQuery({ ...query }) }, slotsSchema),
    dates: (query: DatesQuery) =>
      call({ method: 'GET', path: '/dates', query: compactQuery({ ...query }) }, datesSchema),
  };
}

export type ApiClient = ReturnType<typeof createApiClient>;
