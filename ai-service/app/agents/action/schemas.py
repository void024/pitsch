from datetime import datetime
from enum import Enum

from pydantic import Field

from app.schemas.common import CamelModel


class WorkflowEvent(str, Enum):
    PITCH_DETECTED = "PITCH_DETECTED"
    FOLLOW_UP_DETECTED = "FOLLOW_UP_DETECTED"
    NOT_PITCH = "NOT_PITCH"
    PROCESSING_STARTED = "PROCESSING_STARTED"
    BRIEF_READY = "BRIEF_READY"
    MEETING_APPROVED = "MEETING_APPROVED"      # investor picked a slot
    EMAIL_APPROVED = "EMAIL_APPROVED"          # investor clicked Send on a draft
    WORKFLOW_COMPLETED = "WORKFLOW_COMPLETED"
    WORKFLOW_STOPPED = "WORKFLOW_STOPPED"


class ActionType(str, Enum):
    GMAIL_ADD_LABELS = "GMAIL_ADD_LABELS"
    GMAIL_REMOVE_LABELS = "GMAIL_REMOVE_LABELS"
    GMAIL_SEND_EMAIL = "GMAIL_SEND_EMAIL"
    CALENDAR_CREATE_EVENT = "CALENDAR_CREATE_EVENT"
    SHEETS_UPSERT_ROW = "SHEETS_UPSERT_ROW"


class Approval(CamelModel):
    approved_by: str
    approved_at: datetime


class PitchSummary(CamelModel):
    pitch_id: str
    company_name: str | None = None
    sector: str | None = None
    stage: str | None = None
    founder_name: str | None = None
    founder_email: str | None = None
    amount_requested: str | None = None
    website: str | None = None
    brief_url: str | None = None
    claim_status_summary: dict[str, int] = Field(default_factory=dict)   # from the Analysis Agent
    open_question_count: int | None = None


class MeetingRequest(CamelModel):
    start: datetime
    end: datetime
    timezone: str = "Asia/Kolkata"
    title: str | None = None
    description: str | None = None
    attendee_emails: list[str] = Field(default_factory=list)
    add_video_conference: bool = True


class EmailToSend(CamelModel):
    recipient: str
    subject: str
    body: str
    thread_id: str | None = None
    in_reply_to_message_id: str | None = None


class ActionInput(CamelModel):
    workflow_id: str
    event: WorkflowEvent
    email_id: str | None = None        # Gmail message ID the labels apply to
    thread_id: str | None = None
    pitch: PitchSummary | None = None
    meeting: MeetingRequest | None = None
    email: EmailToSend | None = None
    approval: Approval | None = None   # required for actions that leave the system


class PlannedAction(CamelModel):
    action_id: str                     # stable hash: same workflow+event+action -> same ID (idempotency)
    type: ActionType
    target: str                        # Gmail message/thread ID, "calendar:primary", "sheet:pipeline"
    payload: dict
    requires_approval: bool
    approved: bool                     # backend must NOT execute if requires_approval and not approved
    depends_on: list[str] = Field(default_factory=list)   # run only after these actions succeed
    reason: str


class ActionOutput(CamelModel):
    workflow_id: str
    event: WorkflowEvent
    actions: list[PlannedAction]
    blocked_action_ids: list[str]      # need approval that wasn't provided
    warnings: list[str]
