import { readFile, rename, writeFile } from 'node:fs/promises';
import { join } from 'node:path';
import { app, safeStorage } from 'electron';
import { z } from 'zod';
import { normalizeBaseUrl, type ApiConnection } from '@shared/api/transport';
import { DEFAULT_API_URL, type SettingsSaveResult, type SettingsUpdate, type SettingsView } from '@shared/ipc';

/**
 * Настройки подключения к API. Источники по приоритету: переменные окружения
 * `SALON_API_URL` / `SALON_API_TOKEN` → файл `settings.json` в userData → значения по умолчанию.
 * Токен в файле шифруется через safeStorage, если ОС это поддерживает.
 */

export const settingsUpdateSchema = z.object({
  apiUrl: z.string().max(500),
  token: z.string().max(1000).optional(),
}) satisfies z.ZodType<SettingsUpdate>;

const storedSchema = z.object({
  apiUrl: z.string().optional(),
  token: z.string().optional(),
  tokenEncrypted: z.boolean().optional(),
});

type Stored = z.infer<typeof storedSchema>;

interface Overrides {
  mockApi: boolean;
  mockToken: string | null;
}

function envValue(name: string): string | null {
  const value = process.env[name]?.trim();
  return value === undefined || value === '' ? null : value;
}

export class SettingsStore {
  private stored: Stored = {};
  private readonly file = join(app.getPath('userData'), 'settings.json');

  constructor(private readonly overrides: Overrides) {}

  async load(): Promise<void> {
    try {
      const parsed = storedSchema.safeParse(JSON.parse(await readFile(this.file, 'utf8')));
      this.stored = parsed.success ? parsed.data : {};
      if (!parsed.success) {
        console.warn('Файл настроек повреждён, используются значения по умолчанию');
      }
    } catch (error) {
      if ((error as NodeJS.ErrnoException).code !== 'ENOENT') {
        console.warn('Не удалось прочитать настройки:', (error as Error).message);
      }
      this.stored = {};
    }
  }

  private storedToken(): string | null {
    const { token, tokenEncrypted } = this.stored;
    if (token === undefined || token === '') {
      return null;
    }
    if (!tokenEncrypted) {
      return token;
    }
    try {
      return safeStorage.decryptString(Buffer.from(token, 'base64'));
    } catch {
      console.warn('Не удалось расшифровать сохранённый токен');
      return null;
    }
  }

  private apiUrl(): string {
    const fromEnv = envValue('SALON_API_URL');
    const candidate = fromEnv ?? this.stored.apiUrl ?? DEFAULT_API_URL;
    return normalizeBaseUrl(candidate) ?? DEFAULT_API_URL;
  }

  private token(): string | null {
    return envValue('SALON_API_TOKEN') ?? this.storedToken() ?? this.overrides.mockToken;
  }

  /** Подключение для запросов; `null`, если токен не задан. */
  connection(): ApiConnection | null {
    const token = this.token();
    return token === null ? null : { baseUrl: this.apiUrl(), token };
  }

  view(): SettingsView {
    return {
      apiUrl: this.apiUrl(),
      hasToken: this.token() !== null,
      apiUrlFromEnv: envValue('SALON_API_URL') !== null,
      tokenFromEnv: envValue('SALON_API_TOKEN') !== null,
      tokenEncrypted: this.stored.tokenEncrypted === true,
      mockApi: this.overrides.mockApi,
    };
  }

  async save(update: SettingsUpdate): Promise<SettingsSaveResult> {
    const apiUrl = normalizeBaseUrl(update.apiUrl);
    if (apiUrl === null) {
      return { ok: false, message: 'Укажите адрес вида http://127.0.0.1:8787 (только http или https).' };
    }
    const next: Stored = { ...this.stored, apiUrl };
    const token = update.token?.trim();
    if (token !== undefined && token !== '') {
      if (safeStorage.isEncryptionAvailable()) {
        next.token = safeStorage.encryptString(token).toString('base64');
        next.tokenEncrypted = true;
      } else {
        next.token = token;
        next.tokenEncrypted = false;
      }
    }
    try {
      const tmp = `${this.file}.tmp`;
      await writeFile(tmp, JSON.stringify(next, null, 2), { encoding: 'utf8', mode: 0o600 });
      await rename(tmp, this.file);
    } catch (error) {
      console.error('Не удалось сохранить настройки:', (error as Error).message);
      return { ok: false, message: 'Не удалось сохранить настройки на диск.' };
    }
    this.stored = next;
    return { ok: true, settings: this.view() };
  }
}
