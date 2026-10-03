from app.agents.verification.agent import VerificationAgent
from app.agents.verification.schemas import VerificationInput, VerificationStatus as S
from app.core.guardrails import find_commitment, find_recommendation
from app.schemas.common import AgentRequest
from tests.fakes import FakeLLM

CLAIMS = [
    {"claimId": "C1", "text": "The pitch claims 12,000 farmers onboarded.", "category": "TRACTION", "quoteVerified": True},
    {"claimId": "C2", "text": "The pitch claims market leadership.", "category": "COMPETITION", "quoteVerified": True},
    {"claimId": "C3", "text": "The pitch claims Rs 1.2 Cr ARR.", "category": "FINANCIAL", "quoteVerified": False},
]
EVIDENCE = [
    {"evidenceId": "E1", "statement": "TechCrunch reports about 9,000 farmers.", "excerpt": "serves about 9,000 farmers",
     "sourceType": "EXTERNAL", "sourceTitle": "TechCrunch", "claimIds": ["C1"]},
    {"evidenceId": "E2", "statement": "Company site says 12,000+ farmers.", "excerpt": "over 12,000 farmers",
     "sourceType": "COMPANY", "sourceTitle": "krishiai.in", "claimIds": ["C1"]},
    {"evidenceId": "E3", "statement": "Press release says market leader.", "excerpt": "is the market leader",
     "sourceType": "COMPANY", "claimIds": ["C2"]},
    {"evidenceId": "E4", "statement": "2023 article lists FarmWise as larger.", "excerpt": "FarmWise, the largest",
     "sourceType": "EXTERNAL", "possiblyOutdated": True, "claimIds": ["C2"]},
]


def req(claims=CLAIMS, evidence=EVIDENCE):
    return AgentRequest[VerificationInput](execution_id="wf:VER:1", trace_id="t", input=VerificationInput.model_validate(
        {"companyName": "Krishi AI", "pitchId": 7, "claims": claims, "evidence": evidence}))


def res(cid, status, sup=(), con=(), finding="Neutral finding.", confidence=0.85):
    return {"claim_id": cid, "status": status, "supporting_evidence_ids": list(sup),
            "contradicting_evidence_ids": list(con), "finding": finding, "confidence": confidence}


def run(results, **kw):
    llm = FakeLLM([{"results": results}] if not isinstance(results, list) or not results or isinstance(results[0], dict)
                  else results)
    return VerificationAgent(llm, retry_backoff_seconds=0).run(req(**kw)), llm


def test_statuses_are_enforced_by_code():
    r, _ = run([
        res("C1", "PARTIALLY_VERIFIED", sup=["E1", "E2"]),
        res("C2", "VERIFIED", sup=["E3"]),                       # only company source -> partial
        res("C3", "VERIFIED", sup=["E99"]),                      # invented evidence -> unverified
    ])
    assert r.success, r.error
    by = {x.claim_id: x for x in r.data.results}
    assert by["C1"].status == S.PARTIALLY_VERIFIED and by["C1"].independently_verified
    assert by["C2"].status == S.PARTIALLY_VERIFIED and not by["C2"].independently_verified
    assert any("company-provided" in n for n in by["C2"].notes)
    assert by["C3"].status == S.UNVERIFIED and by["C3"].supporting_evidence_ids == []
    assert r.data.summary["PARTIALLY_VERIFIED"] == 2
    assert any("C3" in w for w in r.data.warnings)               # quote not found in pitch


def test_contradiction_requires_contradicting_evidence():
    r, _ = run([res("C1", "CONTRADICTED"), res("C2", "CONTRADICTED", con=["E4"]), res("C3", "NOT_FOUND", sup=["E1"])])
    by = {x.claim_id: x for x in r.data.results}
    assert by["C1"].status == S.UNVERIFIED
    assert by["C2"].status == S.CONTRADICTED
    assert by["C3"].status == S.UNVERIFIED


def test_verified_with_conflicting_evidence_becomes_partial():
    r, _ = run([res("C1", "VERIFIED", sup=["E1"], con=["E2"]), res("C2", "NOT_FOUND"), res("C3", "NOT_FOUND")])
    assert r.data.results[0].status == S.PARTIALLY_VERIFIED


def test_outdated_support_is_flagged():
    r, _ = run([res("C1", "NOT_FOUND"), res("C2", "PARTIALLY_VERIFIED", sup=["E4"]), res("C3", "NOT_FOUND")])
    assert r.data.results[1].evidence_outdated


def test_missing_claim_in_model_output_flags_review():
    r, _ = run([res("C1", "NOT_FOUND"), res("C2", "NOT_FOUND")])
    by = {x.claim_id: x for x in r.data.results}
    assert by["C3"].status == S.UNVERIFIED and r.data.needs_human_review


def test_no_evidence_means_not_found_without_llm_call():
    llm = FakeLLM([])
    r = VerificationAgent(llm).run(req(evidence=[]))
    assert all(x.status == S.NOT_FOUND for x in r.data.results) and not llm.calls


def test_investment_advice_in_finding_triggers_rewrite():
    bad = {"results": [res("C1", "VERIFIED", sup=["E1"], finding="Strong traction, we recommend investing."),
                       res("C2", "NOT_FOUND"), res("C3", "NOT_FOUND")]}
    good = {"results": [res("C1", "PARTIALLY_VERIFIED", sup=["E1"]), res("C2", "NOT_FOUND"), res("C3", "NOT_FOUND")]}
    llm = FakeLLM([bad, good])
    r = VerificationAgent(llm, retry_backoff_seconds=0).run(req())
    assert r.success and r.meta.attempts == 2
    assert "investment recommendation" in llm.calls[1][1]


def test_low_confidence_flags_review():
    r, _ = run([res("C1", "PARTIALLY_VERIFIED", sup=["E1"], confidence=0.3), res("C2", "NOT_FOUND"), res("C3", "NOT_FOUND")])
    assert r.data.needs_human_review


def test_large_claim_sets_are_batched():
    claims = [{"claimId": f"C{i}", "text": f"claim {i}"} for i in range(1, 21)]
    batch1 = {"results": [res(f"C{i}", "NOT_FOUND") for i in range(1, 16)]}
    batch2 = {"results": [res(f"C{i}", "NOT_FOUND") for i in range(16, 21)]}
    llm = FakeLLM([batch1, batch2])
    r = VerificationAgent(llm).run(req(claims=claims))
    assert len(llm.calls) == 2 and len(r.data.results) == 20


# ---------- guardrails ----------

def test_recommendation_detector():
    for bad in ["We recommend investing in this company.", "You should invest now.", "This is a strong investment opportunity.",
                "Verdict: pass", "Not worth investing.", "I would suggest passing on this deal."]:
        assert find_recommendation(bad), bad
    for ok in ["The company raised $2M from Omnivore.", "Investors in the round include Accel.",
               "Revenue could not be verified.", "The founder previously worked at an investment bank."]:
        assert find_recommendation(ok) is None, ok


def test_commitment_detector():
    assert find_commitment("We will invest $500k.") and find_commitment("Happy to send a term sheet.")
    assert find_commitment("Could you share your latest metrics?") is None
