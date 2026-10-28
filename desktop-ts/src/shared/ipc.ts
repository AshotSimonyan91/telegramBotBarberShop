import type { ApiRequest, TransportResult } from './api/transport';

// Модуль без runtime-зависимостей: его импортирует preload, работающий в песочнице.

/** Каналы IPC между renderer (через preload) и main-процессом. */
export const IpcChannel = {
  apiRequest: 'salon:api-request',
  settingsGet: 'salon:settings-get',
  settingsSave: 'salon:settings-save',
} as const;

export const DEFAULT_API_URL = 'http://127.0.0.1:8787';

/** Настройки для показа в renderer. Сам токен в renderer не передаётся. */
export interface SettingsView {
  apiUrl: string;
  hasToken: boolean;
  /** Значение задано переменной окружения и в приложении не меняется. */
  apiUrlFromEnv: boolean;
  tokenFromEnv: boolean;
  /** Токен хранится зашифрованным средствами ОС (safeStorage). */
  tokenEncrypted: boolean;
  /** Приложение работает с моками MSW (`npm run dev:mock`). */
  mockApi: boolean;
}

export interface SettingsUpdate {
  apiUrl: string;
  /** `undefined` или пустая строка — оставить сохранённый токен. */
  token?: string;
}

export type SettingsSaveResult = { ok: true; settings: SettingsView } | { ok: false; message: string };

/** API, который preload публикует в `window.salon`. */
export interface SalonBridge {
  api: {
    request(request: ApiRequest): Promise<TransportResult>;
  };
  settings: {
    get(): Promise<SettingsView>;
    save(update: SettingsUpdate): Promise<SettingsSaveResult>;
  };
}
