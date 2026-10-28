import { z } from 'zod';

/**
 * zod-схемы ответов API (`/api/v1`) — единственный источник типов данных приложения.
 * Типы ниже выводятся из схем, вручную не дублируются.
 */

/** `2026-10-30` */
export const localDateSchema = z.string().regex(/^\d{4}-\d{2}-\d{2}$/, 'Ожидается дата ГГГГ-ММ-ДД');
/** `14:30` */
export const localTimeSchema = z.string().regex(/^\d{2}:\d{2}$/, 'Ожидается время ЧЧ:ММ');
/** `2026-10-28T15:04` (секунды, если сервер их пришлёт, допускаются и при показе отбрасываются). */
export const localDateTimeSchema = z
  .string()
  .regex(/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}(:\d{2}(\.\d+)?)?$/, 'Ожидается дата и время ГГГГ-ММ-ДДTЧЧ:ММ');

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

export const bookingSchema = z.object({
  id: z.number().int(),
  createdAt: localDateTimeSchema,
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
  'slot_taken',
  'already_cancelled',
  'storage_busy',
  'storage_error',
  'internal',
]);

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
export type LocalDateTime = z.infer<typeof localDateTimeSchema>;
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
