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
  type BookingsQuery,
  type CreateBookingRequest,
  type DatesQuery,
  type SlotsQuery,
} from './schemas';
import type { ApiRequest, Transport } from './transport';

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

function fallbackMessage(status: number): string {
  return STATUS_MESSAGES[status] ?? (status >= 500 ? 'Ошибка на сервере. Повторите позже.' : `Ошибка запроса (${status}).`);
}

function toHttpError(status: number, body: unknown): ApiError {
  const parsed = apiErrorBodySchema.safeParse(body);
  if (!parsed.success) {
    return new ApiError('http', fallbackMessage(status), status);
  }
  const code = apiErrorCodeSchema.safeParse(parsed.data.error);
  const message = parsed.data.message.trim() === '' ? fallbackMessage(status) : parsed.data.message;
  return new ApiError('http', message, status, code.success ? code.data : null, parsed.data.fields ?? {});
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
  async function call<S extends z.ZodType>(request: ApiRequest, schema: S): Promise<z.infer<S>> {
    const result = await transport(request);
    if (result.kind === 'not-configured') {
      throw new ApiError('not-configured', 'Не заданы адрес и токен API. Откройте настройки.');
    }
    if (result.kind === 'network-error') {
      throw new ApiError('network', `${result.message} Проверьте, что бот запущен и адрес API указан верно.`);
    }
    if (result.status < 200 || result.status >= 300) {
      throw toHttpError(result.status, result.body);
    }
    const parsed = schema.safeParse(result.body);
    if (!parsed.success) {
      throw new ApiError('invalid-response', 'Сервер вернул данные в неожиданном формате.', result.status);
    }
    return parsed.data;
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
    cancelBooking: (id: number) => call({ method: 'POST', path: `/bookings/${id}/cancel` }, bookingSchema),
    slots: (query: SlotsQuery) => call({ method: 'GET', path: '/slots', query: compactQuery({ ...query }) }, slotsSchema),
    dates: (query: DatesQuery) => call({ method: 'GET', path: '/dates', query: compactQuery({ ...query }) }, datesSchema),
  };
}

export type ApiClient = ReturnType<typeof createApiClient>;
