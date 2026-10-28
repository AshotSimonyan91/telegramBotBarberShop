import { join, resolve } from 'node:path';
import { pathToFileURL } from 'node:url';
import { app, BrowserWindow, ipcMain, session, shell, type IpcMainInvokeEvent } from 'electron';
import {
  apiRequestSchema,
  createFetchTransport,
  isAllowedRequest,
  type TransportResult,
} from '@shared/api/transport';
import { IpcChannel, type SettingsSaveResult } from '@shared/ipc';
import { SettingsStore, settingsUpdateSchema } from './settings';

const devServerUrl = process.env.ELECTRON_RENDERER_URL;
const rendererFile = join(__dirname, '../renderer/index.html');

/** В режиме разработки переменные из `.env` рядом с package.json (реальные переменные окружения важнее). */
function loadDotenv(): void {
  if (app.isPackaged) {
    return;
  }
  try {
    process.loadEnvFile(resolve(app.getAppPath(), '.env'));
  } catch {
    // файла .env может не быть — это нормально
  }
}

/** Запросы принимаются только от собственной страницы приложения. */
function isTrustedSender(event: IpcMainInvokeEvent): boolean {
  const url = event.senderFrame?.url ?? '';
  if (devServerUrl !== undefined && !app.isPackaged) {
    return url.startsWith(devServerUrl);
  }
  return url === pathToFileURL(rendererFile).toString();
}

function hardenSession(): void {
  session.defaultSession.setPermissionRequestHandler((_wc, _permission, callback) => callback(false));
  session.defaultSession.setPermissionCheckHandler(() => false);
}

function createWindow(): BrowserWindow {
  const window = new BrowserWindow({
    width: 1280,
    height: 820,
    minWidth: 760,
    minHeight: 520,
    title: 'Салон — записи',
    show: false,
    autoHideMenuBar: true,
    webPreferences: {
      preload: join(__dirname, '../preload/index.js'),
      contextIsolation: true,
      nodeIntegration: false,
      sandbox: true,
      webSecurity: true,
      allowRunningInsecureContent: false,
      spellcheck: false,
    },
  });

  window.once('ready-to-show', () => window.show());

  // Внешние ссылки — в браузере, новые окна и переходы внутри приложения запрещены.
  window.webContents.setWindowOpenHandler(({ url }) => {
    if (url.startsWith('https://')) {
      void shell.openExternal(url);
    }
    return { action: 'deny' };
  });
  window.webContents.on('will-navigate', (event) => event.preventDefault());
  window.webContents.on('will-attach-webview', (event) => event.preventDefault());

  if (!app.isPackaged) {
    // В разработке предупреждения и ошибки страницы дублируются в терминал.
    window.webContents.on('console-message', (details) => {
      if (details.level === 'warning' || details.level === 'error') {
        console.log(`[renderer:${details.level}] ${details.message}`);
      }
    });
  }

  if (devServerUrl !== undefined && !app.isPackaged) {
    void window.loadURL(devServerUrl);
  } else {
    void window.loadFile(rendererFile);
  }
  return window;
}

async function main(): Promise<void> {
  loadDotenv();
  const mockApi = import.meta.env.VITE_MOCK_API === '1';
  let mockToken: string | null = null;
  if (mockApi) {
    const { startMockApi } = await import('./mock');
    mockToken = startMockApi();
  }

  await app.whenReady();
  const settings = new SettingsStore({ mockApi, mockToken });
  await settings.load();
  const transport = createFetchTransport({ getConnection: () => settings.connection() });

  ipcMain.handle(IpcChannel.apiRequest, async (event, raw: unknown): Promise<TransportResult> => {
    if (!isTrustedSender(event)) {
      throw new Error('Запрос от недоверенной страницы отклонён');
    }
    const request = apiRequestSchema.safeParse(raw);
    if (!request.success || !isAllowedRequest(request.data)) {
      return {
        kind: 'response',
        status: 400,
        body: { error: 'validation', message: 'Недопустимый запрос.' },
        headers: {},
      };
    }
    return transport(request.data);
  });

  ipcMain.handle(IpcChannel.settingsGet, (event) => {
    if (!isTrustedSender(event)) {
      throw new Error('Запрос от недоверенной страницы отклонён');
    }
    return settings.view();
  });

  ipcMain.handle(IpcChannel.settingsSave, async (event, raw: unknown): Promise<SettingsSaveResult> => {
    if (!isTrustedSender(event)) {
      throw new Error('Запрос от недоверенной страницы отклонён');
    }
    const update = settingsUpdateSchema.safeParse(raw);
    if (!update.success) {
      return { ok: false, message: 'Некорректные настройки.' };
    }
    return settings.save(update.data);
  });

  hardenSession();
  createWindow();

  app.on('activate', () => {
    if (BrowserWindow.getAllWindows().length === 0) {
      createWindow();
    }
  });
}

app.on('window-all-closed', () => {
  if (process.platform !== 'darwin') {
    app.quit();
  }
});

if (!app.requestSingleInstanceLock()) {
  app.quit();
} else {
  app.on('second-instance', () => {
    const [window] = BrowserWindow.getAllWindows();
    if (window !== undefined) {
      if (window.isMinimized()) {
        window.restore();
      }
      window.focus();
    }
  });
  main().catch((error: unknown) => {
    console.error('Ошибка запуска приложения:', error);
    app.quit();
  });
}
