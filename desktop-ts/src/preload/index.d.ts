import type { SalonBridge } from '@shared/ipc';

declare global {
  interface Window {
    /** Мост из preload; в тестах подменяется. */
    salon: SalonBridge;
  }
}

export {};
