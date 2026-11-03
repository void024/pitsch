"""Produce real upstream outputs by running the actual agents with scripted LLM/search.

Used by the Analysis tests and the end-to-end pipeline test, so those tests consume exactly
what the earlier agents emit (catching any contract drift between agents).
"""

from datetime import datetime, timezone

from app.agents.document.agent import DocumentAgent
from app.agents.document.schemas import DocumentAgentInput, DocumentOutput
from app.agents.research.agent import ResearchAgent
from app.agents.research.schemas import ResearchInput, ResearchOutput
from app.agents.verification.agent import VerificationAgent
from app.agents.verification.schemas import VerificationInput, VerificationOutput
from app.schemas.common import AgentRequest
from tests import test_document as td
from tests import test_research as tr
from tests.fakes import FakeLLM, FakeSearch

NOW = datetime(2026, 10, 3, tzinfo=timezone.utc)


def req(model, payload, execution_id="wf:1"):
    return AgentRequest[model](execution_id=execution_id, trace_id="trace", input=model.model_validate(payload))


def document_output() -> DocumentOutput:
    llm_out = td.llm_output(claims=td.llm_output()["claims"] + [
        {"text": "The pitch claims Rs 1.2 Cr ARR.", "category": "FINANCIAL",
         "quote": "Revenue: Rs 1.2 Cr ARR, growing 40% QoQ", "source_ref": "d1:p2"}])
    r = DocumentAgent(FakeLLM([llm_out]), retry_backoff_seconds=0).run(td.make_request())
    assert r.success, r.error
    return r.data


def research_input_from(doc: DocumentOutput) -> dict:
    """What the backend would send the Research Agent, built from the Document Agent output."""
    return {"pitchId": doc.pitch_id, "companyName": doc.company.name, "companyDomain": "krishiai.in",
            "founders": [f.name for f in doc.founders], "sector": doc.company.sector,
            "claims": [c.model_dump(by_alias=True) for c in doc.claims]}


def research_output(doc: DocumentOutput) -> ResearchOutput:
    search = FakeSearch(lambda q: [tr.TC, tr.SITE, tr.OLD, tr.OTHER] if "funding" in q.lower() else [])
    ext = tr.extraction()
    ext["evidence"][2]["claim_ids"] = ["C1"]
    agent = ResearchAgent(FakeLLM([tr.plan("Krishi AI farmers"), ext]), search, retry_backoff_seconds=0,
                          clock=lambda: NOW)
    r = agent.run(req(ResearchInput, research_input_from(doc)))
    assert r.success, r.error
    return r.data


def verification_output(doc: DocumentOutput, research: ResearchOutput) -> VerificationOutput:
    payload = {"pitchId": doc.pitch_id, "companyName": doc.company.name,
               "claims": [c.model_dump(by_alias=True) for c in doc.claims],
               "evidence": [e.model_dump(by_alias=True, mode="json") for e in research.evidence]}
    llm = FakeLLM([{"results": [
        {"claim_id": "C1", "status": "PARTIALLY_VERIFIED", "supporting_evidence_ids": ["E2", "E3"],
         "finding": "TechCrunch reports about 9,000 farmers versus 12,000 claimed.", "confidence": 0.85},
        {"claim_id": "C2", "status": "NOT_FOUND", "finding": "No source compares market share.", "confidence": 0.9},
        {"claim_id": "C3", "status": "NOT_FOUND", "finding": "No source reports revenue.", "confidence": 0.9},
    ]}])
    r = VerificationAgent(llm, retry_backoff_seconds=0).run(req(VerificationInput, payload))
    assert r.success, r.error
    return r.data
