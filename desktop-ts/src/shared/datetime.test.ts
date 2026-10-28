import { describe, expect, it } from 'vitest';
import {
  addDays,
  currentDateTime,
  formatDateLong,
  formatDateTimeShort,
  formatDateWithWeekday,
  relativeDayLabel,
  weekdayOf,
} from './datetime';

describe('datetime', () => {
  it('addDays переходит через границы месяца и года', () => {
    expect(addDays('2026-10-31', 1)).toBe('2026-11-01');
    expect(addDays('2026-12-31', 1)).toBe('2027-01-01');
    expect(addDays('2026-03-01', -1)).toBe('2026-02-28');
  });

  it('weekdayOf', () => {
    expect(weekdayOf('2026-10-28')).toBe('WEDNESDAY');
    expect(weekdayOf('2026-11-01')).toBe('SUNDAY');
  });

  it('форматирование по-русски', () => {
    expect(formatDateWithWeekday('2026-10-30')).toBe('30.10.2026 (пт)');
    expect(formatDateLong('2026-10-30')).toBe('пт, 30 октября');
    expect(formatDateTimeShort('2026-10-28T15:04')).toBe('28.10.2026 15:04');
    expect(formatDateTimeShort('2026-10-28T15:04:59')).toBe('28.10.2026 15:04');
  });

  it('currentDateTime сдвигает время сервера на прошедшее время', () => {
    const clock = { serverNow: '2026-10-28T23:59', receivedAt: 1_000 };
    expect(currentDateTime(clock, 1_000)).toBe('2026-10-28T23:59');
    expect(currentDateTime(clock, 1_000 + 2 * 60_000)).toBe('2026-10-29T00:01');
  });

  it('relativeDayLabel', () => {
    expect(relativeDayLabel('2026-10-28', '2026-10-28')).toBe('Сегодня');
    expect(relativeDayLabel('2026-10-29', '2026-10-28')).toBe('Завтра');
    expect(relativeDayLabel('2026-10-30', '2026-10-28')).toBe('');
  });
});
