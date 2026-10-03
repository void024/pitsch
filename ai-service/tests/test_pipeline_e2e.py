"""End-to-end: drive every agent over HTTP in the order the Spring Boot orchestrator would,
feeding each step's JSON output into the next step's input. Catches contract drift between agents.
"""

import json
import os
from datetime import datetime, timezone
from pathlib import Path

from fastapi.testclient import TestClient

from app.agents.action.agent import ActionAgent
from app.agents.analysis.agent import AnalysisAgent
from app.agents.calendar.agent import CalendarAgent
from app.agents.classifier.agent import EmailClassifierAgent
from app.agents.document.agent import DocumentAgent
from app.agents.email_response.agent import EmailResponseAgent
from app.agents.research.agent import ResearchAgent
from app.agents.verification.agent import VerificationAgent
from app.api import routes
from app.main import app
from tests import test_analysis as ta
from tests import test_classifier as tc
from tests import test_document as td
from tests import test_research as tr
from tests.fakes import FakeLLM, FakeSearch

NOW = datetime(2026, 10, 3, tzinfo=timezone.utc)
# Set PITSCH_RECORD_EXAMPLES=docs/examples to save every request/response as example JSON.
RECORD_DIR = os.getenv("PITSCH_RECORD_EXAMPLES")


def routed_llm() -> FakeLLM:
    """One fake LLM for all agents: picks the scripted answer by which agent is asking."""
    doc = td.llm_output(claims=td.llm_output()["claims"] + [
        {"text": "The pitch claims Rs 1.2 Cr ARR.", "category": "FINANCIAL",
         "quote": "Revenue: Rs 1.2 Cr ARR, growing 40% QoQ", "source_ref": "d1:p2"}])
    ext = tr.extraction()
    ext["evidence"][2]["claim_ids"] = ["C1"]
    answers = {
        "Email Classification Agent": tc.llm_output(),
        "Document Agent": doc,
        "research planner": tr.plan("Krishi AI farmers"),
        "evidence extractor": ext,
        "Verification Agent": {"results": [
            {"claim_id": "C1", "status": "PARTIALLY_VERIFIED", "supporting_evidence_ids": ["E2", "E3"],
             "finding": "TechCrunch reports about 9,000 farmers versus 12,000 claimed.", "confidence": 0.85},
            {"claim_id": "C2", "status": "NOT_FOUND", "finding": "No comparative source.", "confidence": 0.9},
            {"claim_id": "C3", "status": "NOT_FOUND", "finding": "No revenue source.", "confidence": 0.9}]},
        "Analysis Agent": ta.analysis_llm(),
        "Email Response Agent": {"subject": "Call?", "body": "Hi Ananya,\n\nThanks for sharing Krishi AI. "
                                                            "Would one of these work?\n\n{{SLOTS}}"},
    }

    def answer(system: str, user: str):
        for key, value in answers.items():
            if key in system:
                return value
        raise AssertionError("unexpected agent prompt")

    return FakeLLM([answer] * 50)


def test_full_pitch_workflow_over_http():
    llm = routed_llm()
    search = FakeSearch(lambda q: [tr.TC, tr.SITE, tr.OLD, tr.OTHER] if "funding" in q.lower() else [])
    overrides = {
        routes.get_classifier: lambda: EmailClassifierAgent(llm, retry_backoff_seconds=0),
        routes.get_document_agent: lambda: DocumentAgent(llm, retry_backoff_seconds=0),
        routes.get_research_agent: lambda: ResearchAgent(llm, search, retry_backoff_seconds=0, clock=lambda: NOW),
        routes.get_verification_agent: lambda: VerificationAgent(llm, retry_backoff_seconds=0),
        routes.get_analysis_agent: lambda: AnalysisAgent(llm, retry_backoff_seconds=0),
        routes.get_calendar_agent: lambda: CalendarAgent(llm, retry_backoff_seconds=0),
        routes.get_email_response_agent: lambda: EmailResponseAgent(llm, retry_backoff_seconds=0),
        routes.get_action_agent: lambda: ActionAgent(clock=lambda: NOW),
    }
    app.dependency_overrides.update(overrides)
    client = TestClient(app)

    def call(path: str, step: int, payload: dict) -> dict:
        request = {"executionId": f"102:{path}:{step}", "traceId": "trace-102", "input": payload}
        resp = client.post(f"/agents/{path}", json=request)
        assert resp.status_code == 200, resp.text
        body = resp.json()
        if RECORD_DIR:
            out = Path(RECORD_DIR)
            out.mkdir(parents=True, exist_ok=True)
            if "contentBase64" in json.dumps(request):   # keep examples readable
                for d in request["input"].get("documents", []):
                    d["contentBase64"] = "<base64 of the PDF file>"
            (out / f"{step:02d}-{path}.json").write_text(json.dumps(
                {"request": {"method": "POST", "path": f"/agents/{path}", "body": request}, "response": body},
                indent=2, ensure_ascii=False), encoding="utf-8")
        assert body["success"], body["error"]
        assert body["meta"]["executionId"] == f"102:{path}:{step}"
        return body["data"]

    try:
        # 1. Classify the incoming email
        email = tc.make_request().input.model_dump(mode="json", by_alias=True)
        cls = call("email-classifier", 1, email)
        assert cls["isPitch"] and cls["recommendedAction"] == "ASK_TO_HANDLE"

        # 2. Label it + add to pipeline (backend asks the user "Handle this pitch?")
        act = call("action", 2, {"workflowId": "102", "event": "PITCH_DETECTED", "emailId": email["emailId"],
                                 "pitch": {"pitchId": "7", "companyName": cls["detectedCompanies"][0]}})
        assert act["actions"][0]["payload"]["labels"] == ["Pitsch/Pitch"]

        # 3. User clicked "Handle Pitch" -> Document Agent
        doc = call("document", 3, td.make_request().input.model_dump(mode="json", by_alias=True))
        assert len(doc["claims"]) == 3 and all(c["quoteVerified"] for c in doc["claims"])

        # 4. Research, built from the document output
        res = call("research", 4, {"pitchId": doc["pitchId"], "companyName": doc["company"]["name"],
                                   "companyDomain": "krishiai.in", "founders": [f["name"] for f in doc["founders"]],
                                   "sector": doc["company"]["sector"], "claims": doc["claims"]})
        assert res["evidence"] and res["ignoredSourceCount"] == 1

        # 5. Verification: claims from the document, evidence from research — passed through verbatim
        ver = call("verification", 5, {"pitchId": doc["pitchId"], "companyName": doc["company"]["name"],
                                       "claims": doc["claims"], "evidence": res["evidence"]})
        assert ver["summary"]["PARTIALLY_VERIFIED"] == 1

        # 6. Analysis: the three stored outputs, unchanged
        ana = call("analysis", 6, {"pitchId": doc["pitchId"], "document": doc, "research": res, "verification": ver})
        assert "## Claims from the pitch" in ana["markdown"]
        assert ana["brief"]["openQuestions"]

        # 7. Brief ready -> labels + sheet counts
        act = call("action", 7, {"workflowId": "102", "event": "BRIEF_READY", "emailId": email["emailId"],
                                 "pitch": {"pitchId": "7", "companyName": ana["companyName"],
                                           "claimStatusSummary": ana["claimStatusSummary"],
                                           "openQuestionCount": len(ana["brief"]["openQuestions"])}})
        assert act["actions"][-1]["payload"]["values"]["Unverified / not found"] == 2

        # 8. User chose PLAN_MEETING -> Calendar Agent
        cal = call("calendar", 8, {"timezone": "Asia/Kolkata", "now": "2026-10-05T08:00:00+05:30",
                                   "busy": [{"start": "2026-10-06T10:00:00+05:30", "end": "2026-10-06T11:00:00+05:30"}]})
        assert len(cal["slots"]) == 3

        # 9. Draft the email proposing those slots
        draft = call("email-response", 9, {
            "pitchId": "7", "purpose": "PROPOSE_MEETING",
            "recipient": {"email": email["sender"]["email"], "name": "Ananya"},
            "investor": {"name": "Josephine", "firm": "Northstar Ventures"},
            "companyName": ana["companyName"], "thread": {"subject": email["subject"], "body": email["body"]},
            "proposedSlots": [{"start": s["start"], "end": s["end"]} for s in cal["slots"]]})
        assert draft["recipient"] == "ananya@krishiai.in" and draft["requiresApproval"]
        assert draft["body"].count("• ") == 3

        # 10. Investor approved slot #1 and the email -> executable actions
        slot = cal["slots"][0]
        approval = {"approvedBy": "user_12", "approvedAt": "2026-10-05T10:00:00+05:30"}
        meet = call("action", 10, {"workflowId": "102", "event": "MEETING_APPROVED", "emailId": email["emailId"],
                                   "pitch": {"pitchId": "7", "companyName": "Krishi AI",
                                             "founderEmail": draft["recipient"]},
                                   "meeting": {"start": slot["start"], "end": slot["end"]}, "approval": approval})
        send = call("action", 11, {"workflowId": "102", "event": "EMAIL_APPROVED", "approval": approval,
                                   "email": {"recipient": draft["recipient"], "subject": draft["subject"],
                                             "body": draft["body"]}})
        assert meet["blockedActionIds"] == [] and send["blockedActionIds"] == []
        assert send["actions"][0]["payload"]["to"] == "ananya@krishiai.in"
    finally:
        app.dependency_overrides.clear()


def test_every_agent_has_an_endpoint_in_openapi():
    paths = TestClient(app).get("/openapi.json").json()["paths"]
    for p in ("email-classifier", "document", "research", "verification", "analysis", "calendar",
              "email-response", "action"):
        assert f"/agents/{p}" in paths
