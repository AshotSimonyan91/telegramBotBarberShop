import { describe, expect, it } from 'vitest';
import { isValidLocalDate, isValidLocalTime } from './api/schemas';
import { createBookingFormSchema, PHONE_FORMAT_MESSAGE, phoneSchema } from './validation';

const schema = createBookingFormSchema('2026-10-28');
const valid = {
  serviceName: 'Мужская стрижка',
  date: '2026-10-29',
  masterName: '',
  time: '10:00',
  clientName: '  Арам   Петросян ',
  phone: ' +374 (91) 12-34-56 ',
};

describe('валидация новой записи', () => {
  it('нормализует пробелы в имени и телефоне', () => {
    const result = schema.parse(valid);
    expect(result.clientName).toBe('Арам Петросян');
    expect(result.phone).toBe('+374 (91) 12-34-56');
  });

  it.each([
    ['clientName', 'А'],
    ['clientName', 'Я'.repeat(101)],
    ['phone', '1234'],
    ['phone', '1'.repeat(21)],
    ['phone', '+374 91 abc'],
    ['phone', '+(12) 34'],
    ['phone', '1-2-3-4'],
    ['phone', '374+91123456'],
    ['phone', '++37491123456'],
    ['phone', '+374 91 12+34'],
    ['date', '2026-10-27'],
    ['date', '2027-02-30'],
    ['date', '2026-13-01'],
    ['date', '2026-11-31'],
    ['date', '29.10.2026'],
    ['time', ''],
    ['time', '24:00'],
    ['time', '12:60'],
    ['time', '9:00'],
    ['serviceName', ''],
  ])('%s = %j — ошибка', (field, value) => {
    const result = schema.safeParse({ ...valid, [field]: value });
    expect(result.success).toBe(false);
    expect(result.error?.issues[0]?.path).toEqual([field]);
  });

  it('границы длины телефона включительно', () => {
    expect(phoneSchema.safeParse('12345').success).toBe(true);
    expect(phoneSchema.safeParse('1'.repeat(20)).success).toBe(true);
  });

  it('в телефоне не меньше 5 цифр', () => {
    expect(phoneSchema.safeParse('+(1234)').error?.issues[0]?.message).toMatch(/не меньше 5 цифр/);
    expect(phoneSchema.safeParse('+(12345)').success).toBe(true);
  });

  it('«+» допускается только первым символом', () => {
    expect(phoneSchema.safeParse('+37491123456').success).toBe(true);
    expect(phoneSchema.safeParse('37491123456').success).toBe(true);
    expect(phoneSchema.safeParse(' +374 91 123456').success).toBe(true);
    const plusInside = phoneSchema.safeParse('374+91123456');
    expect(plusInside.error?.issues[0]?.message).toBe(PHONE_FORMAT_MESSAGE);
    expect(PHONE_FORMAT_MESSAGE).toMatch(/«\+» только в начале/);
  });

  it('строгая проверка календарной даты', () => {
    expect(isValidLocalDate('2027-02-28')).toBe(true);
    expect(isValidLocalDate('2028-02-29')).toBe(true);
    expect(isValidLocalDate('2027-02-29')).toBe(false);
    expect(isValidLocalDate('2027-02-30')).toBe(false);
    expect(isValidLocalDate('2026-04-31')).toBe(false);
    expect(isValidLocalDate('2026-12-31')).toBe(true);
    expect(isValidLocalDate('2026-00-10')).toBe(false);
    expect(isValidLocalDate('2026-10-00')).toBe(false);
    expect(isValidLocalDate('2026-1-10')).toBe(false);
  });

  it('строгая проверка времени', () => {
    expect(isValidLocalTime('00:00')).toBe(true);
    expect(isValidLocalTime('23:59')).toBe(true);
    expect(isValidLocalTime('24:00')).toBe(false);
    expect(isValidLocalTime('23:60')).toBe(false);
    expect(isValidLocalTime('12:00:00')).toBe(false);
  });

  it('сегодняшняя дата допустима', () => {
    expect(schema.safeParse({ ...valid, date: '2026-10-28' }).success).toBe(true);
  });
});
