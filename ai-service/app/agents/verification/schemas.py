from datetime import datetime
from enum import Enum

from pydantic import BaseModel, ConfigDict, Field, field_validator

from app.core.guardrails import reject_recommendation
from app.schemas.common import CamelModel


class VerificationStatus(str, Enum):
    VERIFIED = "VERIFIED"                      # independent evidence confirms the claim
    PARTIALLY_VERIFIED = "PARTIALLY_VERIFIED"  # evidence roughly supports it, or only company sources do
    UNVERIFIED = "UNVERIFIED"                  # relevant evidence exists but is inconclusive
    CONTRADICTED = "CONTRADICTED"              # evidence disagrees with the claim
    NOT_FOUND = "NOT_FOUND"                    # no relevant evidence found at all


# ---------- Input: subsets of the Document & Research outputs (extra fields are ignored) ----------

class ClaimToVerify(CamelModel):
    claim_id: str
    text: str
    category: str | None = None
    quote: str | None = None
    quote_verified: bool | None = None


class EvidenceItem(CamelModel):
    evidence_id: str
    statement: str
    excerpt: str
    source_type: str                 # COMPANY | EXTERNAL
    source_title: str | None = None
    source_url: str | None = None
    published_at: datetime | None = None
    possibly_outdated: bool = False
    claim_ids: list[str] = Field(default_factory=list)


class VerificationInput(CamelModel):
    pitch_id: str | None = None
    company_name: str
    claims: list[ClaimToVerify] = Field(max_length=60)
    evidence: list[EvidenceItem] = Field(default_factory=list, max_length=300)


# ---------- LLM output ----------

class _LLMResult(BaseModel):
    model_config = ConfigDict(extra="ignore")
    claim_id: str
    status: VerificationStatus
    supporting_evidence_ids: list[str] = Field(default_factory=list)
    contradicting_evidence_ids: list[str] = Field(default_factory=list)
    finding: str
    confidence: float = Field(default=0.5, ge=0.0, le=1.0)

    @field_validator("finding")
    @classmethod
    def _no_advice(cls, v: str) -> str:
        return reject_recommendation(v.strip())


class VerificationLLMOutput(BaseModel):
    model_config = ConfigDict(extra="ignore")
    results: list[_LLMResult] = Field(default_factory=list)


# ---------- Output ----------

class ClaimVerification(CamelModel):
    claim_id: str
    claim_text: str
    category: str | None = None
    status: VerificationStatus
    independently_verified: bool          # at least one EXTERNAL source supports it
    supporting_evidence_ids: list[str]
    contradicting_evidence_ids: list[str]
    finding: str                          # what the evidence shows, in plain words
    confidence: float
    evidence_outdated: bool               # all supporting evidence is older than the freshness window
    notes: list[str]                      # adjustments code made, and why


class VerificationOutput(CamelModel):
    pitch_id: str | None = None
    results: list[ClaimVerification]
    summary: dict[str, int]               # count per status
    needs_human_review: bool
    review_reasons: list[str]
    warnings: list[str]
