import { z } from 'zod';

/**
 * zod-схемы ответов API (`/api/v1`) — единственный источник типов данных приложения.
 * Типы ниже выводятся из схем, вручную не дублируются.
 */

const LOCAL_DATE_PATTERN = /^(\d{4})-(\d{2})-(\d{2})$/;
const LOCAL_TIME_PATTERN = /^(\d{2}):(\d{2})$/;

/** Строгая проверка `ГГГГ-ММ-ДД`: формат и существующая календарная дата (2027-02-30 — нет). */
export function isValidLocalDate(value: string): boolean {
  const match = LOCAL_DATE_PATTERN.exec(value);
  if (match === null) {
    return false;
  }
  const [y, m, d] = [Number(match[1]), Number(match[2]), Number(match[3])];
  if (m < 1 || m > 12 || d < 1) {
    return false;
  }
  // День 0 следующего месяца — последний день нужного; в UTC пояс компьютера не влияет.
  const daysInMonth = new Date(Date.UTC(y, m, 0)).getUTCDate();
  return d <= daysInMonth;
}

/** Строгая проверка `ЧЧ:ММ`: часы 00..23, минуты 00..59 (24:00 — нет). */
export function isValidLocalTime(value: string): boolean {
  const match = LOCAL_TIME_PATTERN.exec(value);
  return match !== null && Number(match[1]) <= 23 && Number(match[2]) <= 59;
}

/** `2026-10-30` */
export const localDateSchema = z
  .string()
  .regex(LOCAL_DATE_PATTERN, 'Ожидается дата ГГГГ-ММ-ДД')
  .refine(isValidLocalDate, 'Такой даты не существует.');
/** `14:30` */
export const localTimeSchema = z
  .string()
  .regex(LOCAL_TIME_PATTERN, 'Ожидается время ЧЧ:ММ')
  .refine(isValidLocalTime, 'Такого времени не существует.');
/** `2026-10-28T15:04` (секунды, если сервер их пришлёт, допускаются и при показе отбрасываются). */
export const localDateTimeSchema = z
  .string()
  .regex(/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}(:\d{2}(\.\d+)?)?$/, 'Ожидается дата и время ГГГГ-ММ-ДДTЧЧ:ММ');

export type LocalDateTime = z.infer<typeof localDateTimeSchema>;

export const weekdaySchema = z.enum([
  'MONDAY',
  'TUESDAY',
  'WEDNESDAY',
  'THURSDAY',
  'FRIDAY',
  'SATURDAY',
  'SUNDAY',
]);

export const bookingStatusSchema = z.enum(['CONFIRMED', 'CANCELLED']);

export const healthSchema = z.object({
  status: z.string(),
  timezone: z.string(),
  now: localDateTimeSchema,
});

export const serviceSchema = z.object({
  name: z.string(),
  durationMinutes: z.number().int().positive(),
  price: z.number().nonnegative(),
});

export const masterSchema = z.object({
  name: z.string(),
  workDays: z.array(weekdaySchema),
  workStart: localTimeSchema,
  workEnd: localTimeSchema,
  active: z.boolean(),
});

/**
 * Время создания записи. В старых строках Excel его может не быть: `null` или пустая строка
 * приводятся к `null` (в таблице показывается «—»).
 */
export const createdAtSchema = z
  .union([localDateTimeSchema, z.literal('')])
  .nullish()
  .transform((value): LocalDateTime | null => (value === '' || value == null ? null : value));

export const bookingSchema = z.object({
  id: z.number().int(),
  createdAt: createdAtSchema,
  clientName: z.string(),
  telegramId: z.number(),
  username: z.string(),
  phone: z.string(),
  serviceName: z.string(),
  masterName: z.string(),
  date: localDateSchema,
  time: localTimeSchema,
  durationMinutes: z.number().int(),
  source: z.string(),
  status: bookingStatusSchema,
  reminderSent: z.boolean(),
});

export const slotsSchema = z.object({
  date: localDateSchema,
  serviceName: z.string(),
  durationMinutes: z.number().int(),
  slots: z.array(localTimeSchema),
});

export const datesSchema = z.array(localDateSchema);

export const statusSchema = z.object({
  bot: z.object({
    running: z.boolean(),
    username: z.string().nullish(),
  }),
  storage: z.object({
    ok: z.boolean(),
    file: z.string().nullish(),
  }),
  time: z.string(),
});

/** Коды ошибок из контракта. */
export const apiErrorCodeSchema = z.enum([
  'unauthorized',
  'validation',
  'not_found',
  'method_not_allowed',
  'slot_taken',
  'already_cancelled',
  'storage_busy',
  'storage_error',
  'internal',
]);

/**
 * Поля, которые сервер может указать в `fields` ошибки validation (POST /bookings).
 * Порядок — как в форме новой записи. «Мастер не работает в этот день» приходит в `fields.date`.
 */
export const BOOKING_FIELDS = ['serviceName', 'date', 'masterName', 'time', 'clientName', 'phone'] as const;
export type BookingField = (typeof BOOKING_FIELDS)[number];

export const apiErrorBodySchema = z.object({
  error: z.string(),
  message: z.string(),
  fields: z.record(z.string(), z.string()).optional(),
});

/** Тело POST /bookings. `masterName: null` — «любой мастер». */
export const createBookingRequestSchema = z.object({
  clientName: z.string(),
  phone: z.string(),
  serviceName: z.string(),
  masterName: z.string().nullable(),
  date: localDateSchema,
  time: localTimeSchema,
});

export type LocalDate = z.infer<typeof localDateSchema>;
export type LocalTime = z.infer<typeof localTimeSchema>;
export type Weekday = z.infer<typeof weekdaySchema>;
export type BookingStatus = z.infer<typeof bookingStatusSchema>;
export type Health = z.infer<typeof healthSchema>;
export type Service = z.infer<typeof serviceSchema>;
export type Master = z.infer<typeof masterSchema>;
export type Booking = z.infer<typeof bookingSchema>;
export type Slots = z.infer<typeof slotsSchema>;
export type ApiStatus = z.infer<typeof statusSchema>;
export type ApiErrorCode = z.infer<typeof apiErrorCodeSchema>;
export type ApiErrorBody = z.infer<typeof apiErrorBodySchema>;
export type CreateBookingRequest = z.infer<typeof createBookingRequestSchema>;

/** Параметры GET /bookings. `date` несовместим с `from`/`to`. */
export interface BookingsQuery {
  date?: LocalDate;
  from?: LocalDate;
  to?: LocalDate;
  status?: BookingStatus;
  q?: string;
  masterName?: string;
}

export interface SlotsQuery {
  serviceName: string;
  date: LocalDate;
  masterName?: string | null;
}

export interface DatesQuery {
  serviceName: string;
  masterName?: string | null;
  days?: number;
}
