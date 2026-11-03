from functools import lru_cache
from typing import Literal

from pydantic import field_validator, model_validator
from pydantic_settings import BaseSettings, SettingsConfigDict


GEMINI_PRESET = {
    "llm_base_url": "https://generativelanguage.googleapis.com/v1beta/openai/",
    # Google strongly recommends the default temperature (1.0) for Gemini 3 models; lower values
    # can cause looping. None = don't send temperature at all.
    "llm_temperature": None,
    # Gemini 3 Flash/Pro default to "high" thinking, which is slow. "low" is plenty for extraction.
    "llm_reasoning_effort": "low",
    "llm_timeout_seconds": 90.0,   # thinking models + long research prompts need more time
}


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=".env", extra="ignore")

    # ---- LLM (any OpenAI-compatible provider) ----
    # LLM_PROVIDER=gemini fills in Gemini's endpoint and recommended settings (see GEMINI_PRESET).
    llm_provider: Literal["gemini", "openai", "other"] = "other"
    llm_api_key: str
    llm_model: str
    llm_base_url: str | None = None
    llm_timeout_seconds: float = 30.0
    llm_temperature: float | None = 0.0  # empty = don't send (provider default)
    llm_reasoning_effort: Literal["none", "minimal", "low", "medium", "high"] | None = None
    llm_json_mode: bool = True
    llm_max_retries: int = 2
    # Upper bound on generated tokens per call (cost and runaway-output control). 0 = provider default.
    llm_max_output_tokens: int = 16000
    # Optional second model used when the primary fails with a provider error (outage, 5xx, 404 model).
    llm_fallback_model: str | None = None

    # Optional per-agent models (empty = use LLM_MODEL), e.g. a lighter model for the classifier.
    classifier_llm_model: str | None = None
    document_llm_model: str | None = None
    research_llm_model: str | None = None
    verification_llm_model: str | None = None
    analysis_llm_model: str | None = None
    email_response_llm_model: str | None = None
    calendar_llm_model: str | None = None

    # ---- Email Classifier ----
    classifier_review_threshold: float = 0.75  # calibrate this on your labelled test set
    classifier_max_body_chars: int = 12000

    # ---- Document Agent ----
    document_max_chars: int = 40000      # total pitch text sent to the model
    document_max_file_mb: float = 15.0

    # ---- Research Agent ----
    search_provider: Literal["tavily", "none"] = "tavily"
    tavily_api_key: str | None = None
    search_timeout_seconds: float = 20.0
    search_results_per_query: int = 5
    research_max_queries: int = 8        # total search budget per research run
    research_max_rounds: int = 2         # 1 planned round + follow-up rounds for gaps
    research_staleness_days: int = 365   # evidence older than this is flagged "possibly outdated"
    research_deadline_seconds: float = 150.0  # wall-clock budget; the backend's read timeout is 240 s

    # ---- Action Agent ----
    gmail_label_prefix: str = "Pitsch"

    @field_validator("llm_base_url", "llm_temperature", "llm_reasoning_effort", "tavily_api_key",
                     "llm_fallback_model",
                     "classifier_llm_model", "document_llm_model", "research_llm_model",
                     "verification_llm_model", "analysis_llm_model", "email_response_llm_model",
                     "calendar_llm_model", mode="before")
    @classmethod
    def _empty_to_none(cls, v):
        return None if v == "" else v

    @model_validator(mode="after")
    def _apply_provider_preset(self):
        if self.llm_provider == "gemini":
            for key, value in GEMINI_PRESET.items():
                if key not in self.model_fields_set:   # explicit .env values always win
                    setattr(self, key, value)
        return self

    def model_for(self, agent: str) -> str:
        """Model for an agent: its override if set, else LLM_MODEL."""
        return getattr(self, f"{agent}_llm_model", None) or self.llm_model


@lru_cache
def get_settings() -> Settings:
    return Settings()


class ServiceSettings(BaseSettings):
    """Service-level settings that do not depend on the LLM configuration (auth, docs, limits)."""

    model_config = SettingsConfigDict(env_file=".env", extra="ignore")

    # production: the internal token is mandatory and the OpenAPI docs are off unless API_DOCS_ENABLED=true.
    pitsch_mode: Literal["production", "development", "demo", "test"] = "production"
    # Shared secret the backend sends in X-Internal-Token. The AI service has no other callers.
    ai_service_token: str | None = None
    api_docs_enabled: bool | None = None   # default: on outside production
    max_request_mb: float = 30.0           # base64 decks are ~1.37x their file size
    # Price list for cost estimates, USD per million tokens: "model=input/output;model2=input/output".
    # Models not listed report no cost (never a guessed one).
    llm_prices: str = ""

    @field_validator("ai_service_token", "api_docs_enabled", mode="before")
    @classmethod
    def _empty_to_none(cls, v):
        return None if v == "" else v

    @model_validator(mode="after")
    def _production_requirements(self):
        if self.pitsch_mode == "production" and (not self.ai_service_token or len(self.ai_service_token) < 32):
            raise ValueError("AI_SERVICE_TOKEN (at least 32 characters) is required when PITSCH_MODE=production")
        return self

    def prices(self) -> dict[str, tuple[float, float]]:
        """Parsed LLM_PRICES: {model: (usd_per_mtok_input, usd_per_mtok_output)}; malformed entries are skipped."""
        out: dict[str, tuple[float, float]] = {}
        for entry in (self.llm_prices or "").split(";"):
            if "=" not in entry or "/" not in entry:
                continue
            model, _, price = entry.partition("=")
            inp, _, outp = price.partition("/")
            try:
                out[model.strip()] = (float(inp), float(outp))
            except ValueError:
                continue
        return out

    @property
    def docs_enabled(self) -> bool:
        return self.api_docs_enabled if self.api_docs_enabled is not None else self.pitsch_mode != "production"


@lru_cache
def get_service_settings() -> ServiceSettings:
    return ServiceSettings()
