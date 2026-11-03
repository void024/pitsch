"""Test environment: no network, no real keys. Must run before `app` is imported."""

import os

os.environ.setdefault("PITSCH_MODE", "test")
