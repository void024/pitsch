/// <reference types="vite/client" />

interface ImportMetaEnv {
  /** API base, e.g. https://api.pitsch.example/api. Default "/api" (same origin; proxied by Vite in development). */
  readonly VITE_API_BASE_URL?: string;
  /** "true" shows "Continue with Google" on the sign-in page (backend GOOGLE_LOGIN_ENABLED must also be true). */
  readonly VITE_GOOGLE_LOGIN?: string;
  /** "true" pre-fills the demo account on the sign-in page (only for PITSCH_MODE=demo deployments). */
  readonly VITE_DEMO_MODE?: string;
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}
