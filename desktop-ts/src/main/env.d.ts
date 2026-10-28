/// <reference types="electron-vite/node" />

interface ImportMetaEnv {
  readonly VITE_MOCK_API?: string;
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}
