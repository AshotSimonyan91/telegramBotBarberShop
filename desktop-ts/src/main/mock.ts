import { setupServer } from 'msw/node';
import { currentDateTime } from '@shared/datetime';
import { createMockDb } from '@mocks/db';
import { createHandlers, MOCK_TOKEN } from '@mocks/handlers';

/** Режим `npm run dev:mock`: fetch из main-процесса перехватывается MSW, реальный API не нужен. */
export function startMockApi(): string {
  const db = createMockDb(() => currentDateTime(null));
  const server = setupServer(...createHandlers(db, { token: MOCK_TOKEN }));
  server.listen({ onUnhandledRequest: 'bypass' });
  console.info('Мок-API (MSW) включён');
  return MOCK_TOKEN;
}
