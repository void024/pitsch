from datetime import datetime, time
from enum import Enum
from zoneinfo import ZoneInfo, ZoneInfoNotFoundError

from pydantic import BaseModel, ConfigDict, Field, field_validator, model_validator

from app.schemas.common import CamelModel


class Weekday(str, Enum):
    MON = "MON"
    TUE = "TUE"
    WED = "WED"
    THU = "THU"
    FRI = "FRI"
    SAT = "SAT"
    SUN = "SUN"


WEEKDAY_INDEX = {d: i for i, d in enumerate(Weekday)}


class Priority(str, Enum):
    HIGH = "HIGH"      # prefer the earliest good slot
    NORMAL = "NORMAL"
    LOW = "LOW"        # no preference for earliness


class AvailabilitySource(str, Enum):
    EXPLICIT = "EXPLICIT"                    # backend passed founder windows
    PARSED_FROM_TEXT = "PARSED_FROM_TEXT"    # LLM read them from the founder's email
    NONE = "NONE"


def _check_tz(v: str | None) -> str | None:
    if v is None:
        return v
    try:
        ZoneInfo(v)
    except (ZoneInfoNotFoundError, ValueError) as exc:
        raise ValueError(f"unknown IANA time zone '{v}' (e.g. 'Asia/Kolkata')") from exc
    return v


class Interval(CamelModel):
    start: datetime
    end: datetime

    @model_validator(mode="after")
    def _ordered(self):
        if self.end <= self.start:
            raise ValueError("end must be after start")
        return self


class TimeWindow(CamelModel):
    """A daily clock-time window in the investor's time zone, e.g. 14:00–17:00."""
    start: time
    end: time

    @model_validator(mode="after")
    def _ordered(self):
        if self.end <= self.start:
            raise ValueError("end must be after start")
        return self


class WorkingHours(CamelModel):
    start: time = time(9, 30)
    end: time = time(18, 30)
    days: list[Weekday] = Field(default_factory=lambda: [Weekday.MON, Weekday.TUE, Weekday.WED,
                                                         Weekday.THU, Weekday.FRI])


class CalendarInput(CamelModel):
    pitch_id: str | None = None
    timezone: str = "Asia/Kolkata"                       # investor's IANA time zone
    search_start: datetime | None = None                 # default: now + min notice
    search_end: datetime | None = None                   # default: search start + 7 days
    duration_minutes: int = Field(default=30, ge=15, le=240)
    buffer_minutes: int = Field(default=15, ge=0, le=120)
    min_notice_hours: float = Field(default=12, ge=0, le=168)
    granularity_minutes: int = Field(default=30, ge=5, le=60)
    working_hours: WorkingHours = Field(default_factory=WorkingHours)
    busy: list[Interval] = Field(default_factory=list, max_length=500)   # from Google Calendar free/busy
    max_meetings_per_day: int | None = Field(default=6, ge=1)
    preferred_windows: list[TimeWindow] = Field(default_factory=list)
    lunch: TimeWindow | None = Field(default_factory=lambda: TimeWindow(start=time(13, 0), end=time(14, 0)))
    avoid_back_to_back: bool = True
    priority: Priority = Priority.NORMAL
    founder_timezone: str | None = None
    founder_availability: list[Interval] = Field(default_factory=list)
    founder_availability_text: str | None = Field(default=None, max_length=4000)
    reference_time: datetime | None = None               # when the founder wrote the text ("next Tuesday")
    now: datetime | None = None                          # override the clock (tests / backend clock)
    max_suggestions: int = Field(default=3, ge=1, le=10)

    @field_validator("timezone", "founder_timezone")
    @classmethod
    def _valid_tz(cls, v: str | None) -> str | None:
        return _check_tz(v)


# ---------- LLM output (only for parsing founder availability text) ----------

class _LLMWindow(BaseModel):
    model_config = ConfigDict(extra="ignore")
    start: datetime
    end: datetime


class AvailabilityLLMOutput(BaseModel):
    model_config = ConfigDict(extra="ignore")
    windows: list[_LLMWindow] = Field(default_factory=list)
    founder_timezone_mentioned: str | None = None
    notes: str | None = None


# ---------- Output ----------

class SlotSuggestion(CamelModel):
    rank: int
    start: datetime                  # timezone-aware, in the investor's time zone
    end: datetime
    founder_local_start: datetime | None = None
    score: float                     # 0-100, higher is better
    reasons: list[str]               # human-readable explanation of the ranking


class CalendarOutput(CamelModel):
    pitch_id: str | None = None
    timezone: str
    duration_minutes: int
    slots: list[SlotSuggestion]
    founder_availability_used: list[Interval]
    founder_availability_source: AvailabilitySource
    candidates_considered: int
    constraints_applied: list[str]
    requires_approval: bool = True   # the meeting is only created after the investor picks a slot
    needs_human_review: bool
    review_reasons: list[str]
    warnings: list[str]
