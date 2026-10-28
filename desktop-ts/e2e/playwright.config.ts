import { defineConfig } from '@playwright/test';

/**
 * E2E: настоящее Electron-приложение (собранное в out/) + настоящий бэкенд (java -jar target/salon-bot.jar)
 * во временной папке, на свободном порту и с тестовым токеном. Подробности — в README, раздел «E2E-тесты».
 */
export default defineConfig({
  testDir: '.',
  testMatch: '**/*.spec.ts',
  // Один бэкенд на файл со сценариями: файлы идут строго по очереди.
  workers: 1,
  fullyParallel: false,
  timeout: 60_000,
  expect: { timeout: 10_000 },
  retries: 0,
  reporter: [['list']],
  outputDir: '../test-results',
  globalSetup: './helpers/global-setup.ts',
  globalTeardown: './helpers/global-teardown.ts',
});
