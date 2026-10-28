import type { Booking, BookingStatus, BookingsQuery, LocalDate, LocalDateTime, Master } from '@shared/api/schemas';
import { addDays, combine, weekdayOf } from '@shared/datetime';

/**
 * Фильтры вкладки «Записи». Отбор по дате, статусу, мастеру и поиск выполняет сервер (GET /bookings),
 * здесь — построение параметров запроса, уточнение «предстоящие/прошедшие» по времени и сортировка.
 */

export const PERIODS = ['today', 'tomorrow', 'week', 'upcoming', 'past', 'all', 'range'] as const;
export type Period = (typeof PERIODS)[number];

export const PERIOD_TITLES: Readonly<Record<Period, string>> = {
  today: 'Сегодня',
  tomorrow: 'Завтра',
  week: 'Ближайшие 7 дней',
  upcoming: 'Все предстоящие',
  past: 'Прошедшие',
  all: 'Все',
  range: 'Период…',
};

export const STATUS_FILTERS = ['active', 'cancelled', 'all'] as const;
export type StatusFilter = (typeof STATUS_FILTERS)[number];

export const STATUS_FILTER_TITLES: Readonly<Record<StatusFilter, string>> = {
  active: 'Активные',
  cancelled: 'Отменённые',
  all: 'Все статусы',
};

const STATUS_PARAM: Readonly<Record<StatusFilter, BookingStatus | undefined>> = {
  active: 'CONFIRMED',
  cancelled: 'CANCELLED',
  all: undefined,
};

/** Пустая строка в `masterName` — все мастера. */
export interface BookingFilters {
  period: Period;
  from: LocalDate | '';
  to: LocalDate | '';
  status: StatusFilter;
  masterName: string;
  q: string;
}

export const DEFAULT_FILTERS: BookingFilters = {
  period: 'upcoming',
  from: '',
  to: '',
  status: 'active',
  masterName: '',
  q: '',
};

/** Ошибка во введённом периоде или `null`. */
export function rangeError(filters: Pick<BookingFilters, 'period' | 'from' | 'to'>): string | null {
  if (filters.period !== 'range') {
    return null;
  }
  if (filters.from !== '' && filters.to !== '' && filters.from > filters.to) {
    return 'Дата «с» позже даты «по».';
  }
  return null;
}

/** Параметры GET /bookings для фильтров. `today` — сегодняшняя дата по часам сервера. */
export function toBookingsQuery(filters: BookingFilters, today: LocalDate): BookingsQuery {
  const query: BookingsQuery = {};
  switch (filters.period) {
    case 'today':
      query.date = today;
      break;
    case 'tomorrow':
      query.date = addDays(today, 1);
      break;
    case 'week':
      query.from = today;
      query.to = addDays(today, 6);
      break;
    case 'upcoming':
      query.from = today;
      break;
    case 'past':
      query.to = today;
      break;
    case 'range':
      if (filters.from !== '' && filters.from === filters.to) {
        query.date = filters.from;
      } else {
        if (filters.from !== '') {
          query.from = filters.from;
        }
        if (filters.to !== '') {
          query.to = filters.to;
        }
      }
      break;
    case 'all':
      break;
  }
  const status = STATUS_PARAM[filters.status];
  if (status !== undefined) {
    query.status = status;
  }
  if (filters.masterName !== '') {
    query.masterName = filters.masterName;
  }
  const q = filters.q.trim();
  if (q !== '') {
    query.q = q;
  }
  return query;
}

export function bookingStart(booking: Pick<Booking, 'date' | 'time'>): LocalDateTime {
  return combine(booking.date, booking.time);
}

/**
 * Уточнение на клиенте: сервер фильтрует по датам, а «предстоящие» и «прошедшие» в Swing-приложении
 * считаются по времени начала относительно текущего момента.
 */
export function refineByTime(bookings: readonly Booking[], period: Period, now: LocalDateTime): Booking[] {
  if (period === 'upcoming') {
    return bookings.filter((b) => bookingStart(b) >= now);
  }
  if (period === 'past') {
    return bookings.filter((b) => bookingStart(b) < now);
  }
  return [...bookings];
}

export const SORT_KEYS = [
  'id',
  'start',
  'clientName',
  'phone',
  'serviceName',
  'masterName',
  'status',
  'source',
  'createdAt',
] as const;
export type SortKey = (typeof SORT_KEYS)[number];
export type SortDirection = 'asc' | 'desc';

export interface SortState {
  key: SortKey;
  direction: SortDirection;
}

export function defaultSort(period: Period): SortState {
  return { key: 'start', direction: period === 'past' ? 'desc' : 'asc' };
}

/** Следующее состояние при клике по заголовку столбца. */
export function toggleSort(current: SortState, key: SortKey): SortState {
  if (current.key !== key) {
    return { key, direction: 'asc' };
  }
  return { key, direction: current.direction === 'asc' ? 'desc' : 'asc' };
}

const collator = new Intl.Collator('ru', { sensitivity: 'base', numeric: true });

function sortValue(booking: Booking, key: SortKey): string | number {
  switch (key) {
    case 'id':
      return booking.id;
    case 'start':
      return bookingStart(booking);
    case 'createdAt':
      return booking.createdAt;
    default:
      return booking[key];
  }
}

/** Стабильная сортировка; при равенстве — по времени начала и номеру. */
export function sortBookings(bookings: readonly Booking[], sort: SortState): Booking[] {
  const sign = sort.direction === 'asc' ? 1 : -1;
  return [...bookings].sort((a, b) => {
    const va = sortValue(a, sort.key);
    const vb = sortValue(b, sort.key);
    const primary =
      typeof va === 'number' && typeof vb === 'number' ? va - vb : collator.compare(String(va), String(vb));
    if (primary !== 0) {
      return primary * sign;
    }
    return bookingStart(a).localeCompare(bookingStart(b)) || a.id - b.id;
  });
}

/** Мастера, работающие в этот день недели (для шага «Мастер» новой записи). */
export function mastersWorkingOn(masters: readonly Master[], date: LocalDate): Master[] {
  const weekday = weekdayOf(date);
  return masters.filter((m) => m.active && m.workDays.includes(weekday));
}

/** Имена мастеров для фильтра: все из справочника плюс выбранный, если его уже нет в списке. */
export function masterFilterOptions(masters: readonly Master[], selected: string): string[] {
  const names = masters.map((m) => m.name);
  if (selected !== '' && !names.includes(selected)) {
    names.push(selected);
  }
  return names;
}

export function summarize(bookings: readonly Booking[]): { total: number; active: number } {
  return { total: bookings.length, active: bookings.filter((b) => b.status === 'CONFIRMED').length };
}
