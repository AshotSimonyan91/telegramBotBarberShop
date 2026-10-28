import { createContext, useContext, type ReactNode } from 'react';
import { createApiClient, type ApiClient } from '@shared/api/client';
import type { Transport } from '@shared/api/transport';
import type { SalonBridge } from '@shared/ipc';

/** Зависимости UI от внешнего мира: API-клиент и настройки. В тестах подменяются. */
export interface AppServices {
  api: ApiClient;
  settings: SalonBridge['settings'];
}

const ServicesContext = createContext<AppServices | null>(null);

export function ServicesProvider({ services, children }: { services: AppServices; children: ReactNode }) {
  return <ServicesContext.Provider value={services}>{children}</ServicesContext.Provider>;
}

export function useServices(): AppServices {
  const services = useContext(ServicesContext);
  if (services === null) {
    throw new Error('useServices вызван вне ServicesProvider');
  }
  return services;
}

export function useApi(): ApiClient {
  return useServices().api;
}

/** Рабочие зависимости: запросы уходят в main-процесс через preload. */
export function createBridgeServices(bridge: SalonBridge): AppServices {
  const transport: Transport = (request) => bridge.api.request(request);
  return { api: createApiClient(transport), settings: bridge.settings };
}
