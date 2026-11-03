from datetime import datetime
from enum import Enum

from pydantic import BaseModel, ConfigDict, Field, field_validator

from app.agents.calendar.schemas import check_timezone
from app.schemas.common import CamelModel


class EmailPurpose(str, Enum):
    ACKNOWLEDGE = "ACKNOWLEDGE"            # "thanks, we're reviewing"
    REQUEST_INFO = "REQUEST_INFO"          # ask the founder questions
    PROPOSE_MEETING = "PROPOSE_MEETING"    # offer slots from the Calendar Agent
    CONFIRM_MEETING = "CONFIRM_MEETING"    # confirm the slot the investor picked
    DECLINE = "DECLINE"                    # only when the investor chose to decline
    GENERAL_REPLY = "GENERAL_REPLY"        # follow the investor's instructions


class Tone(str, Enum):
    WARM = "WARM"
    FORMAL = "FORMAL"
    BRIEF = "BRIEF"


class Person(CamelModel):
    email: str = Field(min_length=3, max_length=320)
    name: str | None = None

    @field_validator("email")
    @classmethod
    def _lower(cls, v: str) -> str:
        return v.strip().lower()


class Investor(CamelModel):
    name: str
    title: str | None = None
    firm: str | None = None
    email: str | None = None


class ThreadMessage(CamelModel):
    subject: str = ""
    body: str = ""


class SlotOption(CamelModel):
    start: datetime
    end: datetime


class MeetingDetails(CamelModel):
    start: datetime
    end: datetime
    location_or_link: str | None = None


class EmailResponseInput(CamelModel):
    pitch_id: str | None = None
    purpose: EmailPurpose
    recipient: Person
    investor: Investor
    company_name: str | None = None
    thread: ThreadMessage | None = None              # the founder's latest message (untrusted)
    questions: list[str] = Field(default_factory=list, max_length=10)   # chosen by the investor
    proposed_slots: list[SlotOption] = Field(default_factory=list, max_length=6)
    meeting: MeetingDetails | None = None
    timezone: str = "Asia/Kolkata"                   # investor time zone, for formatting times
    founder_timezone: str | None = None
    instructions: str | None = Field(default=None, max_length=2000)   # from the investor (trusted)
    tone: Tone = Tone.WARM

    @field_validator("timezone", "founder_timezone")
    @classmethod
    def _valid_tz(cls, v):
        return check_timezone(v)


# ---------- LLM output ----------

class EmailLLMOutput(BaseModel):
    model_config = ConfigDict(extra="ignore")
    subject: str = ""
    body: str

    @field_validator("body")
    @classmethod
    def _not_empty(cls, v: str) -> str:
        if len(v.strip()) < 20:
            raise ValueError("body is empty or too short")
        return v.strip()


# ---------- Output (recipient/subject/body match the frontend contract) ----------

class EmailResponseOutput(CamelModel):
    pitch_id: str | None = None
    recipient: str
    recipient_name: str | None = None
    subject: str
    body: str
    purpose: EmailPurpose
    requires_approval: bool = True   # never sent without the investor clicking Send
    needs_human_review: bool
    review_reasons: list[str]
    warnings: list[str]
