 <div align="center">

<img src="https://capsule-render.vercel.app/api?type=waving&height=230&color=0:06152E,25:0B2D62,55:1457B8,80:168BEB,100:38BDF8&text=PITSCH&fontSize=76&fontColor=FFFFFF&fontAlignY=43&desc=INTELLIGENT%20INVESTMENT%20WORKFLOW%20AUTOMATION&descSize=15&descAlignY=64&animation=fadeIn" width="100%" alt="Pitsch"/>

<br/>

<img src="https://readme-typing-svg.demolab.com?font=Inter&weight=600&size=23&duration=2800&pause=1000&color=38BDF8&center=true&vCenter=true&width=800&height=50&lines=Transforming+Startup+Pitches+into+Intelligence;Eight+Specialized+AI+Agents.+One+Unified+Platform.;Research.+Verify.+Analyze.+Automate.;Human+Decisions.+Intelligent+Execution." alt="Pitsch animated tagline"/>

<br/>

**An AI-powered platform that transforms unstructured startup pitches into structured, evidence-backed investment intelligence.**

<br/><br/>

<a href="https://github.com/void024/pitsch">
<img src="https://img.shields.io/badge/GITHUB-REPOSITORY-0B1F3A?style=for-the-badge&logo=github&logoColor=white"/>
</a>
<a href="#architecture">
<img src="https://img.shields.io/badge/EXPLORE-ARCHITECTURE-1457B8?style=for-the-badge&logo=diagramsdotnet&logoColor=white"/>
</a>
<a href="#getting-started">
<img src="https://img.shields.io/badge/QUICK-START-087EA4?style=for-the-badge&logo=rocket&logoColor=white"/>
</a>

<br/><br/>

<img src="https://img.shields.io/badge/React-19-61DAFB?style=flat-square&logo=react&logoColor=black"/>
<img src="https://img.shields.io/badge/TypeScript-3178C6?style=flat-square&logo=typescript&logoColor=white"/>
<img src="https://img.shields.io/badge/Java-21-ED8B00?style=flat-square&logo=openjdk&logoColor=white"/>
<img src="https://img.shields.io/badge/Spring_Boot-3.5-6DB33F?style=flat-square&logo=springboot&logoColor=white"/>
<img src="https://img.shields.io/badge/Python-3776AB?style=flat-square&logo=python&logoColor=white"/>
<img src="https://img.shields.io/badge/FastAPI-009688?style=flat-square&logo=fastapi&logoColor=white"/>
<img src="https://img.shields.io/badge/PostgreSQL-4169E1?style=flat-square&logo=postgresql&logoColor=white"/>
<img src="https://img.shields.io/badge/Docker-2496ED?style=flat-square&logo=docker&logoColor=white"/>

<br/>

<a href="https://github.com/void024">
<strong>Developed by Ayush Kumawat</strong>
</a>

</div>

---

## 01. Overview

**Pitsch** is an AI-powered, human-in-the-loop pitch management and investment workflow automation platform designed for investors, venture capital firms, accelerators, and investment teams.

Investment teams receive hundreds of startup pitches through email. Evaluating each opportunity requires reading pitch decks, researching companies, validating founder claims, understanding market positioning, and coordinating follow-ups.

Pitsch brings these activities together through a coordinated multi-agent AI system.

It reads incoming pitches, identifies relevant opportunities, extracts information from documents, conducts external research, verifies claims, and generates structured investment briefs.

The platform then assists with communication, meeting scheduling, and deal pipeline management — while keeping investors in control of every consequential decision.

<div align="center">

### From an incoming pitch to investment intelligence.

<table>
<tr>
<td align="center" width="25%">
<strong>01</strong><br/><br/>
<strong>INGEST</strong><br/>
<sub>Incoming pitches and documents</sub>
</td>
<td align="center" width="25%">
<strong>02</strong><br/><br/>
<strong>INVESTIGATE</strong><br/>
<sub>Research and verification</sub>
</td>
<td align="center" width="25%">
<strong>03</strong><br/><br/>
<strong>ANALYZE</strong><br/>
<sub>Evidence-backed briefs</sub>
</td>
<td align="center" width="25%">
<strong>04</strong><br/><br/>
<strong>EXECUTE</strong><br/>
<sub>Human-approved actions</sub>
</td>
</tr>
</table>

</div>

**A fundamental design principle:**

> Pitsch does not make investment decisions. It provides investors with evidence, contradictions, risks, missing information, and questions to support better-informed decisions.

---

## 02. Core Capabilities

| Capability | Description |
|:---|:---|
| Intelligent Inbox | Ingest startup pitches through Gmail or manual email import, including attachments. |
| AI Classification | Identify new pitches, follow-ups, updates, and irrelevant messages. |
| Document Intelligence | Extract structured information from PDF and PPTX pitch decks. |
| Automated Research | Research startups, founders, markets, competitors, and business models. |
| Claim Verification | Evaluate founder claims against independently collected evidence. |
| Investment Brief Generation | Generate structured company analysis with evidence, risks, and opportunities. |
| Deal Pipeline Management | Track startup opportunities, investment stages, ownership, and follow-ups. |
| Workflow Orchestration | Coordinate multi-agent execution with persistent workflow states. |
| Smart Scheduling | Identify suitable meeting slots using calendar availability and working hours. |
| AI Email Drafting | Generate contextual email replies for investor review and approval. |
| Human-in-the-Loop Execution | Require approval before consequential external operations. |
| Audit & Notifications | Maintain traceability through audit records and workflow notifications. |
| Workspace Management | Support organizations, members, invitations, roles, and tenant isolation. |

---

## 03. Multi-Agent AI Architecture

Pitsch uses eight specialized AI agents, each responsible for a distinct stage of the investment workflow.

Rather than relying on one general-purpose model to perform everything, Pitsch distributes responsibilities across specialized agents with structured inputs, outputs, and execution boundaries.

### The Eight Specialized Agents

| Agent | Primary Responsibility |
|:---|:---|
| **01 — Email Classifier** | Understands incoming email intent and identifies new pitches, follow-ups, updates, and irrelevant messages. |
| **02 — Document Agent** | Extracts company information, founders, fundraising details, metrics, and claims from pitch decks. |
| **03 — Research Agent** | Conducts external research on startups, founders, competitors, and markets. |
| **04 — Verification Agent** | Cross-checks extracted claims against collected evidence and identifies unsupported or contradictory information. |
| **05 — Analysis Agent** | Produces structured investment briefs highlighting opportunities, risks, and missing information. |
| **06 — Calendar Agent** | Evaluates calendar availability and proposes suitable meeting slots. |
| **07 — Email Response Agent** | Generates contextual email drafts for investor communication. |
| **08 — Action Agent** | Prepares and processes authorized integration operations involving Gmail, Calendar, and Sheets. |

### Agent Execution Pipeline

<div align="center">

<table>
<tr>
<td align="center">

**STAGE 01 — INTAKE**

<br/>

<img src="https://img.shields.io/badge/INCOMING%20PITCH-123D70?style=for-the-badge"/>
<br/><br/>
<img src="https://img.shields.io/badge/EMAIL%20CLASSIFIER-1556A6?style=for-the-badge"/>

</td>
</tr>
<tr><td align="center">↓</td></tr>
<tr>
<td align="center">

**STAGE 02 — INTELLIGENCE**

<br/>

<img src="https://img.shields.io/badge/DOCUMENT-1556A6?style=flat-square"/>
<img src="https://img.shields.io/badge/RESEARCH-1556A6?style=flat-square"/>
<img src="https://img.shields.io/badge/VERIFICATION-1556A6?style=flat-square"/>
<img src="https://img.shields.io/badge/ANALYSIS-1556A6?style=flat-square"/>

</td>
</tr>
<tr><td align="center">↓</td></tr>
<tr>
<td align="center">

**STAGE 03 — INVESTOR REVIEW**

<br/>

<img src="https://img.shields.io/badge/INVESTMENT%20INTELLIGENCE%20BRIEF-087EA4?style=for-the-badge"/>

</td>
</tr>
<tr><td align="center">↓</td></tr>
<tr>
<td align="center">

**STAGE 04 — COMMUNICATION & SCHEDULING**

<br/>

<img src="https://img.shields.io/badge/CALENDAR-1556A6?style=flat-square"/>
<img src="https://img.shields.io/badge/EMAIL%20RESPONSE-1556A6?style=flat-square"/>
<img src="https://img.shields.io/badge/ACTION%20AGENT-1556A6?style=flat-square"/>

</td>
</tr>
<tr><td align="center">↓</td></tr>
<tr>
<td align="center">

**STAGE 05 — HUMAN APPROVAL**

<br/>

<img src="https://img.shields.io/badge/APPROVAL%20REQUIRED-1D4ED8?style=for-the-badge"/>

<br/><br/>

<table>
<tr>
<td align="center">

<img src="https://img.shields.io/badge/APPROVED-087EA4?style=for-the-badge"/>

<br/><br/>
Gmail · Calendar · Sheets

</td>
<td align="center">

<img src="https://img.shields.io/badge/REJECTED-475569?style=for-the-badge"/>

<br/><br/>
No external action

</td>
</tr>
</table>

</td>
</tr>
</table>

</div>

### Engineering Principles

- **Agent Specialization:** Each agent has a clearly defined responsibility and dedicated instructions.
- **Structured Outputs:** Responses are validated against predefined Pydantic schemas.
- **Provider Abstraction:** LLM integrations support provider-agnostic execution and fallback handling.
- **Resumable Workflows:** Persisted execution state helps recover from failures without unnecessarily repeating completed work.
- **Execution Observability:** Track token usage, model costs, execution latency, and agent status.
- **Prompt-Injection Defences:** Treat incoming emails, documents, and web content as untrusted data.
- **Controlled Actions:** Agents prepare operations, while the backend enforces permissions and approval requirements.

---

<a id="architecture"></a>

## 04. System Architecture

Pitsch follows a modular three-service architecture, separating the presentation layer, application orchestration, and AI intelligence layer.

### High-Level Architecture

<div align="center">

<table>
<tr>
<td colspan="3" align="center">

<strong>CLIENT LAYER</strong>

<br/><br/>

<img src="https://skillicons.dev/icons?i=react,ts,vite&theme=dark" alt="Frontend technologies"/>

<br/><br/>

**React 19 · TypeScript · Vite**

<br/>
<sub>Dashboard · Inbox · Pipeline · Workflows · Investment Briefs</sub>

</td>
</tr>
<tr>
<td colspan="3" align="center">

**REST API**

</td>
</tr>
<tr>
<td colspan="3" align="center">

<strong>APPLICATION LAYER</strong>

<br/><br/>

<img src="https://skillicons.dev/icons?i=java,spring&theme=dark" alt="Backend technologies"/>

<br/><br/>

**Java 21 · Spring Boot 3.5**

<br/>
<sub>Authentication · Authorization · Workflow Orchestration · Persistence · Approvals</sub>

</td>
</tr>
<tr>
<td colspan="3" align="center">

**AUTHENTICATED INTERNAL API**

</td>
</tr>
<tr>
<td colspan="3" align="center">

<strong>AI INTELLIGENCE LAYER</strong>

<br/><br/>

<img src="https://skillicons.dev/icons?i=python,fastapi&theme=dark" alt="AI technologies"/>

<br/><br/>

**Python · FastAPI · Pydantic**

<br/>
<sub>Eight Specialized Agents · LLM Integration · Research · Verification</sub>

</td>
</tr>
<tr>
<td colspan="3" align="center">

**EXTERNAL PROVIDERS & INFRASTRUCTURE**

</td>
</tr>
<tr>
<td align="center" width="33%">

<img src="https://skillicons.dev/icons?i=postgres&theme=dark" alt="PostgreSQL"/>

<br/>

**PostgreSQL**

<br/>
<sub>Application state, jobs and audit</sub>

</td>
<td align="center" width="33%">

<img src="https://skillicons.dev/icons?i=docker&theme=dark" alt="Docker"/>

<br/>

**S3 / MinIO**

<br/>
<sub>Document storage</sub>

</td>
<td align="center" width="33%">

<img src="https://skillicons.dev/icons?i=googlecloud&theme=dark" alt="Google Cloud"/>

<br/>

**External Services**

<br/>
<sub>Gmail, Calendar, Sheets, LLM, Tavily</sub>

</td>
</tr>
</table>

</div>

### Frontend — `frontend/`

**React 19 · TypeScript · Vite**

The frontend provides the investor-facing workspace.

- Component-based application architecture.
- Typed API client and React Router.
- Authentication and session management.
- Dashboard and investment analytics.
- Intelligent inbox and pitch management.
- Deal pipeline and workflow monitoring.
- Investment brief visualization.
- Calendar, tasks, and notifications.
- Workspace settings and integration management.

### Backend — `backend/`

**Java 21 · Spring Boot 3.5**

The backend is the central application, security, and orchestration layer.

- REST API architecture.
- Authentication and authorization.
- Workspace management and tenant isolation.
- Role-based and permission-based access control.
- Workflow state machine and orchestration.
- PostgreSQL persistence with Flyway migrations.
- Background job processing and retries.
- Google provider integrations.
- File storage and document management.
- Approval management and audit logging.
- Subscription management and usage quotas.
- Privacy and account management.

### AI Service — `ai-service/`

**Python · FastAPI · Pydantic**

The AI service handles intelligent processing and specialized agent execution.

- Eight specialized AI agents.
- Provider-agnostic LLM client with fallback.
- Structured output validation.
- Document extraction.
- External research through Tavily.
- Claim verification and evidence evaluation.
- Prompt-injection detection and guardrails.
- Retry and error handling.
- Token usage, latency, and cost tracking.
- AI evaluation datasets and test suites.

The AI service does not directly access the application's database, Gmail, or Calendar. Consequential operations are controlled by the backend.

---

## 05. Technology Stack

<div align="center">

**FRONTEND**

<img src="https://skillicons.dev/icons?i=react,ts,vite,html,css&theme=dark" alt="Frontend stack"/>

<br/><br/>

**BACKEND**

<img src="https://skillicons.dev/icons?i=java,spring,postgres,maven&theme=dark" alt="Backend stack"/>

<br/><br/>

**AI & INFRASTRUCTURE**

<img src="https://skillicons.dev/icons?i=python,fastapi,docker,git,github&theme=dark" alt="AI and infrastructure stack"/>

</div>

| Category | Technologies |
|:---|:---|
| Frontend | React 19, TypeScript, Vite, React Router |
| Backend | Java 21, Spring Boot 3.5, Spring Data JPA |
| AI Framework | Python, FastAPI, Pydantic |
| Database | PostgreSQL 16, H2 |
| ORM | Hibernate |
| Migrations | Flyway |
| LLM Integration | Gemini, OpenAI-compatible providers |
| Web Research | Tavily |
| Integrations | Gmail, Google Calendar, Google Sheets |
| Object Storage | AWS S3-compatible storage, MinIO |
| Containerization | Docker, Docker Compose |
| Testing | Vitest, Playwright, JUnit, Pytest |
| API Documentation | OpenAPI |

---

## 06. Security & Responsible AI

Security is built into the architecture rather than treated as an afterthought.

### Application Security

- Default-deny authorization with permission checks.
- Workspace isolation and server-derived tenant context.
- Short-lived access tokens and rotating HTTP-only refresh cookies.
- PBKDF2 password hashing.
- AES-256-GCM encrypted OAuth credentials.
- CSRF protection, CORS allowlists, CSP, and security headers.
- Validated document uploads and file-size restrictions.
- Rate limiting, account lockout, and usage quotas.
- Secure configuration validation in production.

### AI Security

- Untrusted external content is treated as data.
- Prompt-injection detection and guardrails.
- Schema validation for model responses.
- No direct model access to application databases or external integrations.
- Backend-enforced authorization for operations.
- Human approval before sending emails or changing calendars.
- Audit records for external actions.

**The objective is to make AI-assisted investment workflows traceable, controlled, and accountable.**

Further details: [Security](docs/SECURITY.md) · [Data Security](docs/DATA_SECURITY.md) · [Privacy](docs/PRIVACY.md)

---

<a id="getting-started"></a>

## 07. Getting Started

### Prerequisites

| Requirement | Version |
|:---|:---|
| Java | 21 |
| Node.js | 20.19+ |
| Python | 3.11+ |
| Docker | Latest stable |
| Docker Compose | Latest stable |

You will also need an LLM provider API key. Tavily is optional but required for live external web research.

### Option A — Docker (Recommended)

**1. Clone the repository**

```bash
git clone https://github.com/void024/pitsch.git
cd pitsch
```

**2. Generate development environment variables**

```bash
./scripts/gen-dev-env.sh
```

On Windows, use Git Bash or WSL.

**3. Configure API credentials**

Open the generated `.env` file and configure your LLM credentials.

Example:

```env
LLM_PROVIDER=gemini
LLM_MODEL=gemini-flash-latest
LLM_API_KEY=your_api_key

TAVILY_API_KEY=your_tavily_api_key
```

Never commit real API keys or production credentials.

**4. Start the application**

```bash
docker compose up --build
```

**5. Open Pitsch**

```text
http://localhost:3000
```

The default Docker configuration runs in demo mode, using mock Gmail, Calendar, and Sheets integrations. AI agents still use the configured LLM provider.

The demo environment seeds:

```text
Email:    demo@pitsch.app
Password: pitsch-demo-2026
```

The demo password can be overridden using `DEMO_PASSWORD`.

Demo credentials are intended only for local development. Do not use them in production.

### Option B — Run Without Docker

Requirements: Java 21, Node.js 20.19+, Python 3.11+.

**1. Configure the AI service**

```bash
cp ai-service/.env.example ai-service/.env
```

Configure the LLM provider and API credentials.

**2. Start the application**

From the repository root:

```bash
./start-dev.sh
```

On Windows:

```bash
start-dev.bat
```

This configuration uses an H2 file database and local file storage.

**3. Access the frontend**

```text
http://localhost:5173
```

---

## 08. Experience the Workflow

Once Pitsch is running, follow this journey:

| Step | Action |
|:---|:---|
| 01 | Sign in to the investor workspace. |
| 02 | Navigate to Inbox and select Import Email. |
| 03 | Import a startup pitch and attach a PDF/PPTX deck. |
| 04 | Observe the Email Classifier process the incoming pitch. |
| 05 | Approve handling of the pitch. |
| 06 | Watch the Document, Research, Verification, and Analysis agents execute. |
| 07 | Review the generated investment brief and supporting evidence. |
| 08 | Plan a meeting using available calendar slots. |
| 09 | Draft, edit, and approve an email response. |
| 10 | Review executed operations in Settings → Audit Log. |

---

## 09. Testing & Quality Assurance

Pitsch includes automated testing across all three services.

### Frontend

```bash
cd frontend

npm run lint
npm run build
npm test
npm run e2e
```

### Backend

```bash
cd backend

./mvnw verify
```

For PostgreSQL-backed testing:

```bash
TEST_DB_URL=jdbc:postgresql://localhost:5432/pitsch_test
TEST_DDL_AUTO=validate
```

### AI Service

```bash
cd ai-service

python -m pytest -q
```

Run AI evaluation suites:

```bash
python -m evals.run_evals
```

For live AI quality evaluation:

```bash
python -m evals.run_evals --suite live
```

Evaluation metrics include accuracy, false-SUPPORTED rate, latency, and cost.

### Continuous Integration

The CI pipeline includes:

- Frontend linting, build, unit tests, and E2E tests.
- Backend tests on H2 and PostgreSQL.
- AI service tests and evaluation suites.
- Secret scanning and dependency audits.
- Trivy container security scanning.
- CodeQL analysis.
- Docker image builds.

No standard automated test requires live Google, LLM, search, or Stripe credentials.

---

## 10. Repository Structure

```text
pitsch/
│
├── frontend/
│   ├── src/
│   │   ├── components/
│   │   ├── pages/
│   │   ├── styles/
│   │   ├── lib/
│   │   └── test/
│   ├── e2e/
│   └── package.json
│
├── backend/
│   ├── src/
│   │   ├── main/
│   │   └── test/
│   └── pom.xml
│
├── ai-service/
│   ├── app/
│   │   ├── agents/
│   │   │   ├── classifier/
│   │   │   ├── document/
│   │   │   ├── research/
│   │   │   ├── verification/
│   │   │   ├── analysis/
│   │   │   ├── calendar/
│   │   │   ├── email_response/
│   │   │   └── action/
│   │   ├── api/
│   │   ├── core/
│   │   └── schemas/
│   ├── evals/
│   ├── tests/
│   └── requirements.txt
│
├── docs/
│   ├── ARCHITECTURE.md
│   ├── AI_ARCHITECTURE.md
│   ├── ARCHITECTURAL_DECISIONS.md
│   ├── SECURITY.md
│   ├── DATA_SECURITY.md
│   ├── PRIVACY.md
│   ├── INTEGRATIONS.md
│   ├── DEPLOYMENT.md
│   ├── OPERATIONS.md
│   └── API.md
│
├── scripts/
├── docker-compose.yml
├── start-dev.sh
├── start-dev.bat
└── README.md
```

---

## 11. Deployment & Operations

Pitsch is designed around containerized deployment.

The production deployment consists of:

| Component | Responsibility |
|:---|:---|
| Frontend Container | Serves the React application through Nginx. |
| Backend Container | Handles application APIs, authentication, workflows, and integrations. |
| AI Service Container | Executes specialized AI agents and research operations. |
| PostgreSQL | Stores application data, workflow state, jobs, and audit records. |
| S3-Compatible Storage | Stores pitch decks and uploaded documents. |
| SMTP | Handles application email delivery. |
| Google Cloud | Provides Gmail, Calendar, and Sheets integrations. |
| Optional Stripe | Supports subscription checkout, billing portal, and webhooks. |

Production deployment requires secure configuration, managed database infrastructure, HTTPS, and properly configured external services.

See [Deployment Documentation](docs/DEPLOYMENT.md) and [Operations Runbook](docs/OPERATIONS.md).

---

## 12. Current Limitations

Some capabilities have known limitations and are documented for transparency.

- Gmail integration requires Google OAuth verification before unrestricted public use.
- Rate limiting is per application instance; distributed rate limiting is a future improvement.
- Malware scanning through ClamAV is optional and disabled by default.
- Scanned image-only pitch decks do not currently undergo OCR.
- Follow-up matching uses deterministic candidates and classifier judgement rather than embeddings.
- AI quality depends on the selected model and external research provider.
- Live evaluation datasets are synthetic and relatively small.
- Deployment currently uses logical multi-tenancy within a single database.
- Mobile and web push notifications are not implemented; in-app and email notifications are supported.

---

## 13. Roadmap

| Planned Improvement | Description |
|:---|:---|
| OCR | Support scanned and image-only pitch decks. |
| Research Expansion | Introduce a second search provider for independent cross-checking. |
| Semantic Matching | Improve follow-up identification using embeddings. |
| Microsoft 365 | Add Outlook and Microsoft Calendar integrations. |
| CRM Integration | Connect investment workflows with Affinity and HubSpot. |
| Push Notifications | Introduce web and mobile push notifications. |
| Enterprise Identity | Add SAML/OIDC SSO and SCIM provisioning. |
| Distributed Infrastructure | Introduce Redis-backed distributed rate limiting. |

---

## 14. Documentation

Explore the engineering documentation for more details.

| Document | Description |
|:---|:---|
| [System Architecture](docs/ARCHITECTURE.md) | Overall system architecture and service boundaries. |
| [AI Architecture](docs/AI_ARCHITECTURE.md) | Multi-agent architecture and execution pipeline. |
| [Architectural Decisions](docs/ARCHITECTURAL_DECISIONS.md) | Important engineering decisions and trade-offs. |
| [Security](docs/SECURITY.md) | Application and infrastructure security. |
| [Data Security](docs/DATA_SECURITY.md) | Data protection and security controls. |
| [Privacy](docs/PRIVACY.md) | Privacy and data lifecycle management. |
| [Integrations](docs/INTEGRATIONS.md) | External provider integrations. |
| [API Reference](docs/API.md) | API endpoints and usage. |
| [Deployment](docs/DEPLOYMENT.md) | Production deployment instructions. |
| [Operations](docs/OPERATIONS.md) | Monitoring and operational guidelines. |
| [Contributing](docs/CONTRIBUTING.md) | Contribution guidelines. |
| [Production Audit](docs/PRODUCTION_AUDIT.md) | Production readiness audit. |

---

## 15. Team

Pitsch is built through collaboration across frontend engineering, backend engineering, and AI development.

<table>
<tr>
<th align="left">Contributor</th>
<th align="left">Engineering Focus</th>
<th align="left">Responsibilities</th>
</tr>
<tr>
<td>

<a href="https://github.com/void024"><strong>Ayush Kumawat</strong></a>

</td>
<td>

<strong>Backend & AI Engineering</strong>

</td>
<td>

Designed and developed the Spring Boot backend, REST APIs, authentication, authorization, PostgreSQL architecture, workflow orchestration, external integrations, security, multi-agent AI architecture, LLM integrations, agent orchestration, document intelligence, automated research, claim verification, and investment analysis.

</td>
</tr>
<tr>
<td>

<strong>Yash Kamble</strong>

</td>
<td>

<strong>Frontend Engineering</strong>

</td>
<td>

Developed the React frontend, dashboard, authentication interfaces, pitch management, workflow interfaces, notifications, investment briefs, and frontend API integration.

</td>
</tr>
</table>

---

<div align="center">

<img src="https://readme-typing-svg.demolab.com?font=Inter&weight=600&size=22&duration=3000&pause=1000&color=38BDF8&center=true&vCenter=true&width=750&height=45&lines=Intelligence+Behind+Every+Opportunity.;Evidence+Behind+Every+Insight.;Humans+Behind+Every+Decision." alt="Pitsch closing statement"/>

<br/><br/>

**PITSCH**

<sub>AI-POWERED INVESTMENT WORKFLOW AUTOMATION</sub>

<br/><br/>

<a href="https://github.com/void024">
<img src="https://img.shields.io/badge/BUILT%20BY-AYUSH%20KUMAWAT-1457B8?style=for-the-badge&logo=github&logoColor=white"/>
</a>

<br/><br/>

<img src="https://capsule-render.vercel.app/api?type=waving&height=140&section=footer&color=0:38BDF8,30:168BEB,65:1457B8,100:06152E" width="100%" alt="Pitsch blue footer"/>

</div>
