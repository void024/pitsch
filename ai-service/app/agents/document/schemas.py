from enum import Enum

from pydantic import BaseModel, ConfigDict, Field, field_validator

from app.schemas.common import CamelModel


class Provenance(str, Enum):
    """Where a piece of information came from — shown to the investor next to every fact."""
    PITCH = "PITCH"                # founder's deck or email (a claim, not a fact)
    COMPANY = "COMPANY"            # company's own website/blog/press page
    EXTERNAL = "EXTERNAL"          # independent third-party source
    AI_INFERENCE = "AI_INFERENCE"  # the model's own synthesis, with no direct source


class ClaimCategory(str, Enum):
    TRACTION = "TRACTION"
    FINANCIAL = "FINANCIAL"
    MARKET = "MARKET"
    COMPETITION = "COMPETITION"
    TEAM = "TEAM"
    PRODUCT = "PRODUCT"
    CUSTOMERS = "CUSTOMERS"
    FUNDING = "FUNDING"
    OTHER = "OTHER"


# ---------- Input (sent by Spring Boot) ----------

class DocumentInput(CamelModel):
    document_id: str = Field(min_length=1)
    filename: str
    mime_type: str
    # Send EITHER text the backend already extracted, OR the raw file as base64.
    text: str | None = None
    content_base64: str | None = None


class DocumentAgentInput(CamelModel):
    pitch_id: str | None = None
    company_name_hint: str | None = None   # e.g. from the classifier's detectedCompanies
    sender_email: str | None = None
    email_subject: str = ""
    email_body: str = ""                   # pitches without a deck are processed from the email alone
    documents: list[DocumentInput] = Field(default_factory=list, max_length=10)


# ---------- What the LLM must return ----------

class _LLMCompany(BaseModel):
    model_config = ConfigDict(extra="ignore", coerce_numbers_to_str=True)
    name: str | None = None
    website: str | None = None
    sector: str | None = None
    sub_sector: str | None = None
    stage: str | None = None
    location: str | None = None
    founded_year: str | None = None
    one_liner: str | None = None


class _LLMFounder(BaseModel):
    model_config = ConfigDict(extra="ignore")
    name: str
    role: str | None = None
    background: str | None = None
    linkedin_url: str | None = None


class _LLMFundraise(BaseModel):
    model_config = ConfigDict(extra="ignore", coerce_numbers_to_str=True)
    amount_requested: str | None = None
    currency: str | None = None
    instrument: str | None = None
    valuation: str | None = None
    use_of_funds: list[str] = Field(default_factory=list)


class _LLMMetric(BaseModel):
    model_config = ConfigDict(extra="ignore", coerce_numbers_to_str=True)
    metric: str
    value: str
    period: str | None = None
    source_ref: str | None = None


class _LLMClaim(BaseModel):
    model_config = ConfigDict(extra="ignore")
    text: str
    category: ClaimCategory = ClaimCategory.OTHER
    quote: str | None = None
    source_ref: str | None = None


class DocumentLLMOutput(BaseModel):
    model_config = ConfigDict(extra="ignore")
    company: _LLMCompany = Field(default_factory=_LLMCompany)
    founders: list[_LLMFounder] = Field(default_factory=list)
    fundraise: _LLMFundraise = Field(default_factory=_LLMFundraise)
    product_summary: str | None = None
    business_model: str | None = None
    target_customers: str | None = None
    traction_metrics: list[_LLMMetric] = Field(default_factory=list)
    claims: list[_LLMClaim] = Field(default_factory=list)
    missing_information: list[str] = Field(default_factory=list)

    @field_validator("claims")
    @classmethod
    def _cap_claims(cls, v):
        return v[:40]


# ---------- Final output (returned to Spring Boot) ----------

class CompanyProfile(CamelModel):
    name: str | None = None
    website: str | None = None
    sector: str | None = None
    sub_sector: str | None = None
    stage: str | None = None
    location: str | None = None
    founded_year: str | None = None
    one_liner: str | None = None


class Founder(CamelModel):
    name: str
    role: str | None = None
    background: str | None = None
    linkedin_url: str | None = None


class Fundraise(CamelModel):
    amount_requested: str | None = None
    currency: str | None = None
    instrument: str | None = None
    valuation: str | None = None
    use_of_funds: list[str] = Field(default_factory=list)


class TractionMetric(CamelModel):
    metric: str
    value: str
    period: str | None = None
    source_ref: str | None = None


class Claim(CamelModel):
    claim_id: str                      # assigned by code: C1, C2, ...
    text: str
    category: ClaimCategory
    quote: str | None = None
    source_ref: str | None = None      # "d1:p3" (document d1, page 3) or "email"
    quote_verified: bool               # code found the quote in the source text
    provenance: Provenance = Provenance.PITCH


class DocumentInfo(CamelModel):
    document_id: str
    ref: str                           # short ref used in sourceRef, e.g. "d1"
    filename: str
    readable: bool
    page_count: int
    chars_extracted: int
    warning: str | None = None


class DocumentOutput(CamelModel):
    pitch_id: str | None = None
    company: CompanyProfile
    founders: list[Founder]
    fundraise: Fundraise
    product_summary: str | None = None
    business_model: str | None = None
    target_customers: str | None = None
    traction_metrics: list[TractionMetric]
    claims: list[Claim]
    links: list[str]                   # URLs that literally appear in the pitch
    missing_information: list[str]     # standard diligence items the pitch doesn't cover
    documents: list[DocumentInfo]
    truncated: bool
    needs_human_review: bool
    review_reasons: list[str]
    warnings: list[str]
