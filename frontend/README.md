# Pitsch frontend

React 19 + TypeScript + Vite single-page app. It talks only to the Pitsch backend (`/api/v1`).

```bash
npm ci
npm run dev        # http://localhost:5173, proxies /api to VITE_DEV_API_TARGET (default http://localhost:8080)
npm run lint       # ESLint (incl. React Compiler rules)
npm run build      # type-check + production build to dist/
npm test           # Vitest unit/component tests
npm run e2e        # Playwright journeys against the production build with a mocked API
```

E2E needs Chromium: `npx playwright install chromium` (or set `PW_CHROMIUM_PATH` to an installed one).

## Structure

```
src/
  lib/api/client.ts      fetch client: in-memory access token, single-flight refresh, CSRF header, ApiError(requestId)
  lib/api/endpoints.ts   every backend call, typed
  lib/api/types.ts       API contract types
  lib/auth/AuthContext   session state, login/signup/logout, permissions (can())
  lib/hooks.ts           useAsync (abortable, no spinner on background reloads), useInterval, useDebounced
  lib/safeUrl.ts         safeHttpUrl (untrusted links), safeAppPath (internal redirects only)
  components/            ui primitives, badges, BriefView, ImportEmailModal, layout/AppShell (nav, RequireAuth)
  pages/                 Dashboard, Inbox, Pitches, PitchDetail, Pipeline, Workflows, WorkflowDetail, Approvals,
                         Calendar, Tasks, Notifications, Integrations, Onboarding, auth/*, settings/*
  styles/app.css         design tokens and components
  test/                  Vitest tests
e2e/                     Playwright specs + mock API
```

Build-time settings (`.env.example`): `VITE_API_BASE_URL` (default `/api`), `VITE_GOOGLE_LOGIN`, `VITE_DEMO_MODE`.
They are public — never put secrets in `VITE_*` variables. The production image (`Dockerfile`) serves the build with
nginx, adds security headers (CSP, frame, nosniff) and proxies `/api` to `BACKEND_URL`.

Permissions are enforced by the backend; the UI only hides what the user's role cannot do.
