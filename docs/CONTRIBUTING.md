# Contributing

## Repository layout

```
frontend/     React 19 + TypeScript + Vite SPA (src/lib = API client, auth, hooks; src/pages; src/components)
backend/      Spring Boot 3.5 / Java 21 (package per domain: auth, org, pitch, email, workflow, approval, jobs, …)
ai-service/   FastAPI agents (app/agents/<agent>/{agent,schemas,prompts}.py), tests/, evals/
docs/         Architecture, security, operations, ADRs
.github/      CI, CodeQL, release, Dependabot
```

## Local setup

Requirements: Java 21, Node 20.19+ (22 recommended), Python 3.11+ (3.12 recommended); Docker optional.

```bash
cp ai-service/.env.example ai-service/.env      # add LLM_API_KEY (+ TAVILY_API_KEY)
./start-dev.sh                                  # demo mode: H2, mock Google, demo@pitsch.app
# or, everything in containers with PostgreSQL + MinIO:
./scripts/gen-dev-env.sh && docker compose up --build     # http://localhost:3000
```

Manual: `cd ai-service && python -m venv .venv && .venv/bin/pip install -r requirements-dev.txt &&
PITSCH_MODE=development .venv/bin/uvicorn app.main:app --port 8000` · `cd backend && PITSCH_MODE=demo ./mvnw
spring-boot:run` · `cd frontend && npm ci && npm run dev` (proxies `/api` to `:8080`).

## Checks (all run in CI)

```bash
cd frontend && npm run lint && npm run build && npm test && npm run e2e
cd backend && ./mvnw verify                                   # H2
TEST_DB_URL=jdbc:postgresql://localhost:5432/pitsch_test TEST_DB_USERNAME=… TEST_DB_PASSWORD=… \
  TEST_DDL_AUTO=validate ./mvnw test                          # PostgreSQL + schema validation
cd ai-service && python -m pytest -q && python -m evals.run_evals
```

No test may need real Google, LLM, search or Stripe credentials: the backend uses `FakeAiClient` and demo providers,
the AI service mocks the LLM client, the E2E suite mocks the API with Playwright routes.

## Rules of the codebase

* **Tenancy:** every new tenant table gets `organization_id NOT NULL` + an index leading on it; every repository
  method used by a controller takes the organization id from `AuthPrincipal`. Add a case to
  `TenantIsolationTests` for new resources.
* **Authorization:** new endpoints need `@RequiresPermission` (or a deliberate `@PublicEndpoint`, which also means
  updating the pinned list in `BackendApplicationTests`). Never check roles in the frontend only.
* **Schema:** add a Flyway migration (`db/migration/common/V<n>__*.sql`, vendor-specific in `postgresql/` + `h2/`);
  never edit an applied migration; Hibernate runs `validate`.
* **Side effects:** anything that changes the outside world goes through an approval or workspace policy, a job and
  the `external_operations` ledger with a deterministic idempotency key. Never call a provider from a request thread
  for a consequential action.
* **Workflow state:** change status only through `WorkflowStore` / `WorkflowStatus.requireTransition`.
* **AI output:** validate with Pydantic in the AI service and re-validate business rules in the backend; never let
  model output pick recipients, ids or URLs that the input did not contain.
* **Errors:** throw `ApiException(ErrorCode, message)`; messages are user-facing, no internals.
* **Logging:** ids and counts only — never tokens, passwords, email bodies, document text or prompts.
* **Frontend:** API calls only through `src/lib/api/endpoints.ts`; types in `src/lib/api/types.ts`; render untrusted
  URLs through `safeHttpUrl`; no `dangerouslySetInnerHTML`; consistent loading/empty/error states via `components/ui`.
* **Prompts:** changing a prompt, model or provider requires running the live evals and attaching the report to the PR.
* Record non-obvious design choices in `docs/ARCHITECTURAL_DECISIONS.md`.

## Pull requests

Small, focused PRs with tests. CI (lint, build, unit + integration + E2E tests, PostgreSQL run, evals, secret scan,
dependency audit, Docker builds) must pass. Do not commit `.env` files or real data; test fixtures are synthetic.
