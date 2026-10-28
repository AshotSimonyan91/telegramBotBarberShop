import type { Booking, BookingStatus, Service } from '@shared/api/schemas';

/** «5 000 ֏» (с неразрывными пробелами) — как в боте. */
export function formatPrice(price: number): string {
  const grouped = Math.round(price)
    .toString()
    .replace(/\B(?=(\d{3})+(?!\d))/g, '\u00a0');
  return `${grouped}\u00a0֏`;
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
 * Телефон для показа. Сервер хранит номер нормализованным (`+37491123456`), здесь он группируется:
 * армянский — «+374 91 123 456», местный — «091 123 456». Остальные номера и старые записи
 * с ручным форматированием («+374 77 55-44-33») показываются как есть, без лишних пробелов.
 */
export function formatPhone(phone: string): string {
  const trimmed = phone.trim().replace(/\s+/g, ' ');
  const armenian = /^\+374(\d{2})(\d{3})(\d{3})$/.exec(trimmed);
  if (armenian !== null) {
    const [, operator, a, b] = armenian;
    return `+374 ${operator} ${a} ${b}`;
  }
  const local = /^0(\d{2})(\d{3})(\d{3})$/.exec(trimmed);
  if (local !== null) {
    const [, operator, a, b] = local;
    return `0${operator} ${a} ${b}`;
  }
  return trimmed;
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
