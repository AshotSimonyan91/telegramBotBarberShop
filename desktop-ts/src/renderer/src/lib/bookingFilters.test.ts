import { describe, expect, it } from 'vitest';
import type { Booking } from '@shared/api/schemas';
import {
  DEFAULT_FILTERS,
  mastersWorkingOn,
  rangeError,
  refineByTime,
  sortBookings,
  toBookingsQuery,
  toggleSort,
} from './bookingFilters';

const TODAY = '2026-10-28';

function booking(partial: Partial<Booking>): Booking {
  return {
    id: 1,
    createdAt: '2026-10-27T10:00',
    clientName: 'Клиент',
    telegramId: 0,
    username: '',
    phone: '+37491000000',
    serviceName: 'Стрижка',
    masterName: 'Анна',
    date: TODAY,
    time: '12:00',
    durationMinutes: 30,
    source: 'администратор',
    status: 'CONFIRMED',
    reminderSent: false,
    ...partial,
  };
}

describe('toBookingsQuery', () => {
  it('по умолчанию — предстоящие активные', () => {
    expect(toBookingsQuery(DEFAULT_FILTERS, TODAY)).toEqual({ from: TODAY, status: 'CONFIRMED' });
  });

  it('сегодня/завтра — параметр date', () => {
    expect(toBookingsQuery({ ...DEFAULT_FILTERS, period: 'today' }, TODAY).date).toBe(TODAY);
    expect(toBookingsQuery({ ...DEFAULT_FILTERS, period: 'tomorrow' }, TODAY).date).toBe('2026-10-29');
  });

  it('7 дней — from/to включительно', () => {
    expect(toBookingsQuery({ ...DEFAULT_FILTERS, period: 'week', status: 'all' }, TODAY)).toEqual({
      from: TODAY,
      to: '2026-11-03',
    });
  });

  it('период из одного дня превращается в date; date и from/to не смешиваются', () => {
    const query = toBookingsQuery(
      { ...DEFAULT_FILTERS, period: 'range', from: '2026-11-01', to: '2026-11-01' },
      TODAY,
    );
    expect(query.date).toBe('2026-11-01');
    expect(query.from).toBeUndefined();
    expect(query.to).toBeUndefined();
  });

  it('мастер, статус и поиск', () => {
    expect(
      toBookingsQuery(
        { ...DEFAULT_FILTERS, period: 'all', status: 'cancelled', masterName: 'Карен', q: '  +374 ' },
        TODAY,
      ),
    ).toEqual({ status: 'CANCELLED', masterName: 'Карен', q: '+374' });
  });
});

describe('rangeError', () => {
  it('«с» позже «по» — ошибка', () => {
    expect(rangeError({ period: 'range', from: '2026-11-02', to: '2026-11-01' })).not.toBeNull();
    expect(rangeError({ period: 'range', from: '2026-11-01', to: '' })).toBeNull();
  });
});

describe('refineByTime', () => {
  const list = [
    booking({ id: 1, time: '15:00' }),
    booking({ id: 2, time: '15:04' }),
    booking({ id: 3, time: '16:00' }),
  ];
  it('предстоящие — начиная с текущей минуты', () => {
    expect(refineByTime(list, 'upcoming', `${TODAY}T15:04`).map((b) => b.id)).toEqual([2, 3]);
  });
  it('прошедшие — строго раньше', () => {
    expect(refineByTime(list, 'past', `${TODAY}T15:04`).map((b) => b.id)).toEqual([1]);
  });
});

describe('sortBookings', () => {
  const list = [
    booking({ id: 3, clientName: 'Ваня', date: '2026-10-30' }),
    booking({ id: 1, clientName: 'арам', date: '2026-10-29' }),
    booking({ id: 2, clientName: 'Борис', date: '2026-10-29', time: '09:00' }),
  ];
  it('по началу записи', () => {
    expect(sortBookings(list, { key: 'start', direction: 'asc' }).map((b) => b.id)).toEqual([2, 1, 3]);
    expect(sortBookings(list, { key: 'start', direction: 'desc' }).map((b) => b.id)).toEqual([3, 1, 2]);
  });
  it('по имени без учёта регистра', () => {
    expect(sortBookings(list, { key: 'clientName', direction: 'asc' }).map((b) => b.id)).toEqual([1, 2, 3]);
  });
  it('не меняет исходный массив', () => {
    const copy = [...list];
    sortBookings(list, { key: 'id', direction: 'asc' });
    expect(list).toEqual(copy);
  });
  it('toggleSort', () => {
    expect(toggleSort({ key: 'id', direction: 'asc' }, 'id')).toEqual({ key: 'id', direction: 'desc' });
    expect(toggleSort({ key: 'id', direction: 'desc' }, 'phone')).toEqual({ key: 'phone', direction: 'asc' });
  });
});

describe('mastersWorkingOn', () => {
  it('только активные мастера, работающие в этот день недели', () => {
    const masters = [
      { name: 'Анна', workDays: ['WEDNESDAY' as const], workStart: '10:00', workEnd: '19:00', active: true },
      { name: 'Карен', workDays: ['THURSDAY' as const], workStart: '10:00', workEnd: '19:00', active: true },
      {
        name: 'Мариам',
        workDays: ['WEDNESDAY' as const],
        workStart: '10:00',
        workEnd: '19:00',
        active: false,
      },
    ];
    expect(mastersWorkingOn(masters, TODAY).map((m) => m.name)).toEqual(['Анна']);
  });
});
