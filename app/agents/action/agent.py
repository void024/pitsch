"""Action Agent — deterministic, no LLM.

Turns a workflow event into concrete, ready-to-execute payloads for Gmail, Google Calendar and
Google Sheets. It does not call those APIs: the Spring Boot backend executes the actions, which
keeps credentials and side effects in one place.

Approval policy (from the project's human-in-the-loop design):
  autonomous      -> labels, internal pipeline sheet
  needs approval  -> sending email, creating calendar events (anything the founder sees)
  never           -> investment decisions (no such action exists here)
"""

import hashlib
from datetime import datetime, timezone

from app.agents.action.schemas import (
    ActionInput,
    ActionOutput,
    ActionType as T,
    PlannedAction,
    WorkflowEvent as E,
)
from app.agents.base import execute
from app.core.errors import AgentException, ErrorCode
from app.core.llm import CallStats
from app.schemas.common import AgentRequest, AgentResult

AGENT_NAME = "ACTION_AGENT"
APPROVAL_REQUIRED = {T.GMAIL_SEND_EMAIL, T.CALENDAR_CREATE_EVENT}
SHEET_TARGET = "sheet:pipeline"
SHEET_KEY = "Pitch ID"
SHEET_COLUMNS = ["Pitch ID", "Company", "Sector", "Stage", "Founder", "Founder Email", "Ask", "Website",
                 "Status", "Verified", "Partially verified", "Unverified / not found", "Contradicted",
                 "Open questions", "Brief", "Meeting", "Last updated"]


class Labels:
    def __init__(self, prefix: str):
        p = prefix.strip("/")
        self.pitch, self.follow_up = f"{p}/Pitch", f"{p}/Follow-up"
        self.processing, self.needs_review = f"{p}/Processing", f"{p}/Needs Review"
        self.reviewed, self.meeting = f"{p}/Reviewed", f"{p}/Meeting Scheduled"
        self.archived = f"{p}/Archived"


class ActionAgent:
    def __init__(self, *, label_prefix: str = "Pitsch", clock=lambda: datetime.now(timezone.utc)):
        self.labels = Labels(label_prefix)
        self.clock = clock

    def run(self, request: AgentRequest[ActionInput]) -> AgentResult[ActionOutput]:
        return execute(AGENT_NAME, request, ActionOutput, self._plan,
                       log_fields=lambda d: {"actions": len(d.actions)})

    # ------------------------------------------------------------------

    def _plan(self, inp: ActionInput, _: CallStats) -> ActionOutput:
        L = self.labels
        actions: list[PlannedAction] = []
        warnings: list[str] = []

        def add(type_: T, target: str, payload: dict, reason: str, depends_on: list[str] | None = None) -> str:
            aid = hashlib.sha1(f"{inp.workflow_id}|{inp.event.value}|{type_.value}|{target}".encode()).hexdigest()[:16]
            needs = type_ in APPROVAL_REQUIRED
            actions.append(PlannedAction(action_id=aid, type=type_, target=target, payload=payload,
                                         requires_approval=needs, approved=(not needs) or inp.approval is not None,
                                         depends_on=depends_on or [], reason=reason))
            return aid

        def labels(add_: list[str], remove: list[str], reason: str, depends_on=None):
            target = inp.email_id or inp.thread_id
            if not target:
                warnings.append("No emailId/threadId provided; Gmail labels were skipped.")
                return
            kind = "message" if inp.email_id else "thread"
            if remove:
                add(T.GMAIL_REMOVE_LABELS, f"gmail:{kind}:{target}", {"labels": remove, "scope": kind},
                    reason, depends_on)
            if add_:
                add(T.GMAIL_ADD_LABELS, f"gmail:{kind}:{target}", {"labels": add_, "scope": kind,
                                                                  "createIfMissing": True}, reason, depends_on)

        def sheet(status: str, extra: dict | None = None, reason: str = "Keep the deal pipeline sheet current."):
            if not inp.pitch:
                warnings.append("No pitch summary provided; pipeline sheet was not updated.")
                return
            add(T.SHEETS_UPSERT_ROW, SHEET_TARGET,
                {"keyColumn": SHEET_KEY, "columns": SHEET_COLUMNS,
                 "values": self._row(inp, status, extra or {})}, reason)

        ev = inp.event
        if ev == E.NOT_PITCH:
            warnings.append("Not a pitch: no actions.")
        elif ev == E.PITCH_DETECTED:
            labels([L.pitch], [], "Email classified as a new pitch.")
            sheet("NEW")
        elif ev == E.FOLLOW_UP_DETECTED:
            labels([L.pitch, L.follow_up], [], "Founder follow-up on an existing pitch.")
            sheet("FOLLOW_UP")
        elif ev == E.PROCESSING_STARTED:
            labels([L.processing], [], "Investor asked Pitsch to handle this pitch.")
            sheet("PROCESSING")
        elif ev == E.BRIEF_READY:
            labels([L.needs_review], [L.processing], "Research brief is ready for the investor.")
            sheet("AWAITING_REVIEW", self._claims(inp))
        elif ev == E.MEETING_APPROVED:
            self._meeting(inp, add, labels, sheet, warnings)
        elif ev == E.EMAIL_APPROVED:
            self._email(inp, add, warnings)
        elif ev == E.WORKFLOW_COMPLETED:
            labels([L.reviewed], [L.processing, L.needs_review], "Workflow completed.")
            sheet("COMPLETED")
        elif ev == E.WORKFLOW_STOPPED:
            labels([L.archived], [L.processing, L.needs_review], "Investor stopped the workflow.")
            sheet("STOPPED")

        blocked = [a.action_id for a in actions if a.requires_approval and not a.approved]
        if blocked:
            warnings.append("Some actions need investor approval and must not be executed yet.")
        return ActionOutput(workflow_id=inp.workflow_id, event=ev, actions=actions,
                            blocked_action_ids=blocked, warnings=warnings)

    # ---------------- event handlers ----------------

    def _meeting(self, inp: ActionInput, add, labels, sheet, warnings):
        m = inp.meeting
        if not m:
            raise AgentException(ErrorCode.INSUFFICIENT_INPUT, "MEETING_APPROVED needs meeting details.")
        company = inp.pitch.company_name if inp.pitch and inp.pitch.company_name else "startup"
        attendees = list(dict.fromkeys(e.strip().lower() for e in
                                       m.attendee_emails + ([inp.pitch.founder_email] if inp.pitch and inp.pitch.founder_email else [])
                                       if e))
        if not attendees:
            warnings.append("Meeting has no attendees; the founder will not receive an invite.")
        description = m.description or "\n".join(filter(None, [
            f"Pitch meeting with {company}.",
            f"Research brief: {inp.pitch.brief_url}" if inp.pitch and inp.pitch.brief_url else None,
            "Scheduled via Pitsch."]))
        event = {
            "summary": m.title or f"Pitch meeting: {company}",
            "description": description,
            "start": {"dateTime": m.start.isoformat(), "timeZone": m.timezone},
            "end": {"dateTime": m.end.isoformat(), "timeZone": m.timezone},
            "attendees": [{"email": e} for e in attendees],
            "reminders": {"useDefault": True},
        }
        query = {"sendUpdates": "all"}
        if m.add_video_conference:
            event["conferenceData"] = {"createRequest": {"requestId": f"{inp.workflow_id}-meet",
                                                         "conferenceSolutionKey": {"type": "hangoutsMeet"}}}
            query["conferenceDataVersion"] = 1
        cal_id = add(T.CALENDAR_CREATE_EVENT, "calendar:primary", {"event": event, "queryParams": query},
                     "Investor approved a meeting slot; invite goes to the founder.")
        labels([self.labels.meeting], [self.labels.needs_review], "Meeting scheduled.", depends_on=[cal_id])
        sheet("MEETING_SCHEDULED", {"Meeting": m.start.isoformat()})

    def _email(self, inp: ActionInput, add, warnings):
        e = inp.email
        if not e:
            raise AgentException(ErrorCode.INSUFFICIENT_INPUT, "EMAIL_APPROVED needs the email to send.")
        payload = {"to": e.recipient, "subject": e.subject, "body": e.body, "threadId": e.thread_id or inp.thread_id}
        if e.in_reply_to_message_id:
            payload["inReplyTo"] = e.in_reply_to_message_id
            payload["references"] = e.in_reply_to_message_id
        add(T.GMAIL_SEND_EMAIL, f"gmail:send:{e.recipient}", payload, "Investor approved this email draft.")

    # ---------------- helpers ----------------

    def _row(self, inp: ActionInput, status: str, extra: dict) -> dict:
        p = inp.pitch
        values = {"Pitch ID": p.pitch_id, "Company": p.company_name, "Sector": p.sector, "Stage": p.stage,
                  "Founder": p.founder_name, "Founder Email": p.founder_email, "Ask": p.amount_requested,
                  "Website": p.website, "Brief": p.brief_url, "Status": status,
                  "Last updated": self.clock().isoformat(timespec="seconds")}
        values.update(extra)
        return {k: v for k, v in values.items() if v is not None}   # only overwrite what we know

    @staticmethod
    def _claims(inp: ActionInput) -> dict:
        if not inp.pitch:
            return {}
        s = inp.pitch.claim_status_summary
        out = {"Verified": s.get("VERIFIED", 0), "Partially verified": s.get("PARTIALLY_VERIFIED", 0),
               "Unverified / not found": s.get("UNVERIFIED", 0) + s.get("NOT_FOUND", 0),
               "Contradicted": s.get("CONTRADICTED", 0)}
        if inp.pitch.open_question_count is not None:
            out["Open questions"] = inp.pitch.open_question_count
        return out
