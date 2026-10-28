import { setupServer } from 'msw/node';
import { createMockDb, type MockDb } from '@mocks/db';
import { createHandlers } from '@mocks/handlers';

export const TEST_API_URL = 'http://api.test';
export const TEST_TOKEN = 'test-token';
export const TEST_NOW_ISO = '2026-10-28T15:04';

/** Общий MSW-сервер тестов. Данные пересоздаются вызовом resetMockDb(). */
export const server = setupServer();

export function resetMockDb(): MockDb {
  const db = createMockDb(() => TEST_NOW_ISO);
  server.use(...createHandlers(db, { token: TEST_TOKEN }));
  return db;
}
