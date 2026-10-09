from app.agents.analysis.agent import AnalysisAgent
from app.agents.analysis.schemas import AnalysisInput
from app.agents.document.schemas import Provenance
from tests.builders import document_output, req, research_output, verification_output
from tests.fakes import FakeLLM

DOC = document_output()
RES = research_output(DOC)
VER = verification_output(DOC, RES)


def analysis_llm(**overrides):
    base = {
        "executive_summary": [
            {"statement": "Krishi AI offers AI crop advisory and is raising a $2M seed on a SAFE.", "citations": ["C1"]},
            {"statement": "TechCrunch reports a $1.5M seed round led by Omnivore.", "citations": ["E1"]},
            {"statement": "The company appears well positioned.", "citations": []},
            {"statement": "Bogus cite.", "citations": ["E999"]},
        ],
        "market": [],
        "competition": [{"statement": "A 2023 article names FarmWise as a competitor.", "citations": ["E3"]}],
        "founders": [],
        "funding_history": [{"statement": "TechCrunch reports a $1.5M seed.", "citations": ["E1"]}],
        "risks": [{"risk": "Customer count could not be independently confirmed.", "category": "traction",
                   "citations": ["C1", "E2"]}],
        "open_questions": [{"question": "What is monthly churn among paying farmers?", "reason": "Not disclosed"}],
    }
    base.update(overrides)
    return base


def payload(**overrides):
    p = {"pitchId": DOC.pitch_id,
         "document": DOC.model_dump(by_alias=True, mode="json"),
         "research": RES.model_dump(by_alias=True, mode="json"),
         "verification": VER.model_dump(by_alias=True, mode="json")}
    p.update(overrides)
    return p


def run(responses, **overrides):
    llm = FakeLLM(responses)
    return AnalysisAgent(llm, retry_backoff_seconds=0).run(req(AnalysisInput, payload(**overrides))), llm


def test_brief_is_built_with_provenance_and_claims_matrix():
    r, llm = run([analysis_llm()])
    assert r.success, r.error
    b = r.data.brief
    prov = [f.provenance for f in b.executive_summary]
    assert prov == [Provenance.PITCH, Provenance.EXTERNAL, Provenance.AI_INFERENCE, Provenance.AI_INFERENCE]
    assert b.executive_summary[3].citations == []                      # E999 removed
    assert {row.claim_id: row.status for row in b.claims_matrix} == {
        "C1": "PARTIALLY_VERIFIED", "C2": "NOT_FOUND", "C3": "NOT_FOUND"}
    assert b.claims_matrix[0].supporting[0].source_url.startswith("https://")
    assert any(row.field == "Raising" and row.value == "$2M" for row in b.company_overview)
    assert r.data.claim_status_summary == {"PARTIALLY_VERIFIED": 1, "NOT_FOUND": 2}
    assert any("labelled AI inference" in w for w in r.data.warnings)


def test_open_questions_combine_verification_gaps_and_model():
    r, _ = run([analysis_llm()])
    origins = [q.origin for q in r.data.brief.open_questions]
    assert origins.count("VERIFICATION") == 2                         # the two NOT_FOUND claims
    assert "MISSING_INFO" in origins and "RESEARCH_GAP" in origins and "ANALYSIS" in origins


def test_only_cited_sources_are_listed():
    r, _ = run([analysis_llm()])
    urls = {s.url for s in r.data.brief.sources}
    assert "https://techcrunch.com/2026/05/krishi-ai-seed" in urls
    assert "https://example.com/krishi-ai-usa" not in urls             # ignored entity never appears


def test_markdown_render_contains_key_sections():
    md = run([analysis_llm()])[0].data.markdown
    for part in ("# Research Brief: Krishi AI", "## Claims from the pitch", "🟡 Partially supported",
                 "## Open questions for the investor", "## Sources", "no investment recommendation"):
        assert part in md, part


def test_recommendation_language_forces_rewrite():
    bad = analysis_llm(executive_summary=[{"statement": "This is a compelling investment opportunity.", "citations": []}])
    r, llm = run([bad, analysis_llm()])
    assert r.success and r.meta.attempts == 2
    assert "recommendation" in llm.calls[1][1]
    assert "compelling investment" not in r.data.markdown


def test_persistent_recommendation_fails_instead_of_leaking():
    bad = analysis_llm(risks=[{"risk": "We recommend passing on this deal.", "category": "OTHER"}])
    r, _ = run([bad, bad, bad])
    assert not r.success and r.error.code.value == "MALFORMED_LLM_OUTPUT"


def test_pitch_only_brief_when_research_and_verification_missing():
    r, _ = run([analysis_llm(executive_summary=[{"statement": "Pitch-only summary.", "citations": ["C1"]}],
                             competition=[], funding_history=[], risks=[])],
               research=None, verification=None)
    assert r.success
    assert all(row.status == "NOT_CHECKED" for row in r.data.brief.claims_matrix)
    assert r.data.brief.sources == []
    assert any("pitch only" in w for w in r.data.warnings)


def test_upstream_review_flags_are_carried_forward():
    doc = DOC.model_copy(update={"needs_human_review": True, "review_reasons": ["Deck unreadable"]})
    r, _ = run([analysis_llm()], document=doc.model_dump(by_alias=True, mode="json"))
    assert r.data.needs_human_review and "Deck unreadable" in r.data.review_reasons[0]
