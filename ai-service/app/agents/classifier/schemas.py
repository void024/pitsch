from datetime import datetime
from enum import Enum

from pydantic import BaseModel, ConfigDict, Field, field_validator

from app.schemas.common import CamelModel


class EmailCategory(str, Enum):
    NEW_PITCH = "NEW_PITCH"
    PITCH_FOLLOW_UP = "PITCH_FOLLOW_UP"   # continues an existing pitch conversation
    PITCH_UPDATE = "PITCH_UPDATE"         # existing pitch + new/revised materials
    NOT_PITCH = "NOT_PITCH"
    AMBIGUOUS = "AMBIGUOUS"


class NotPitchType(str, Enum):
    NEWSLETTER = "NEWSLETTER"
    SPAM = "SPAM"
    RECRUITER = "RECRUITER"
    VENDOR = "VENDOR"
    OTHER = "OTHER"


class RecommendedAction(str, Enum):
    ASK_TO_HANDLE = "ASK_TO_HANDLE"
    COMPLETE_WORKFLOW = "COMPLETE_WORKFLOW"
    STOP = "STOP"
    PLAN_MEETING = "PLAN_MEETING"            # suggestion only — the analyst triggers the Calendar Agent
    PLAN_EMAIL_RESPONSE = "PLAN_EMAIL_RESPONSE"


# ---------- Input (sent by Spring Boot) ----------

class Sender(CamelModel):
    email: str = Field(min_length=3, max_length=320)
    name: str | None = None

    @field_validator("email")
    @classmethod
    def _lower(cls, v: str) -> str:
        return v.strip().lower()


class Attachment(CamelModel):
    filename: str
    mime_type: str
    size_bytes: int = Field(ge=0)
    readable: bool = True  # set by Spring Boot after trying to open the file


class PitchCandidate(CamelModel):
    """Existing pitches Spring Boot thinks this email might belong to (recent, same sender/domain, same thread)."""
    pitch_id: str
    company_name: str
    company_domain: str | None = None
    founder_emails: list[str] = Field(default_factory=list)
    thread_ids: list[str] = Field(default_factory=list)
    last_activity_at: datetime | None = None


class EmailInput(CamelModel):
    email_id: str = Field(min_length=1)
    thread_id: str | None = None
    in_reply_to: str | None = None
    references: list[str] = Field(default_factory=list)
    sender: Sender
    to: list[str] = Field(default_factory=list)
    cc: list[str] = Field(default_factory=list)
    subject: str = ""
    body: str = ""
    received_at: datetime
    attachments: list[Attachment] = Field(default_factory=list)
    candidate_pitches: list[PitchCandidate] = Field(default_factory=list, max_length=20)


# ---------- Deterministic match signals ----------

class MatchSignal(CamelModel):
    pitch_id: str
    signals: list[str]
    score: float


# ---------- What the LLM must return (snake_case, matches the prompt) ----------

class ClassifierLLMOutput(BaseModel):
    # Models often echo numeric-looking IDs as numbers (42 instead of "42"); accept both.
    model_config = ConfigDict(extra="ignore", coerce_numbers_to_str=True)

    category: EmailCategory
    not_pitch_type: NotPitchType | None = None
    previous_pitch_id: str | None = None
    detected_companies: list[str] = Field(default_factory=list)
    is_forwarded: bool = False
    original_sender_email: str | None = None
    meeting_requested: bool = False
    workflow_closed: bool = False
    confidence: float = Field(ge=0.0, le=1.0)
    reason: str

    @field_validator("reason")
    @classmethod
    def _trim_reason(cls, v: str) -> str:
        return v.strip()[:600]

    @field_validator("detected_companies")
    @classmethod
    def _clean_companies(cls, v: list[str]) -> list[str]:
        seen, out = set(), []
        for name in (n.strip() for n in v):
            if name and name.lower() not in seen:
                seen.add(name.lower())
                out.append(name)
        return out


# ---------- Final agent output (returned to Spring Boot) ----------

class ClassifierOutput(CamelModel):
    category: EmailCategory
    is_pitch: bool          # NEW_PITCH, PITCH_FOLLOW_UP or PITCH_UPDATE
    is_follow_up: bool      # relates to an existing pitch (FOLLOW_UP or UPDATE)
    not_pitch_type: NotPitchType | None
    previous_pitch_id: str | None
    detected_companies: list[str]
    is_forwarded: bool
    original_sender_email: str | None
    meeting_requested: bool
    workflow_closed: bool
    confidence: float
    recommended_action: RecommendedAction
    needs_human_review: bool
    review_reasons: list[str]
    warnings: list[str]
    reason: str
    match_signals: list[MatchSignal]
