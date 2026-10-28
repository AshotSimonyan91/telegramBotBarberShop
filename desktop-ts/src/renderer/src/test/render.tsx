import type { ReactElement } from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, type RenderResult } from '@testing-library/react';
import { createApiClient } from '@shared/api/client';
import { createFetchTransport } from '@shared/api/transport';
import type { SalonBridge, SettingsView } from '@shared/ipc';
import { ServicesProvider, type AppServices } from '@renderer/api/ApiContext';
import { ToastProvider } from '@renderer/components/Toasts';
import { TEST_API_URL, TEST_TOKEN } from './server';

export const TEST_SETTINGS: SettingsView = {
  apiUrl: TEST_API_URL,
  hasToken: true,
  apiUrlFromEnv: false,
  tokenFromEnv: false,
  tokenEncrypted: true,
  mockApi: false,
};

export function createTestServices(
  overrides: { token?: string | null; settings?: Partial<SalonBridge['settings']> } = {},
): AppServices {
  const token = overrides.token === undefined ? TEST_TOKEN : overrides.token;
  const transport = createFetchTransport({ getConnection: () => ({ baseUrl: TEST_API_URL, token }) });
  return {
    api: createApiClient(transport),
    settings: {
      get: () => Promise.resolve(TEST_SETTINGS),
      save: () => Promise.resolve({ ok: true, settings: TEST_SETTINGS }),
      ...overrides.settings,
    },
  };
}

export function renderWithProviders(
  ui: ReactElement,
  services: AppServices = createTestServices(),
): RenderResult & { queryClient: QueryClient } {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false, refetchOnWindowFocus: false }, mutations: { retry: false } },
  });
  const result = render(
    <ServicesProvider services={services}>
      <QueryClientProvider client={queryClient}>
        <ToastProvider>{ui}</ToastProvider>
      </QueryClientProvider>
    </ServicesProvider>,
  );
  return { ...result, queryClient };
}
