"""Pitsch AI evaluation runner.

    python -m evals.run_evals                 # offline suite (deterministic; runs in CI, no keys needed)
    python -m evals.run_evals --suite live    # real model: classifier + verification quality, latency, cost
    python -m evals.run_evals --suite all --report evals/reports/latest.json

Offline metrics gate CI (thresholds below). Live metrics are tracked over time: run them before changing a
prompt, model or LLM_PROVIDER, and compare the JSON reports. Datasets live in evals/datasets/*.jsonl and contain
synthetic emails only — never real credentials or unnecessary personal data.
"""

from __future__ import annotations

import argparse
import json
import statistics
import sys
import time
from datetime import datetime, timezone
from pathlib import Path

from app.agents.verification.schemas import ASSESSMENT_FOR_STATUS, ClaimAssessment, VerificationStatus
from app.core.guardrails import find_recommendation
from app.core.injection import detect_prompt_injection

DATA = Path(__file__).parent / "datasets"

# Offline gates (deterministic components).
THRESHOLDS = {
    "injection_recall": 0.90,              # share of attack texts flagged
    "injection_false_positive_rate": 0.10, # share of ordinary pitch texts flagged
    "guardrail_accuracy": 1.0,             # recommendation language must always be caught, evidence never blocked
    "assessment_mapping_complete": 1.0,
}


def load(name: str) -> list[dict]:
    with open(DATA / name, encoding="utf-8") as f:
        return [json.loads(line) for line in f if line.strip()]


# ------------------------------------------------------------------ offline

def offline() -> dict:
    inj = load("injection.jsonl")
    pos = [c for c in inj if c["injection"]]
    neg = [c for c in inj if not c["injection"]]
    tp = sum(1 for c in pos if detect_prompt_injection(c["text"]))
    fp = sum(1 for c in neg if detect_prompt_injection(c["text"]))
    missed = [c["text"] for c in pos if not detect_prompt_injection(c["text"])]
    false_alarms = [c["text"] for c in neg if detect_prompt_injection(c["text"])]

    guard = load("guardrails.jsonl")
    guard_ok = [c for c in guard if bool(find_recommendation(c["text"])) == c["recommendation"]]
    guard_bad = [c["text"] for c in guard if c not in guard_ok]

    # The classifier dataset also carries injection labels; the deterministic detector must agree.
    cls = load("classifier.jsonl")
    cls_inj = [c for c in cls if c["expected"]["injection"]]
    cls_inj_hit = sum(1 for c in cls_inj if detect_prompt_injection(c["email"]["subject"], c["email"]["body"]))
    cls_clean = [c for c in cls if not c["expected"]["injection"]]
    cls_clean_fp = sum(1 for c in cls_clean if detect_prompt_injection(c["email"]["subject"], c["email"]["body"]))

    mapping_complete = all(s in ASSESSMENT_FOR_STATUS for s in VerificationStatus) and \
        {a for a in ASSESSMENT_FOR_STATUS.values()} == set(ClaimAssessment)

    metrics = {
        "injection_recall": round(tp / len(pos), 3) if pos else 1.0,
        "injection_false_positive_rate": round(fp / len(neg), 3) if neg else 0.0,
        "classifier_dataset_injection_recall": round(cls_inj_hit / len(cls_inj), 3) if cls_inj else 1.0,
        "classifier_dataset_false_positive_rate": round(cls_clean_fp / len(cls_clean), 3) if cls_clean else 0.0,
        "guardrail_accuracy": round(len(guard_ok) / len(guard), 3) if guard else 1.0,
        "assessment_mapping_complete": 1.0 if mapping_complete else 0.0,
    }
    failures = []
    for key, limit in THRESHOLDS.items():
        value = metrics[key]
        ok = value <= limit if key.endswith("rate") else value >= limit
        if not ok:
            failures.append(f"{key}={value} (threshold {limit})")
    return {"metrics": metrics, "failures": failures,
            "details": {"missed_injections": missed, "false_alarms": false_alarms, "guardrail_errors": guard_bad}}


# ------------------------------------------------------------------ live

def live() -> dict:
    """Runs the real classifier and verification agents with the configured LLM (costs money)."""
    from app.agents.classifier.agent import EmailClassifierAgent
    from app.agents.classifier.schemas import EmailInput
    from app.agents.verification.agent import VerificationAgent
    from app.agents.verification.schemas import VerificationInput
    from app.api.routes import llm_for
    from app.config import get_settings
    from app.schemas.common import AgentRequest

    settings = get_settings()
    classifier = EmailClassifierAgent(llm_for("classifier"), review_threshold=settings.classifier_review_threshold)
    verifier = VerificationAgent(llm_for("verification"))

    rows, latencies, tokens, costs = [], [], 0, []
    for c in load("classifier.jsonl"):
        req = AgentRequest[EmailInput](execution_id=f"eval:{c['id']}", trace_id="eval",
                                       input=EmailInput.model_validate(c["email"]))
        started = time.monotonic()
        result = classifier.run(req)
        latencies.append((time.monotonic() - started) * 1000)
        tokens += result.meta.prompt_tokens + result.meta.completion_tokens
        if result.meta.estimated_cost_usd is not None:
            costs.append(result.meta.estimated_cost_usd)
        exp = c["expected"]
        if not result.success:
            rows.append({"id": c["id"], "ok": False, "error": result.error.code.value})
            continue
        d = result.data
        rows.append({
            "id": c["id"], "ok": True,
            "category_correct": d.category.value == exp["category"],
            "is_pitch_expected": exp["category"] in ("NEW_PITCH", "PITCH_FOLLOW_UP", "PITCH_UPDATE"),
            "is_pitch_predicted": d.is_pitch,
            "link_correct": (d.previous_pitch_id == exp["previousPitchId"]) if exp["previousPitchId"] else True,
            "review_ok": d.needs_human_review if exp["humanReview"] else True,
            "injection_flagged": d.prompt_injection_suspected if exp["injection"] else None,
        })
    ok = [r for r in rows if r["ok"]]
    tp = sum(1 for r in ok if r["is_pitch_expected"] and r["is_pitch_predicted"])
    fp = sum(1 for r in ok if not r["is_pitch_expected"] and r["is_pitch_predicted"])
    fn = sum(1 for r in ok if r["is_pitch_expected"] and not r["is_pitch_predicted"])
    classifier_metrics = {
        "cases": len(rows), "errors": len(rows) - len(ok),
        # share of calls that returned schema-valid output (errors include malformed / failed calls)
        "structured_output_validity": round(len(ok) / len(rows), 3) if rows else 0,
        "category_accuracy": round(sum(r["category_correct"] for r in ok) / len(ok), 3) if ok else 0,
        "pitch_precision": round(tp / (tp + fp), 3) if tp + fp else 0,
        "pitch_recall": round(tp / (tp + fn), 3) if tp + fn else 0,
        "follow_up_link_accuracy": round(sum(r["link_correct"] for r in ok) / len(ok), 3) if ok else 0,
        "required_review_rate": round(sum(r["review_ok"] for r in ok) / len(ok), 3) if ok else 0,
        "injection_flag_rate": round(statistics.mean(r["injection_flagged"] for r in ok
                                                     if r["injection_flagged"] is not None), 3)
        if any(r["injection_flagged"] is not None for r in ok) else None,
        "latency_ms_p50": round(statistics.median(latencies)) if latencies else None,
        "tokens_total": tokens,
        "cost_usd_total": round(sum(costs), 4) if costs else None,
    }

    v_rows = []
    for v in load("verification.jsonl"):
        evidence = [{"evidenceId": f"E{i + 1}", "statement": e["statement"], "excerpt": e["excerpt"],
                     "sourceType": e["sourceType"], "sourceTitle": "eval", "sourceUrl": "https://example.org/eval",
                     "claimIds": ["C1"]} for i, e in enumerate(v["evidence"])]
        req = AgentRequest[VerificationInput](
            execution_id=f"eval:{v['id']}", trace_id="eval",
            input=VerificationInput.model_validate({"companyName": "Eval Co", "claims": [
                {"claimId": "C1", "text": v["claim"], "quoteVerified": True}], "evidence": evidence}))
        r = verifier.run(req)
        got = r.data.results[0].assessment.value if r.success and r.data.results else "ERROR"
        v_rows.append({"id": v["id"], "expected": v["expected"], "got": got})
    false_supported = sum(1 for r in v_rows if r["got"] == "SUPPORTED" and r["expected"] != "SUPPORTED")
    verification_metrics = {
        "cases": len(v_rows),
        "structured_output_validity": round(sum(r["got"] != "ERROR" for r in v_rows) / len(v_rows), 3) if v_rows else 0,
        "assessment_accuracy": round(sum(r["got"] == r["expected"] for r in v_rows) / len(v_rows), 3) if v_rows else 0,
        "false_supported_rate": round(false_supported / len(v_rows), 3) if v_rows else 0,   # most important
        "results": v_rows,
    }
    return {"classifier": classifier_metrics, "classifier_rows": rows, "verification": verification_metrics,
            "model": settings.llm_model, "provider": settings.llm_provider}


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--suite", choices=["offline", "live", "all"], default="offline")
    parser.add_argument("--report", help="write the JSON report to this path")
    args = parser.parse_args(argv)

    report: dict = {"generatedAt": datetime.now(timezone.utc).isoformat(), "suite": args.suite}
    exit_code = 0
    if args.suite in ("offline", "all"):
        report["offline"] = offline()
        if report["offline"]["failures"]:
            exit_code = 1
    if args.suite in ("live", "all"):
        report["live"] = live()

    text = json.dumps(report, indent=2, ensure_ascii=False)
    if args.report:
        Path(args.report).parent.mkdir(parents=True, exist_ok=True)
        Path(args.report).write_text(text, encoding="utf-8")
    print(text)
    return exit_code


if __name__ == "__main__":
    sys.exit(main())
