"""Run the research half of Pitsch on a real pitch, with your real LLM and search keys.

Document -> Research -> Verification -> Analysis, then writes the brief.

Usage (from the repo root, with .env filled in):
    python -m scripts.demo_pitch --deck path/to/deck.pdf --domain company.com
    python -m scripts.demo_pitch --email-file pitch.txt
    python -m scripts.demo_pitch                      # built-in sample (fictional company)

Outputs go to demo_output/ (ignored by git): brief.md plus every agent's JSON.
Tip: a real, public deck makes the best demo, because research can find real evidence.
"""

import argparse
import base64
import json
import mimetypes
import sys
import time
from pathlib import Path

from app.agents.analysis.schemas import AnalysisInput
from app.agents.document.schemas import DocumentAgentInput
from app.agents.research.schemas import ResearchInput
from app.agents.verification.schemas import VerificationInput
from app.api import routes
from app.config import get_settings
from app.core.logging import configure_logging
from app.schemas.common import AgentRequest

SAMPLE_EMAIL = """Subject: Krishi AI - raising our $2M seed

Hi,

I'm Ananya, co-founder & CEO of Krishi AI (www.krishiai.in). We give smallholder farmers AI crop
advisory over WhatsApp in 6 Indian languages.

Traction: 12,000 farmers onboarded across Karnataka, Maharashtra and Telangana; Rs 1.2 Cr ARR,
growing 40% quarter on quarter. We are the market leader in AI agri-advisory for smallholders.
Our CTO Rahul previously led ML at a large agritech company.

We're raising a $2M seed on a SAFE to expand to 3 more states. Would love 30 minutes of your time.

Best,
Ananya Rao
"""


def step(name: str, agent, model, payload: dict, out: Path, n: int):
    started = time.monotonic()
    print(f"[{n}] {name:<13}", end="", flush=True)
    result = agent.run(AgentRequest[model](execution_id=f"demo:{name}:{n}", trace_id="demo",
                                           input=model.model_validate(payload)))
    secs = time.monotonic() - started
    (out / f"{n:02d}-{name}.json").write_text(result.model_dump_json(by_alias=True, indent=2), encoding="utf-8")
    m = result.meta
    if not result.success:
        print(f" FAILED in {secs:.1f}s: {result.error.code.value} - {result.error.message}")
        sys.exit(1)
    print(f" ok  {secs:5.1f}s  llm_calls={m.attempts}  tokens={m.prompt_tokens}+{m.completion_tokens}")
    data = result.data
    for reason in getattr(data, "review_reasons", []):
        print(f"      review: {reason}")
    return data, m


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--deck", help="PDF, PPTX or TXT pitch deck")
    parser.add_argument("--email-file", help="text file with the pitch email (subject on first line optional)")
    parser.add_argument("--domain", help="company website domain, e.g. krishiai.in (improves research)")
    parser.add_argument("--out", default="demo_output")
    args = parser.parse_args()

    configure_logging("WARNING")
    s = get_settings()
    out = Path(args.out)
    out.mkdir(exist_ok=True)
    print(f"Model(s): {', '.join(sorted({s.model_for(a) for a in ('document', 'research', 'verification', 'analysis')}))}"
          f" | search: {routes.get_search().name}\n")

    email = Path(args.email_file).read_text(encoding="utf-8") if args.email_file else ("" if args.deck else SAMPLE_EMAIL)
    subject, _, body = email.partition("\n") if email.lower().startswith("subject:") else ("", "", email)
    docs = []
    if args.deck:
        path = Path(args.deck)
        docs.append({"documentId": "deck", "filename": path.name,
                     "mimeType": mimetypes.guess_type(path.name)[0] or "application/octet-stream",
                     "contentBase64": base64.b64encode(path.read_bytes()).decode()})

    totals = [0, 0]
    doc, m = step("document", routes.get_document_agent(), DocumentAgentInput,
                  {"pitchId": "demo", "emailSubject": subject.removeprefix("Subject:").strip(),
                   "emailBody": body.strip(), "documents": docs}, out, 1)
    totals[0] += m.prompt_tokens; totals[1] += m.completion_tokens
    print(f"      company={doc.company.name!r}, {len(doc.claims)} claims "
          f"({sum(c.quote_verified for c in doc.claims)} quotes verified)")

    res, m = step("research", routes.get_research_agent(), ResearchInput, {
        "pitchId": "demo", "companyName": doc.company.name or "Unknown", "companyDomain": args.domain or doc.company.website,
        "founders": [f.name for f in doc.founders], "sector": doc.company.sector, "location": doc.company.location,
        "oneLiner": doc.company.one_liner, "claims": [c.model_dump(by_alias=True) for c in doc.claims]}, out, 2)
    totals[0] += m.prompt_tokens; totals[1] += m.completion_tokens
    print(f"      {len(res.queries_run)} searches, {len(res.sources)} sources, {len(res.evidence)} evidence items, "
          f"{res.dropped_evidence_count} dropped (excerpt not in source)")

    ver, m = step("verification", routes.get_verification_agent(), VerificationInput, {
        "pitchId": "demo", "companyName": doc.company.name or "Unknown",
        "claims": [c.model_dump(by_alias=True) for c in doc.claims],
        "evidence": [e.model_dump(by_alias=True, mode="json") for e in res.evidence]}, out, 3)
    totals[0] += m.prompt_tokens; totals[1] += m.completion_tokens
    print(f"      {ver.summary}")

    ana, m = step("analysis", routes.get_analysis_agent(), AnalysisInput, {
        "pitchId": "demo", "document": doc.model_dump(by_alias=True, mode="json"),
        "research": res.model_dump(by_alias=True, mode="json"),
        "verification": ver.model_dump(by_alias=True, mode="json")}, out, 4)
    totals[0] += m.prompt_tokens; totals[1] += m.completion_tokens

    (out / "brief.md").write_text(ana.markdown, encoding="utf-8")
    print(f"\nTotal tokens: {totals[0]} in + {totals[1]} out")
    print(f"Brief written to {out / 'brief.md'}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
