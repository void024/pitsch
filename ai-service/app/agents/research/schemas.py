from datetime import datetime
from enum import Enum

from pydantic import BaseModel, ConfigDict, Field

from app.agents.document.schemas import Provenance
from app.schemas.common import CamelModel


class ResearchTopic(str, Enum):
    COMPANY = "COMPANY"
    FOUNDERS = "FOUNDERS"
    FUNDING = "FUNDING"
    TRACTION = "TRACTION"
    MARKET = "MARKET"
    COMPETITORS = "COMPETITORS"
    PRODUCT = "PRODUCT"
    NEWS = "NEWS"
    RISK = "RISK"            # legal, regulatory, controversies


class SourceType(str, Enum):
    COMPANY = "COMPANY"      # the company's own site or company-issued press releases
    EXTERNAL = "EXTERNAL"    # independent third party


# ---------- Input ----------

class ClaimRef(CamelModel):
    claim_id: str
    text: str
    category: str | None = None


class ResearchInput(CamelModel):
    pitch_id: str | None = None
    company_name: str = Field(min_length=1, max_length=200)
    company_domain: str | None = None
    founders: list[str] = Field(default_factory=list, max_length=10)
    sector: str | None = None
    location: str | None = None
    one_liner: str | None = None
    claims: list[ClaimRef] = Field(default_factory=list, max_length=40)  # from the Document Agent
    focus_topics: list[ResearchTopic] = Field(default_factory=list)
    max_queries: int | None = Field(default=None, ge=1, le=20)


# ---------- LLM outputs ----------

class _LLMQuery(BaseModel):
    model_config = ConfigDict(extra="ignore")
    query: str
    topic: ResearchTopic = ResearchTopic.COMPANY
    claim_ids: list[str] = Field(default_factory=list)


class ResearchPlanLLMOutput(BaseModel):
    model_config = ConfigDict(extra="ignore")
    queries: list[_LLMQuery] = Field(default_factory=list)


class _LLMEvidence(BaseModel):
    model_config = ConfigDict(extra="ignore")
    source_id: str
    topic: ResearchTopic = ResearchTopic.COMPANY
    statement: str
    excerpt: str
    claim_ids: list[str] = Field(default_factory=list)


class _LLMCompetitor(BaseModel):
    model_config = ConfigDict(extra="ignore")
    name: str
    description: str | None = None
    source_ids: list[str] = Field(default_factory=list)


class EvidenceLLMOutput(BaseModel):
    model_config = ConfigDict(extra="ignore")
    evidence: list[_LLMEvidence] = Field(default_factory=list)
    competitors: list[_LLMCompetitor] = Field(default_factory=list)
    irrelevant_source_ids: list[str] = Field(default_factory=list)
    gaps: list[str] = Field(default_factory=list)
    follow_up_queries: list[_LLMQuery] = Field(default_factory=list)


# ---------- Output ----------

class Source(CamelModel):
    source_id: str
    url: str
    title: str
    domain: str
    source_type: SourceType
    published_at: datetime | None = None
    retrieved_at: datetime
    possibly_outdated: bool
    query: str


class Evidence(CamelModel):
    evidence_id: str                 # E1, E2, ... assigned by code
    source_id: str
    topic: ResearchTopic
    statement: str                   # what the source says, in plain words
    excerpt: str                     # verbatim text from the source (checked by code)
    claim_ids: list[str]             # pitch claims this evidence is relevant to
    source_type: SourceType
    provenance: Provenance           # COMPANY or EXTERNAL
    source_url: str
    source_title: str
    published_at: datetime | None = None
    retrieved_at: datetime
    possibly_outdated: bool


class Competitor(CamelModel):
    name: str
    description: str | None = None
    source_ids: list[str]


class QueryRun(CamelModel):
    query: str
    topic: ResearchTopic
    round: int
    result_count: int
    error: str | None = None


class ResearchOutput(CamelModel):
    pitch_id: str | None = None
    company_name: str
    search_provider: str
    sources: list[Source]
    evidence: list[Evidence]
    competitors: list[Competitor]
    queries_run: list[QueryRun]
    gaps: list[str]                       # what the agent looked for but could not find
    claims_without_evidence: list[str]    # claim IDs with no relevant evidence at all
    dropped_evidence_count: int           # evidence rejected because its excerpt wasn't in the source
    ignored_source_count: int             # sources about a different entity with a similar name
    needs_human_review: bool
    review_reasons: list[str]
    warnings: list[str]
