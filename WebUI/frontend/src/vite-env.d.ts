/// <reference types="vite/client" />

interface ImportMetaEnv {
  readonly VITE_FRONTEND_DEMO?:string;
}

interface ImportMeta {
  readonly env:ImportMetaEnv;
}
