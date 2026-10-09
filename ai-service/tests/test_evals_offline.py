"""The offline evaluation suite is a CI gate: deterministic safety components must meet their thresholds."""

from evals.run_evals import offline


def test_offline_eval_thresholds_hold():
    result = offline()
    assert result["failures"] == [], result
