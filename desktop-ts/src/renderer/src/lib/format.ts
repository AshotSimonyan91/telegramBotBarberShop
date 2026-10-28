import type { Booking, BookingStatus, Service } from '@shared/api/schemas';

/** «5 000 ֏» — как в боте. */
export function formatPrice(price: number): string {
  const grouped = Math.round(price)
    .toString()
    .replace(/\B(?=(\d{3})+(?!\d))/g, ' ');
  return `${grouped} ֏`;
}

/** «30 мин», «1 ч», «1 ч 30 мин» */
export function formatDuration(minutes: number): string {
  const h = Math.floor(minutes / 60);
  const m = minutes % 60;
  if (h === 0) {
    return `${m} мин`;
  }
  return m === 0 ? `${h} ч` : `${h} ч ${m} мин`;
}

export function formatService(service: Service): string {
  return `${service.name} · ${formatDuration(service.durationMinutes)} · ${formatPrice(service.price)}`;
}

export const STATUS_TITLES: Readonly<Record<BookingStatus, string>> = {
  CONFIRMED: 'Подтверждена',
  CANCELLED: 'Отменена',
};

/** Клиент с Telegram-ником: «Арам (@aram)». */
export function formatClient(booking: Pick<Booking, 'clientName' | 'username'>): string {
  const username = booking.username.trim().replace(/^@/, '');
  return username === '' ? booking.clientName : `${booking.clientName} (@${username})`;
}

/**
 * Телефон для показа: убирает лишние пробелы; номер из одних цифр с «+» группирует для читаемости.
 * Введённое вручную форматирование («+374 77 55-44-33») оставляет как есть.
 */
export function formatPhone(phone: string): string {
  const trimmed = phone.trim().replace(/\s+/g, ' ');
  const compact = /^\+(\d{3})(\d{2})(\d{3})(\d{3})$/.exec(trimmed);
  if (compact === null) {
    return trimmed;
  }
  const [, country, operator, a, b] = compact;
  return `+${country} ${operator} ${a} ${b}`;
}

/** Склонение: plural(5, ['запись', 'записи', 'записей']). */
export function plural(n: number, forms: readonly [string, string, string]): string {
  const mod10 = n % 10;
  const mod100 = n % 100;
  if (mod10 === 1 && mod100 !== 11) {
    return forms[0];
  }
  if (mod10 >= 2 && mod10 <= 4 && (mod100 < 12 || mod100 > 14)) {
    return forms[1];
  }
  return forms[2];
}
