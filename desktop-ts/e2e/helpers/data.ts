import { execFileSync } from 'node:child_process';
import type { Backend } from './backend';

export interface BookingDto {
  id: number;
  createdAt: string | null;
  clientName: string;
  telegramId: number;
  username: string;
  phone: string;
  serviceName: string;
  masterName: string;
  date: string;
  time: string;
  durationMinutes: number;
  source: string;
  status: 'CONFIRMED' | 'CANCELLED';
}

export interface NewBookingBody {
  clientName: string;
  phone: string;
  serviceName: string;
  masterName: string | null;
  date: string;
  time: string;
}

export const SERVICE = 'Борода'; // 30 минут: самая короткая услуга
export const LONG_SERVICE = 'Окрашивание'; // 120 минут
export const MASTERS = ['Анна', 'Арам', 'Мариам'];

const WEEKDAYS = ['SUNDAY', 'MONDAY', 'TUESDAY', 'WEDNESDAY', 'THURSDAY', 'FRIDAY', 'SATURDAY'];

export function addDays(date: string, days: number): string {
  const [y, m, d] = date.split('-').map(Number) as [number, number, number];
  return new Date(Date.UTC(y, m - 1, d + days)).toISOString().slice(0, 10);
}

export function weekdayOf(date: string): string {
  const [y, m, d] = date.split('-').map(Number) as [number, number, number];
  return WEEKDAYS[new Date(Date.UTC(y, m - 1, d)).getUTCDay()] ?? '';
}

/** «Сегодня» и «сейчас» по часам бэкенда (его часовой пояс, а не компьютера). */
export async function serverToday(backend: Backend): Promise<{ today: string; now: string }> {
  const { body } = await backend.api<{ now: string }>('GET', '/health');
  return { today: body.now.slice(0, 10), now: body.now };
}

/** Ближайшая дата строго после сегодняшней с нужным днём недели (+ weeksAhead недель). */
export async function futureDate(backend: Backend, weekday: string, weeksAhead = 0): Promise<string> {
  const { today } = await serverToday(backend);
  let date = addDays(today, 1);
  while (weekdayOf(date) !== weekday) {
    date = addDays(date, 1);
  }
  return addDays(date, 7 * weeksAhead);
}

/** В пятницу работают все трое мастеров: Анна 10–19, Арам 11–20, Мариам 9–18. */
export function nextFriday(backend: Backend, weeksAhead = 0): Promise<string> {
  return futureDate(backend, 'FRIDAY', weeksAhead);
}

export async function freeSlots(
  backend: Backend,
  service: string,
  date: string,
  master: string | null,
): Promise<string[]> {
  const { body } = await backend.api<{ slots: string[] }>('GET', '/slots', {
    query: { serviceName: service, date, ...(master !== null ? { masterName: master } : {}) },
  });
  return body.slots;
}

export async function createViaApi(backend: Backend, body: NewBookingBody) {
  return backend.api<BookingDto>('POST', '/bookings', { body });
}

export async function allBookings(backend: Backend): Promise<BookingDto[]> {
  const { body } = await backend.api<BookingDto[]>('GET', '/bookings');
  return body;
}

export function booking(overrides: Partial<NewBookingBody> & Pick<NewBookingBody, 'date' | 'time'>): NewBookingBody {
  return {
    clientName: 'Тест Клиент',
    phone: '+374 91 12-34-56',
    serviceName: SERVICE,
    masterName: null,
    ...overrides,
  };
}

/** Содержимое xlsx как текст (все xml-части) — чтобы убедиться, что значение реально лежит в файле. */
export function xlsxText(file: string): string {
  return execFileSync('unzip', ['-p', file], { encoding: 'utf8', maxBuffer: 50 * 1024 * 1024 });
}
