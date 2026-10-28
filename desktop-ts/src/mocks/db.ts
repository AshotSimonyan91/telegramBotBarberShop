import type { Booking, LocalDate, LocalDateTime, LocalTime, Master, Service } from '@shared/api/schemas';
import { addDays, combine, datePart, fromMinutes, toMinutes, weekdayOf } from '@shared/datetime';

/**
 * Данные мок-API в памяти: используются в тестах и в режиме `npm run dev:mock`.
 * Свободное время считается упрощённо (шаг 30 минут, без перерывов) — только для разработки UI.
 * Поведение повторяет сервер: /dates отдаёт рабочие дни мастеров (свободного времени в такой день
 * может не быть), телефон хранится нормализованным (`+37491123456`), у старых строк нет `createdAt`.
 */

export const SLOT_STEP_MINUTES = 30;

export interface MockDb {
  now: () => LocalDateTime;
  timezone: string;
  services: Service[];
  masters: Master[];
  bookings: Booking[];
  nextId: number;
  bot: { running: boolean; username: string };
  storage: { ok: boolean; file: string };
  /** Удаётся ли боту уведомить клиента в Telegram об отмене (заголовок `X-Client-Notified`). */
  telegram: { notifyOk: boolean };
}

export function createMockDb(now: () => LocalDateTime): MockDb {
  const today = datePart(now());
  const createdAt = `${addDays(today, -1)}T12:00`;
  const base = { telegramId: 0, username: '', source: 'администратор', reminderSent: false, createdAt };
  return {
    now,
    timezone: 'Asia/Yerevan',
    services: [
      { name: 'Мужская стрижка', durationMinutes: 30, price: 4000 },
      { name: 'Женская стрижка', durationMinutes: 60, price: 7000 },
      { name: 'Окрашивание', durationMinutes: 120, price: 15000 },
    ],
    masters: [
      {
        name: 'Анна',
        workDays: ['MONDAY', 'TUESDAY', 'WEDNESDAY', 'THURSDAY', 'FRIDAY', 'SATURDAY'],
        workStart: '10:00',
        workEnd: '19:00',
        active: true,
      },
      {
        name: 'Карен',
        workDays: ['TUESDAY', 'WEDNESDAY', 'THURSDAY', 'FRIDAY', 'SATURDAY', 'SUNDAY'],
        workStart: '11:00',
        workEnd: '20:00',
        active: true,
      },
      {
        name: 'Мариам',
        workDays: ['MONDAY', 'FRIDAY'],
        workStart: '10:00',
        workEnd: '18:00',
        active: false,
      },
    ],
    bookings: [
      {
        ...base,
        id: 1,
        clientName: 'Арам Петросян',
        telegramId: 123456789,
        username: 'aram_p',
        phone: '+37491123456',
        serviceName: 'Мужская стрижка',
        masterName: 'Анна',
        date: addDays(today, -2),
        time: '12:00',
        durationMinutes: 30,
        source: 'salon_1',
        status: 'CONFIRMED',
        reminderSent: true,
      },
      {
        ...base,
        id: 2,
        clientName: 'Лилит Саргсян',
        phone: '+37477554433',
        serviceName: 'Окрашивание',
        masterName: 'Карен',
        date: addDays(today, 1),
        time: '14:00',
        durationMinutes: 120,
        status: 'CONFIRMED',
      },
      {
        ...base,
        id: 3,
        clientName: 'Давид Акопян',
        phone: '+37493000111',
        serviceName: 'Женская стрижка',
        masterName: 'Анна',
        date: addDays(today, 3),
        time: '10:30',
        durationMinutes: 60,
        status: 'CANCELLED',
        // старая строка Excel без времени создания
        createdAt: null,
      },
    ],
    nextId: 4,
    bot: { running: true, username: 'AshotSimonyanBot' },
    storage: { ok: true, file: 'salon_bookings.xlsx' },
    telegram: { notifyOk: true },
  };
}

/** Телефон так, как его хранит сервер: «+» (если был) и только цифры. */
export function normalizePhone(phone: string): string {
  const digits = phone.replace(/\D/g, '');
  return phone.trim().startsWith('+') ? `+${digits}` : digits;
}

/** Работает ли мастер в этот день недели. */
export function worksOn(master: Master, date: LocalDate): boolean {
  return master.workDays.includes(weekdayOf(date));
}

export function activeMasters(db: MockDb, masterName?: string | null): Master[] {
  return db.masters.filter((m) => m.active && (masterName == null || m.name === masterName));
}

function overlaps(startA: number, durationA: number, startB: number, durationB: number): boolean {
  return startA < startB + durationB && startB < startA + durationA;
}

/** Свободное время мастера на дату. */
export function masterSlots(db: MockDb, master: Master, date: LocalDate, duration: number): LocalTime[] {
  if (!worksOn(master, date)) {
    return [];
  }
  const now = db.now();
  const busy = db.bookings.filter(
    (b) => b.status === 'CONFIRMED' && b.masterName === master.name && b.date === date,
  );
  const slots: LocalTime[] = [];
  for (
    let start = toMinutes(master.workStart);
    start + duration <= toMinutes(master.workEnd);
    start += SLOT_STEP_MINUTES
  ) {
    const time = fromMinutes(start);
    if (combine(date, time) <= now) {
      continue;
    }
    if (busy.some((b) => overlaps(start, duration, toMinutes(b.time), b.durationMinutes))) {
      continue;
    }
    slots.push(time);
  }
  return slots;
}

/** Свободное время на дату: у конкретного мастера или у любого активного. */
export function freeSlots(
  db: MockDb,
  date: LocalDate,
  duration: number,
  masterName?: string | null,
): LocalTime[] {
  const all = new Set<LocalTime>();
  for (const master of activeMasters(db, masterName)) {
    masterSlots(db, master, date, duration).forEach((slot) => all.add(slot));
  }
  return [...all].sort();
}

/** Мастер, у которого свободно время (для «любого мастера» — первый подходящий). */
export function pickMaster(
  db: MockDb,
  date: LocalDate,
  time: LocalTime,
  duration: number,
  masterName: string | null,
): Master | null {
  return activeMasters(db, masterName).find((m) => masterSlots(db, m, date, duration).includes(time)) ?? null;
}

/**
 * Даты для записи, как на сервере: рабочие дни активных мастеров (или выбранного мастера)
 * начиная с сегодняшнего. Есть ли в этот день свободное время, здесь не проверяется.
 */
export function availableDates(
  db: MockDb,
  serviceName: string,
  masterName: string | null,
  days: number,
): LocalDate[] {
  if (!db.services.some((s) => s.name === serviceName)) {
    return [];
  }
  const masters = activeMasters(db, masterName);
  const today = datePart(db.now());
  const result: LocalDate[] = [];
  for (let i = 0; i < days; i++) {
    const date = addDays(today, i);
    if (masters.some((m) => worksOn(m, date))) {
      result.push(date);
    }
  }
  return result;
}
