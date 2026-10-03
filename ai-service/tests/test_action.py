from datetime import datetime, timezone

from app.agents.action.agent import ActionAgent
from app.agents.action.schemas import ActionInput, ActionType as T
from app.core.errors import ErrorCode
from app.schemas.common import AgentRequest

PITCH = {"pitchId": "7", "companyName": "Krishi AI", "founderEmail": "Ananya@krishiai.in",
         "briefUrl": "https://app.pitsch.dev/pitches/7",
         "claimStatusSummary": {"PARTIALLY_VERIFIED": 1, "NOT_FOUND": 2}, "openQuestionCount": 5}
APPROVAL = {"approvedBy": "user_12", "approvedAt": "2026-10-05T10:00:00+05:30"}
MEETING = {"start": "2026-10-07T14:00:00+05:30", "end": "2026-10-07T14:30:00+05:30", "timezone": "Asia/Kolkata"}


def run(**p):
    payload = {"workflowId": "102", "emailId": "msg_1", "pitch": PITCH}
    payload.update(p)
    agent = ActionAgent(clock=lambda: datetime(2026, 10, 5, tzinfo=timezone.utc))
    return agent.run(AgentRequest[ActionInput](execution_id="102:ACTION:1", trace_id="t",
                                               input=ActionInput.model_validate(payload)))


def types(r):
    return [a.type for a in r.data.actions]


def test_pitch_detected_labels_and_sheet():
    r = run(event="PITCH_DETECTED")
    assert r.success and types(r) == [T.GMAIL_ADD_LABELS, T.SHEETS_UPSERT_ROW]
    assert r.data.actions[0].payload["labels"] == ["Pitsch/Pitch"]
    row = r.data.actions[1].payload["values"]
    assert row["Status"] == "NEW" and row["Pitch ID"] == "7" and "Sector" not in row   # unknowns not overwritten
    assert all(a.approved and not a.requires_approval for a in r.data.actions)


def test_brief_ready_moves_labels_and_writes_claim_counts():
    r = run(event="BRIEF_READY")
    assert types(r) == [T.GMAIL_REMOVE_LABELS, T.GMAIL_ADD_LABELS, T.SHEETS_UPSERT_ROW]
    row = r.data.actions[2].payload["values"]
    assert row["Unverified / not found"] == 2 and row["Partially verified"] == 1 and row["Open questions"] == 5


def test_meeting_without_approval_is_blocked():
    r = run(event="MEETING_APPROVED", meeting=MEETING)
    cal = next(a for a in r.data.actions if a.type == T.CALENDAR_CREATE_EVENT)
    assert cal.requires_approval and not cal.approved
    assert r.data.blocked_action_ids == [cal.action_id]


def test_meeting_with_approval_builds_google_calendar_event():
    r = run(event="MEETING_APPROVED", meeting=MEETING, approval=APPROVAL)
    cal = next(a for a in r.data.actions if a.type == T.CALENDAR_CREATE_EVENT)
    ev = cal.payload["event"]
    assert cal.approved and r.data.blocked_action_ids == []
    assert ev["summary"] == "Pitch meeting: Krishi AI"
    assert ev["attendees"] == [{"email": "ananya@krishiai.in"}]
    assert ev["start"] == {"dateTime": "2026-10-07T14:00:00+05:30", "timeZone": "Asia/Kolkata"}
    assert "conferenceData" in ev and cal.payload["queryParams"]["conferenceDataVersion"] == 1
    assert "https://app.pitsch.dev/pitches/7" in ev["description"]
    label = next(a for a in r.data.actions if a.type == T.GMAIL_ADD_LABELS)
    assert label.depends_on == [cal.action_id]                       # only label after the event exists


def test_email_send_requires_approval_and_threads_reply():
    email = {"recipient": "ananya@krishiai.in", "subject": "Re: seed", "body": "Hi", "threadId": "th_1",
             "inReplyToMessageId": "<abc@mail>"}
    blocked = run(event="EMAIL_APPROVED", email=email)
    assert blocked.data.blocked_action_ids
    ok = run(event="EMAIL_APPROVED", email=email, approval=APPROVAL)
    send = ok.data.actions[0]
    assert send.type == T.GMAIL_SEND_EMAIL and send.approved
    assert send.payload["threadId"] == "th_1" and send.payload["inReplyTo"] == "<abc@mail>"


def test_action_ids_are_stable_for_idempotent_retries():
    a = [x.action_id for x in run(event="BRIEF_READY").data.actions]
    b = [x.action_id for x in run(event="BRIEF_READY").data.actions]
    assert a == b and len(set(a)) == len(a)


def test_missing_inputs():
    assert run(event="MEETING_APPROVED").error.code == ErrorCode.INSUFFICIENT_INPUT
    assert run(event="EMAIL_APPROVED").error.code == ErrorCode.INSUFFICIENT_INPUT
    r = run(event="PITCH_DETECTED", emailId=None, pitch=None)
    assert r.success and r.data.actions == [] and len(r.data.warnings) == 2


def test_stop_and_complete_and_not_pitch():
    assert run(event="WORKFLOW_STOPPED").data.actions[1].payload["labels"] == ["Pitsch/Archived"]
    assert run(event="WORKFLOW_COMPLETED").data.actions[1].payload["labels"] == ["Pitsch/Reviewed"]
    assert run(event="NOT_PITCH").data.actions == []
