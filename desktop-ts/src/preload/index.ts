import { contextBridge, ipcRenderer } from 'electron';
import { IpcChannel, type SalonBridge } from '@shared/ipc';

/**
 * Мост между страницей и main-процессом. Наружу отдаются только узкие функции,
 * а не ipcRenderer целиком; токен API в renderer не попадает.
 */
const bridge: SalonBridge = {
  api: {
    request: (request) => ipcRenderer.invoke(IpcChannel.apiRequest, request),
  },
  settings: {
    get: () => ipcRenderer.invoke(IpcChannel.settingsGet),
    save: (update) => ipcRenderer.invoke(IpcChannel.settingsSave, update),
  },
};

contextBridge.exposeInMainWorld('salon', bridge);
