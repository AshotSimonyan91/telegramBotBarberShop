import { z } from 'zod';
import { localDateSchema, localTimeSchema, type LocalDate } from './api/schemas';

/**
 * Правила проверки новой записи — те же, что на сервере:
 * имя 2..100 символов, телефон 5..20 символов (цифры, +, пробелы, -, скобки), дата не в прошлом.
 */

export const CLIENT_NAME_MIN = 2;
export const CLIENT_NAME_MAX = 100;
export const PHONE_MIN = 5;
export const PHONE_MAX = 20;
export const PHONE_PATTERN = /^[0-9+\s\-()]+$/;

/** Убирает пробелы по краям и схлопывает повторяющиеся пробелы. */
export function normalizeSpaces(value: string): string {
  return value.trim().replace(/\s+/g, ' ');
}

export const clientNameSchema = z
  .string()
  .transform(normalizeSpaces)
  .pipe(
    z
      .string()
      .min(1, 'Введите имя клиента.')
      .min(CLIENT_NAME_MIN, `Имя — не короче ${CLIENT_NAME_MIN} символов.`)
      .max(CLIENT_NAME_MAX, `Имя — не длиннее ${CLIENT_NAME_MAX} символов.`),
  );

export const phoneSchema = z
  .string()
  .transform(normalizeSpaces)
  .pipe(
    z
      .string()
      .min(1, 'Введите телефон.')
      .regex(PHONE_PATTERN, 'Телефон может содержать только цифры, «+», пробелы, «-» и скобки.')
      .min(PHONE_MIN, `Телефон — не короче ${PHONE_MIN} символов.`)
      .max(PHONE_MAX, `Телефон — не длиннее ${PHONE_MAX} символов.`),
  );

/** Значение поля «Мастер» в форме: пустая строка — «Любой мастер». */
export const ANY_MASTER = '';

export function createBookingFormSchema(today: LocalDate) {
  return z.object({
    serviceName: z.string().min(1, 'Выберите услугу.'),
    date: z
      .string()
      .min(1, 'Выберите дату.')
      .pipe(localDateSchema)
      .refine((date) => date >= today, 'Дата не может быть в прошлом.'),
    masterName: z.string(),
    time: z.string().min(1, 'Выберите время.').pipe(localTimeSchema),
    clientName: clientNameSchema,
    phone: phoneSchema,
  });
}

export type BookingFormSchema = ReturnType<typeof createBookingFormSchema>;
export type BookingFormInput = z.input<BookingFormSchema>;
export type BookingFormValues = z.output<BookingFormSchema>;
