/// <reference types="vite/client" />

interface ImportMetaEnv {
  /** Public base URL of the API, e.g. https://letthemknowapi.example.com/api/v1. Empty in local dev. */
  readonly VITE_PUBLIC_API_URL?: string;
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}
