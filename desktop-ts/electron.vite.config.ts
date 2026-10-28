import { resolve } from 'node:path';
import { defineConfig, type Plugin } from 'electron-vite';
import react from '@vitejs/plugin-react';
import tailwindcss from '@tailwindcss/vite';

const alias = {
  '@shared': resolve(__dirname, 'src/shared'),
  '@renderer': resolve(__dirname, 'src/renderer/src'),
  '@mocks': resolve(__dirname, 'src/mocks'),
};

/**
 * Content-Security-Policy для renderer. В сборке — строгая политика; в dev Vite нужны inline-скрипт
 * React Refresh, inline-стили HMR и WebSocket к dev-серверу.
 */
function contentSecurityPolicy(): Plugin {
  return {
    name: 'salon-csp',
    transformIndexHtml: {
      order: 'pre',
      handler(_html, ctx) {
        const dev = Boolean(ctx.server);
        const policy = [
          "default-src 'none'",
          dev ? "script-src 'self' 'unsafe-inline'" : "script-src 'self'",
          dev ? "style-src 'self' 'unsafe-inline'" : "style-src 'self'",
          "img-src 'self' data:",
          "font-src 'self'",
          dev ? "connect-src 'self' ws://localhost:* http://localhost:*" : "connect-src 'none'",
          "base-uri 'none'",
          "form-action 'none'",
          "object-src 'none'",
          "frame-src 'none'",
        ].join('; ');
        return [
          {
            tag: 'meta',
            attrs: { 'http-equiv': 'Content-Security-Policy', content: policy },
            injectTo: 'head-prepend',
          },
        ];
      },
    },
  };
}

export default defineConfig({
  main: {
    resolve: { alias },
  },
  preload: {
    resolve: { alias },
  },
  renderer: {
    resolve: { alias },
    plugins: [contentSecurityPolicy(), react(), tailwindcss()],
  },
});
