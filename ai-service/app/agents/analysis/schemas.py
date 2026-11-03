from datetime import datetime

from pydantic import BaseModel, ConfigDict, Field, field_validator

from app.agents.document.schemas import DocumentOutput, Fundraise, Provenance, TractionMetric
from app.agents.research.schemas import Competitor, ResearchOutput
from app.agents.verification.schemas import ASSESSMENT_FOR_STATUS, VerificationOutput, VerificationStatus
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
    problem: list[_Item] = Field(default_factory=list)
    solution: list[_Item] = Field(default_factory=list)
    product: list[_Item] = Field(default_factory=list)
    business_model: list[_Item] = Field(default_factory=list)
    opportunities: list[_Item] = Field(default_factory=list)
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


def assessment_for(status: str) -> str:
    """Product vocabulary (SUPPORTED, PARTIALLY_SUPPORTED, UNSUPPORTED, CONTRADICTED, NOT_FOUND) or NOT_CHECKED."""
    try:
        return ASSESSMENT_FOR_STATUS[VerificationStatus(status)].value
    except ValueError:
        return NOT_CHECKED


class ClaimRow(CamelModel):
    claim_id: str
    claim: str
    category: str | None = None
    status: str                       # VerificationStatus value or NOT_CHECKED
    assessment: str = NOT_CHECKED     # SUPPORTED | PARTIALLY_SUPPORTED | UNSUPPORTED | CONTRADICTED | NOT_FOUND | NOT_CHECKED
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


class AIConfidence(CamelModel):
    """How well the brief is supported by evidence — NOT a view on the company or the investment."""
    level: str                        # LOW | MEDIUM | HIGH
    score: float                      # 0..1, deterministic from the evidence counts below
    reasons: list[str]
    note: str = ("Confidence describes the completeness and evidential support of this brief. It is not an "
                 "assessment of the company and not an investment recommendation.")


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
    problem: list[Finding] = Field(default_factory=list)
    solution: list[Finding] = Field(default_factory=list)
    product: list[Finding] = Field(default_factory=list)
    business_model: list[Finding] = Field(default_factory=list)
    opportunities: list[Finding] = Field(default_factory=list)
    fundraising: Fundraise | None = None          # as stated in the pitch (founder-provided)
    missing_information: list[str] = Field(default_factory=list)
    ai_confidence: AIConfidence | None = None
    generated_at: datetime | None = None          # "last updated"


class AnalysisOutput(CamelModel):
    pitch_id: str | None = None
    company_name: str | None = None
    brief: Brief
    markdown: str                     # ready-to-display rendering of the brief
    claim_status_summary: dict[str, int]
    claim_assessment_summary: dict[str, int] = Field(default_factory=dict)
    disclaimer: str
    needs_human_review: bool
    review_reasons: list[str]
    warnings: list[str]
