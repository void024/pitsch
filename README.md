 <div align="center">

<img src="https://capsule-render.vercel.app/api?type=venom&height=220&color=0:071A3D,35:123D80,70:1769D2,100:00B8FF&text=PITSCH&fontColor=FFFFFF&fontSize=75&fontAlignY=45&desc=AI-Powered%20Investment%20Workflow%20Automation&descSize=17&descAlignY=67&animation=fadeIn" width="100%" alt="Pitsch"/>

<br/>

<img src="https://readme-typing-svg.demolab.com?font=Inter&weight=600&size=22&duration=2800&pause=900&color=38BDF8&center=true&vCenter=true&width=750&lines=Intelligent+Investment+Workflows.;From+Startup+Pitches+to+Investment+Intelligence.;Eight+Specialized+AI+Agents.;Research.+Verify.+Analyze.+Decide." alt="Animated introduction"/>

<br/>

**An intelligent platform that transforms startup pitches into structured, evidence-backed investment intelligence.**

<br/>

<a href="#overview"><img src="https://img.shields.io/badge/EXPLORE-PITSCH-1677FF?style=for-the-badge&logo=github&logoColor=white" alt="Explore"/></a>
<a href="#architecture"><img src="https://img.shields.io/badge/SYSTEM-ARCHITECTURE-0B3D91?style=for-the-badge&logo=diagramsdotnet&logoColor=white" alt="Architecture"/></a>
<a href="#getting-started"><img src="https://img.shields.io/badge/GET-STARTED-0891B2?style=for-the-badge&logo=rocket&logoColor=white" alt="Get Started"/></a>

<br/><br/>

<img src="https://img.shields.io/badge/React-19-61DAFB?style=flat-square&logo=react&logoColor=black"/>
<img src="https://img.shields.io/badge/TypeScript-3178C6?style=flat-square&logo=typescript&logoColor=white"/>
<img src="https://img.shields.io/badge/Java-21-ED8B00?style=flat-square&logo=openjdk&logoColor=white"/>
<img src="https://img.shields.io/badge/Spring_Boot-6DB33F?style=flat-square&logo=springboot&logoColor=white"/>
<img src="https://img.shields.io/badge/Python-3776AB?style=flat-square&logo=python&logoColor=white"/>
<img src="https://img.shields.io/badge/FastAPI-009688?style=flat-square&logo=fastapi&logoColor=white"/>
<img src="https://img.shields.io/badge/PostgreSQL-4169E1?style=flat-square&logo=postgresql&logoColor=white"/>
<img src="https://img.shields.io/badge/Docker-2496ED?style=flat-square&logo=docker&logoColor=white"/>

</div>

---

<a id="overview"></a>

## Overview

**Pitsch** is an AI-powered investment workflow automation platform designed to help investors, venture capital firms, and investment teams streamline startup deal evaluation.

Evaluating startup pitches involves much more than reading a presentation. It requires extracting company information, researching markets, validating founder claims, identifying risks, preparing investment briefs, and coordinating follow-ups.

Pitsch brings these activities together through a coordinated multi-agent AI system.

It transforms an incoming startup pitch into a structured research workflow, helping investment teams move from scattered information to actionable intelligence.

**The philosophy behind Pitsch:**

> AI handles the research and repetitive work. Investors retain control over the decisions.

---

## Core Capabilities

| Capability | Description |
|---|---|
| Intelligent Inbox | Organize and process incoming startup pitches and email communications. |
| AI Classification | Automatically identify pitches, follow-ups, updates and irrelevant messages. |
| Document Intelligence | Extract structured company information and claims from pitch decks. |
| Automated Research | Gather external information about startups, founders, competitors and markets. |
| Claim Verification | Evaluate founder claims against independently collected evidence. |
| Investment Intelligence | Generate structured investment briefs highlighting opportunities, risks and missing information. |
| Deal Pipeline | Manage startup opportunities across different investment stages. |
| Workflow Automation | Coordinate multi-agent workflows and track their execution. |
| Smart Scheduling | Identify suitable meeting slots using calendar availability. |
| AI Communication | Generate contextual email drafts for investor communication. |
| Human Approvals | Review and authorize consequential external actions. |
| Activity & Audit Tracking | Maintain visibility into workflow executions and system activity. |

---

## Multi-Agent Intelligence

Pitsch uses eight specialized AI agents, each responsible for a specific part of the investment workflow.

Rather than relying on a single general-purpose AI model, the system divides complex tasks into smaller, specialized responsibilities.

| Agent | Responsibility |
|---|---|
| Email Classifier | Understands incoming email intent and identifies startup pitches and follow-ups. |
| Document Agent | Extracts structured information, founder details, financial metrics and claims from pitch decks. |
| Research Agent | Collects external company, founder, market and competitor information. |
| Verification Agent | Cross-checks extracted claims against available evidence and identifies discrepancies. |
| Analysis Agent | Produces structured investment research briefs, including risks and opportunities. |
| Calendar Agent | Evaluates availability and proposes suitable meeting slots. |
| Email Response Agent | Creates contextual email drafts for investor communication. |
| Action Agent | Prepares and processes approved integration actions. |

### Agent Execution Pipeline

```mermaid
%%{init: {"theme":"base","themeVariables":{"primaryColor":"#102C54","primaryTextColor":"#FFFFFF","primaryBorderColor":"#328BFF","lineColor":"#328BFF","secondaryColor":"#123E78","tertiaryColor":"#071A35","fontFamily":"Inter, Arial"}}}%%
flowchart TD
    A["INCOMING STARTUP PITCH"] --> B["EMAIL CLASSIFIER"]
    B --> C["DOCUMENT AGENT"]
    C --> D["RESEARCH AGENT"]
    D --> E["VERIFICATION AGENT"]
    E --> F["ANALYSIS AGENT"]
    F --> G["INVESTMENT INTELLIGENCE BRIEF"]

    G --> H["INVESTOR REVIEW"]
    H --> I["CALENDAR AGENT"]
    H --> J["EMAIL RESPONSE AGENT"]
    I --> K["ACTION AGENT"]
    J --> K

    K --> L{"HUMAN APPROVAL"}
    L -->|Approved| M["EXTERNAL INTEGRATIONS"]
    L -->|Rejected| N["NO EXTERNAL ACTION"]

    M --> O["AUDIT LOG"]

    classDef main fill:#102C54,stroke:#328BFF,color:#FFFFFF,stroke-width:2px;
    classDef decision fill:#123E78,stroke:#38BDF8,color:#FFFFFF,
