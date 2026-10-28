import { beforeEach, describe, expect, it } from 'vitest';
import { PHONE_FORMAT_MESSAGE } from '@shared/validation';
import { resetMockDb, TEST_API_URL, TEST_TOKEN } from '@renderer/test/server';

/** Мок POST /bookings отвечает как сервер: 400 validation с ошибкой в нужном поле. */

const valid = {
  clientName: 'Гаяне',
  phone: '+374 91 000000',
  serviceName: 'Мужская стрижка',
  masterName: null,
  date: '2026-10-29',
  time: '12:00',
};

async function post(body: Record<string, unknown>) {
  const response = await fetch(`${TEST_API_URL}/api/v1/bookings`, {
    method: 'POST',
    headers: { Authorization: `Bearer ${TEST_TOKEN}`, 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  });
  return { status: response.status, body: (await response.json()) as Record<string, unknown> };
}

beforeEach(() => {
  resetMockDb();
});

describe('мок POST /bookings', () => {
  it('корректная запись создаётся', async () => {
    expect((await post(valid)).status).toBe(201);
  });

  it.each([
    ['date', '2027-02-30'],
    ['time', '24:00'],
    ['phone', '374+91000000'],
  ])('%s = %j — 400 с fields.%s', async (field, value) => {
    const result = await post({ ...valid, [field]: value });
    expect(result.status).toBe(400);
    expect(result.body.error).toBe('validation');
    expect(result.body.fields).toHaveProperty(field);
  });

  it('сообщение о телефоне — как на сервере', async () => {
    const result = await post({ ...valid, phone: '+374+91000000' });
    expect(result.body.fields).toEqual({ phone: PHONE_FORMAT_MESSAGE });
  });
});
