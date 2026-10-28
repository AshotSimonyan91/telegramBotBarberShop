import { describe, expect, it } from 'vitest';
import { formatClient, formatDuration, formatPhone, formatPrice, plural } from './format';

describe('format', () => {
  it('formatPrice — разряды и знак драма', () => {
    expect(formatPrice(5000)).toBe('5 000 ֏');
    expect(formatPrice(150)).toBe('150 ֏');
  });
  it('formatDuration', () => {
    expect(formatDuration(30)).toBe('30 мин');
    expect(formatDuration(60)).toBe('1 ч');
    expect(formatDuration(90)).toBe('1 ч 30 мин');
  });
  it('formatPhone группирует армянский номер и не трогает ручное форматирование', () => {
    expect(formatPhone('+37491123456')).toBe('+374 91 123 456');
    expect(formatPhone(' +374 77  55-44-33 ')).toBe('+374 77 55-44-33');
    expect(formatPhone('12345')).toBe('12345');
    expect(formatPhone('091123456')).toBe('091 123 456');
    expect(formatPhone('+79123456789')).toBe('+79123456789');
  });
  it('formatClient добавляет ник', () => {
    expect(formatClient({ clientName: 'Арам', username: 'aram' })).toBe('Арам (@aram)');
    expect(formatClient({ clientName: 'Арам', username: '' })).toBe('Арам');
  });
  it('plural', () => {
    const forms = ['запись', 'записи', 'записей'] as const;
    expect([1, 2, 5, 11, 21, 22, 25].map((n) => plural(n, forms))).toEqual([
      'запись',
      'записи',
      'записей',
      'записей',
      'запись',
      'записи',
      'записей',
    ]);
  });
});
