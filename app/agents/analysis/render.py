"""Deterministic Markdown rendering of the brief (for previews, emails, exports)."""

from app.agents.analysis.schemas import Brief, Finding

PROVENANCE_TAG = {"PITCH": "🟣 Pitch", "COMPANY": "🔵 Company", "EXTERNAL": "🟢 External",
                  "AI_INFERENCE": "🟠 AI inference"}
STATUS_TAG = {"VERIFIED": "✅ Verified", "PARTIALLY_VERIFIED": "🟡 Partially verified",
              "UNVERIFIED": "⚪ Unverified", "CONTRADICTED": "❌ Contradicted",
              "NOT_FOUND": "❔ Not found", "NOT_CHECKED": "— Not checked"}


def _cell(text) -> str:
    return str(text or "").replace("|", "\\|").replace("\n", " ").strip()


def _findings(title: str, items: list[Finding]) -> list[str]:
    if not items:
        return []
    lines = [f"## {title}", ""]
    for f in items:
        cites = f" [{', '.join(f.citations)}]" if f.citations else ""
        lines.append(f"- {f.statement}{cites} — {PROVENANCE_TAG[f.provenance.value]}")
    return lines + [""]


def render_markdown(company: str | None, brief: Brief, disclaimer: str) -> str:
    out = [f"# Research Brief: {company or 'Unknown company'}", "",
           "Legend: " + " · ".join(PROVENANCE_TAG.values()), ""]

    out += _findings("Summary", brief.executive_summary)

    if brief.company_overview:
        out += ["## Company overview", "", "| Field | Information | Source |", "|---|---|---|"]
        out += [f"| {_cell(r.field)} | {_cell(r.value)} | {PROVENANCE_TAG[r.provenance.value]} |"
                for r in brief.company_overview]
        out.append("")

    if brief.claims_matrix:
        out += ["## Claims from the pitch", "", "| # | Pitch claim | Status | What the evidence shows | Sources |",
                "|---|---|---|---|---|"]
        for r in brief.claims_matrix:
            srcs = ", ".join(f"[{e.evidence_id}]({e.source_url})" for e in r.supporting + r.contradicting) or "—"
            status = STATUS_TAG.get(r.status, r.status) + (" ⚠️ dated" if r.evidence_outdated else "")
            out.append(f"| {r.claim_id} | {_cell(r.claim)} | {status} | {_cell(r.finding) or '—'} | {srcs} |")
        out.append("")

    if brief.traction_metrics:
        out += ["## Metrics stated in the pitch", "", "| Metric | Value | Period |", "|---|---|---|"]
        out += [f"| {_cell(m.metric)} | {_cell(m.value)} | {_cell(m.period) or '—'} |" for m in brief.traction_metrics]
        out.append("")

    out += _findings("Market", brief.market)
    out += _findings("Competition", brief.competition)
    if brief.competitors:
        out += ["**Competitors identified:** " + ", ".join(c.name for c in brief.competitors), ""]
    out += _findings("Founders", brief.founders)
    out += _findings("Funding history", brief.funding_history)

    if brief.risks:
        out += ["## Risks and considerations (🟠 AI inference)", ""]
        out += [f"- **{r.category.title()}:** {r.risk}" + (f" [{', '.join(r.citations)}]" if r.citations else "")
                for r in brief.risks]
        out.append("")

    if brief.open_questions:
        out += ["## Open questions for the investor", ""]
        out += [f"{i}. {q.question}" for i, q in enumerate(brief.open_questions, start=1)]
        out.append("")

    if brief.sources:
        out += ["## Sources", ""]
        for s in brief.sources:
            date = s.published_at.date().isoformat() if s.published_at else "date unknown"
            flag = " ⚠️ may be outdated" if s.possibly_outdated else ""
            out.append(f"- **{s.source_id}** [{_cell(s.title)}]({s.url}) — {s.source_type.title()}, {date}{flag}")
        out.append("")

    out += ["---", f"_{disclaimer}_", ""]
    return "\n".join(out)
