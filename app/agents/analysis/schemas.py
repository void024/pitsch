from datetime import datetime

from pydantic import BaseModel, ConfigDict, Field, field_validator

from app.agents.document.schemas import DocumentOutput, Provenance, TractionMetric
from app.agents.research.schemas import Competitor, ResearchOutput
from app.agents.verification.schemas import VerificationOutput
from app.core.guardrails import reject_recommendation
from app.schemas.common import CamelModel

NOT_CHECKED = "NOT_CHECKED"   # claim status when no verification was run


# ---------- Input: the stored outputs of earlier agents, passed back as-is ----------

class AnalysisInput(CamelModel):
    pitch_id: str | None = None
    document: DocumentOutput
    research: ResearchOutput | None = None
    verification: VerificationOutput | None = None


# ---------- LLM output ----------

class _Item(BaseModel):
    model_config = ConfigDict(extra="ignore")
    statement: str
    citations: list[str] = Field(default_factory=list)

    @field_validator("statement")
    @classmethod
    def _no_advice(cls, v: str) -> str:
        return reject_recommendation(v.strip())


class _Risk(BaseModel):
    model_config = ConfigDict(extra="ignore")
    risk: str
    category: str = "OTHER"
    citations: list[str] = Field(default_factory=list)

    @field_validator("risk")
    @classmethod
    def _no_advice(cls, v: str) -> str:
        return reject_recommendation(v.strip())


class _Question(BaseModel):
    model_config = ConfigDict(extra="ignore")
    question: str
    reason: str | None = None
    citations: list[str] = Field(default_factory=list)

    @field_validator("question", "reason")
    @classmethod
    def _no_advice(cls, v):
        return reject_recommendation(v.strip()) if v else v


class AnalysisLLMOutput(BaseModel):
    model_config = ConfigDict(extra="ignore")
    executive_summary: list[_Item] = Field(default_factory=list)
    market: list[_Item] = Field(default_factory=list)
    competition: list[_Item] = Field(default_factory=list)
    founders: list[_Item] = Field(default_factory=list)
    funding_history: list[_Item] = Field(default_factory=list)
    risks: list[_Risk] = Field(default_factory=list)
    open_questions: list[_Question] = Field(default_factory=list)


# ---------- Output ----------

class OverviewRow(CamelModel):
    field: str
    value: str
    provenance: Provenance
    source: str                       # human-readable, e.g. "Pitch deck (d1:p2)"


class Finding(CamelModel):
    statement: str
    citations: list[str]              # evidence IDs (E..) and/or claim IDs (C..)
    provenance: Provenance            # derived by code from the citations


class EvidenceLink(CamelModel):
    evidence_id: str
    source_title: str
    source_url: str
    published_at: datetime | None = None
    source_type: str


class ClaimRow(CamelModel):
    claim_id: str
    claim: str
    category: str | None = None
    status: str                       # VerificationStatus value or NOT_CHECKED
    independently_verified: bool
    finding: str | None = None
    evidence_outdated: bool = False
    supporting: list[EvidenceLink]
    contradicting: list[EvidenceLink]


class Risk(CamelModel):
    risk: str
    category: str
    citations: list[str]
    provenance: Provenance = Provenance.AI_INFERENCE   # risks are always interpretation


class OpenQuestion(CamelModel):
    question: str
    reason: str | None = None
    citations: list[str]
    origin: str                       # VERIFICATION | MISSING_INFO | RESEARCH_GAP | ANALYSIS


class SourceRef(CamelModel):
    source_id: str
    title: str
    url: str
    source_type: str
    published_at: datetime | None = None
    retrieved_at: datetime | None = None
    possibly_outdated: bool = False


class Brief(CamelModel):
    company_overview: list[OverviewRow]
    executive_summary: list[Finding]
    claims_matrix: list[ClaimRow]
    traction_metrics: list[TractionMetric]
    market: list[Finding]
    competition: list[Finding]
    competitors: list[Competitor]
    founders: list[Finding]
    funding_history: list[Finding]
    risks: list[Risk]
    open_questions: list[OpenQuestion]
    sources: list[SourceRef]


class AnalysisOutput(CamelModel):
    pitch_id: str | None = None
    company_name: str | None = None
    brief: Brief
    markdown: str                     # ready-to-display rendering of the brief
    claim_status_summary: dict[str, int]
    disclaimer: str
    needs_human_review: bool
    review_reasons: list[str]
    warnings: list[str]
