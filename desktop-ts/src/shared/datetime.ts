import type { LocalDate, LocalDateTime, LocalTime, Weekday } from './api/schemas';

/**
 * Работа с датами в формате API без часовых поясов: строки `ГГГГ-ММ-ДД`, `ЧЧ:ММ`, `ГГГГ-ММ-ДДTЧЧ:ММ`.
 * Внутри используется UTC, чтобы пояс компьютера не сдвигал даты: «сегодня» берётся из часов сервера.
 */

const WEEKDAYS: readonly Weekday[] = [
  'SUNDAY',
  'MONDAY',
  'TUESDAY',
  'WEDNESDAY',
  'THURSDAY',
  'FRIDAY',
  'SATURDAY',
];
const SHORT_DAYS: Readonly<Record<Weekday, string>> = {
  MONDAY: 'пн',
  TUESDAY: 'вт',
  WEDNESDAY: 'ср',
  THURSDAY: 'чт',
  FRIDAY: 'пт',
  SATURDAY: 'сб',
  SUNDAY: 'вс',
};
const MONTHS_GENITIVE = [
  'января',
  'февраля',
  'марта',
  'апреля',
  'мая',
  'июня',
  'июля',
  'августа',
  'сентября',
  'октября',
  'ноября',
  'декабря',
] as const;

const pad = (n: number): string => String(n).padStart(2, '0');

function parseDate(date: LocalDate): Date {
  const [y = 0, m = 1, d = 1] = date.split('-').map(Number);
  return new Date(Date.UTC(y, m - 1, d));
}

function toLocalDate(value: Date): LocalDate {
  return `${value.getUTCFullYear()}-${pad(value.getUTCMonth() + 1)}-${pad(value.getUTCDate())}`;
}

export function addDays(date: LocalDate, days: number): LocalDate {
  const value = parseDate(date);
  value.setUTCDate(value.getUTCDate() + days);
  return toLocalDate(value);
}

export function weekdayOf(date: LocalDate): Weekday {
  return WEEKDAYS[parseDate(date).getUTCDay()] ?? 'MONDAY';
}

/** Дата из `ГГГГ-ММ-ДДTЧЧ:ММ`. */
export function datePart(dateTime: LocalDateTime): LocalDate {
  return dateTime.slice(0, 10);
}

/** Склеивает дату и время в строку, которую можно сравнивать лексикографически. */
export function combine(date: LocalDate, time: LocalTime): LocalDateTime {
  return `${date}T${time}`;
}

/** Минуты от полуночи для `ЧЧ:ММ`. */
export function toMinutes(time: LocalTime): number {
  const [h = 0, m = 0] = time.split(':').map(Number);
  return h * 60 + m;
}

export function fromMinutes(minutes: number): LocalTime {
  return `${pad(Math.floor(minutes / 60))}:${pad(minutes % 60)}`;
}

/** Снимок серверных часов: время сервера и момент (Date.now()), когда он получен. */
export interface ServerClock {
  serverNow: LocalDateTime;
  receivedAt: number;
}

function parseDateTime(value: LocalDateTime): number {
  const [date = '', time = '00:00'] = value.split('T');
  return parseDate(date).getTime() + toMinutes(time.slice(0, 5)) * 60_000;
}

function formatDateTime(epochMs: number): LocalDateTime {
  const value = new Date(epochMs);
  return `${toLocalDate(value)}T${pad(value.getUTCHours())}:${pad(value.getUTCMinutes())}`;
}

/** Текущее время в поясе сервера. Без снимка — по часам компьютера. */
export function currentDateTime(clock: ServerClock | null, nowMs: number = Date.now()): LocalDateTime {
  if (clock === null) {
    const local = new Date(nowMs);
    return `${local.getFullYear()}-${pad(local.getMonth() + 1)}-${pad(local.getDate())}T${pad(local.getHours())}:${pad(local.getMinutes())}`;
  }
  return formatDateTime(parseDateTime(clock.serverNow) + (nowMs - clock.receivedAt));
}

/** «30.10.2026» */
export function formatDate(date: LocalDate): string {
  const [y, m, d] = date.split('-');
  return `${d}.${m}.${y}`;
}

/** «30.10.2026 (пт)» — как в Swing-приложении. */
export function formatDateWithWeekday(date: LocalDate): string {
  return `${formatDate(date)} (${SHORT_DAYS[weekdayOf(date)]})`;
}

/** «пт, 30 октября» */
export function formatDateLong(date: LocalDate): string {
  const value = parseDate(date);
  return `${SHORT_DAYS[weekdayOf(date)]}, ${value.getUTCDate()} ${MONTHS_GENITIVE[value.getUTCMonth()] ?? ''}`;
}

/** «28.10.2026 15:04» */
export function formatDateTimeShort(dateTime: LocalDateTime): string {
  return `${formatDate(datePart(dateTime))} ${dateTime.slice(11, 16)}`;
}

/** «Сегодня», «Завтра» или пусто. */
export function relativeDayLabel(date: LocalDate, today: LocalDate): string {
  if (date === today) {
    return 'Сегодня';
  }
  if (date === addDays(today, 1)) {
    return 'Завтра';
  }
  return '';
}
