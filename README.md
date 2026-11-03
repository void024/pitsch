# PITSCH

<p align="center">
  <img src="https://capsule-render.vercel.app/api?type=waving&color=0:071A35,50:0755B8,100:00A6FB&height=220&section=header&text=PITSCH&fontSize=72&fontColor=FFFFFF&fontAlignY=38&desc=Intelligent%20Pitch%20Operations%20Platform&descAlignY=60&descSize=18" width="100%" alt="Pitsch banner"/>
</p>

<p align="center">
  <img src="https://readme-typing-svg.demolab.com?font=JetBrains+Mono&weight=500&size=17&duration=3000&pause=900&color=38BDF8&center=true&vCenter=true&width=700&lines=AI-powered+pitch+operations;Multi-agent+workflow+automation;From+inbox+to+execution;Built+for+smarter+workflows" alt="Typing SVG"/>
</p>

<p align="center">
  <img src="https://img.shields.io/badge/STATUS-Active%20Development-0A84FF?style=for-the-badge"/>
  <img src="https://img.shields.io/badge/AI-Multi--Agent-2563EB?style=for-the-badge"/>
  <img src="https://img.shields.io/badge/Platform-Web-0284C7?style=for-the-badge"/>
</p>

<p align="center">
  <a href="https://github.com/void024/pitsch">GitHub Repository</a> •
  <a href="https://github.com/void024/pitsch/tree/main/docs">Engineering Documentation</a>
</p>

---

## Overview

**Pitsch** is an AI-powered pitch operations platform designed to streamline workflows, manage communications, coordinate tasks, and bring intelligent automation into a unified workspace.

It combines a modern web application, backend services, and a multi-agent AI system to help users manage their operational workflows more efficiently.

Rather than treating every operation as an isolated task, Pitsch connects different stages of the workflow through coordinated services and specialized AI agents.

## Key Capabilities

- **AI-powered workflows** — Automate and coordinate multi-step operational processes.
- **Multi-agent architecture** — Specialized AI agents handle different responsibilities.
- **Centralized workspace** — Manage pitches, tasks, workflows, and related activities.
- **Inbox management** — Organize communication and extract actionable information.
- **Workflow execution** — Track and manage operational processes.
- **Approval management** — Support controlled approval workflows.
- **Calendar and task management** — Organize activities and deadlines.
- **External integrations** — Connect with external services and providers.
- **Secure application architecture** — Designed with authentication, authorization, and data protection considerations.

## AI Agent Architecture

Pitsch incorporates a multi-agent AI architecture consisting of eight specialized agents.

Each agent is designed to handle a specific responsibility within the broader workflow. The architecture separates responsibilities into specialized components rather than relying on a single monolithic AI operation.

The AI service coordinates agent execution and supports structured workflow processing.

### Agent Workflow

<p align="center">
  <img src="https://capsule-render.vercel.app/api?type=rect&color=0:0B2B52,100:0755B8&height=3" width="80%"/>
</p>

| Stage | Responsibility |
|---|---|
| Input | Receive workflow context and relevant information. |
| Processing | Identify the required operation and prepare relevant context. |
| Agent Execution | Route work to specialized AI agents. |
| Coordination | Coordinate outputs across the workflow. |
| Validation | Process and validate generated results. |
| Output | Return structured results to the application. |

For the detailed agent architecture and execution pipeline, see [AI Architecture](docs/AI_ARCHITECTURE.md).

## System Architecture

Pitsch follows a service-oriented architecture with distinct frontend, backend, and AI service responsibilities.

| Component | Responsibility |
|---|---|
| Frontend | User interface, navigation, and application interactions. |
| Backend | Application APIs, business logic, and service coordination. |
| AI Service | AI agents, intelligent processing, and workflow automation. |
| Database | Persistent application data storage. |
| Integrations | Communication with external providers and services. |

### High-Level Architecture

```text
                    ┌──────────────────────┐
                    │      USER            │
                    └──────────┬───────────┘
                               │
                               ▼
                    ┌──────────────────────┐
                    │   REACT FRONTEND     │
                    │   User Interface     │
                    └──────────┬───────────┘
                               │
                               ▼
                    ┌──────────────────────┐
                    │   BACKEND SERVICES   │
                    │   REST APIs          │
                    │   Business Logic     │
                    └──────────┬───────────┘
                               │
                  ┌────────────┴────────────┐
                  │                         │
                  ▼                         ▼
       ┌────────────────────┐    ┌────────────────────┐
       │    DATABASE        │    │    AI SERVICE      │
       │    PostgreSQL      │    │    Python          │
       └────────────────────┘    └─────────┬──────────┘
                                           │
                                           ▼
                                ┌────────────────────┐
                                │  SPECIALIZED AI    │
                                │  AGENTS            │
                                └────────────────────┘
```

## Technology Stack

<p align="center">
  <img src="https://skillicons.dev/icons?i=react,ts,vite,java,spring,python,fastapi,postgres,docker,git,github&perline=6" alt="Technology stack"/>
</p>

| Layer | Technologies |
|---|---|
| Frontend | React, TypeScript, Vite |
| Backend | Java, Spring Boot |
| AI Services | Python, FastAPI |
| Database | PostgreSQL |
| Infrastructure | Docker |
| Version Control | Git, GitHub |

## Repository Structure

```text
pitsch/
│
├── frontend/
│   ├── public/
│   ├── src/
│   │   ├── assets/
│   │   ├── components/
│   │   ├── lib/
│   │   ├── pages/
│   │   ├── styles/
│   │   └── test/
│   └── README.md
│
├── backend/
│   └── Backend application and services
│
├── ai-service/
│   ├── evals/
│   │   └── README.md
│   └── AI service implementation
│
├── docs/
│   ├── AI_ARCHITECTURE.md
│   ├── API.md
│   ├── ARCHITECTURAL_DECISIONS.md
│   ├── ARCHITECTURE.md
│   ├── CHANGELOG_PRODUCTION_UPGRADE.md
│   ├── CONTRIBUTING.md
│   ├── DATA_SECURITY.md
│   ├── DEPLOYMENT.md
│   ├── INTEGRATIONS.md
│   ├── OPERATIONS.md
│   ├── PRIVACY.md
│   ├── PRODUCTION_AUDIT.md
│   └── SECURITY.md
│
└── README.md
```

## Getting Started

### Prerequisites

Before setting up Pitsch locally, ensure that you have the required development tools installed:

- Git
- Node.js and npm
- Java Development Kit
- Python
- PostgreSQL
- Docker (if using containerized services)

### 1. Clone the Repository

```bash
git clone https://github.com/void024/pitsch.git
cd pitsch
```

### 2. Frontend Setup

```bash
cd frontend
npm install
npm run dev
```

### 3. Backend Setup

Navigate to the backend directory and configure the required environment variables and database connection.

Use the project's backend build configuration and application instructions to install dependencies and start the backend service.

### 4. AI Service Setup

Navigate to the AI service directory.

Configure the required Python environment, dependencies, API credentials, and service settings according to the AI service configuration.

### 5. Environment Configuration

Configure environment variables for the services you intend to run.

**Important:** Never commit real credentials, API keys, database passwords, or secret configuration files to GitHub.

Use environment example files where provided.

## Security and Data Protection

Security is an important consideration in Pitsch's architecture.

The engineering documentation covers:

- Application security
- Authentication and authorization
- Data security controls
- Privacy and data lifecycle management
- External integrations
- Production readiness

For implementation details, refer to the dedicated security and privacy documentation below.

## Testing and Evaluation

Pitsch includes frontend testing resources and an AI evaluation directory.

Testing and evaluation are important for validating application behavior, service integration, and AI workflow outputs.

Refer to the relevant project documentation and service-specific instructions for running the available tests.

## Engineering Documentation

Explore the engineering documentation for detailed insights into Pitsch's architecture, implementation, security, and deployment.

| Document | Description |
|---|---|
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

## Roadmap

Pitsch is an evolving project. Future development can focus on:

- Improving multi-agent coordination.
- Expanding workflow automation capabilities.
- Enhancing external integrations.
- Improving AI evaluation and reliability.
- Strengthening observability and operational tooling.
- Improving scalability and performance.
- Expanding the application experience.

## Team

Pitsch is a collaborative engineering project built through contributions across frontend, backend, and AI engineering.

| Contributor | Responsibility |
|---|---|
| **Ayush Kumawat** | Backend Engineering & AI Services |
| **Yash Kamble** | Frontend Engineering |

## Contributing

Contributions and suggestions are welcome.

Please refer to the [Contributing Guidelines](docs/CONTRIBUTING.md) before making changes.

## Project

<p align="center">
  <a href="https://github.com/void024/pitsch">
    <img src="https://img.shields.io/badge/Explore%20Repository-Pitsch-0969DA?style=for-the-badge&logo=github&logoColor=white"/>
  </a>
</p>

<p align="center">
  <strong>BUILT BY THE PITSCH TEAM</strong>
</p>

<p align="center">
  <img src="https://capsule-render.vercel.app/api?type=waving&color=0:00A6FB,50:0755B8,100:071A35&height=120&section=footer" width="100%" alt="Footer banner"/>
</p>
