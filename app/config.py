from functools import lru_cache
from typing import Literal

from pydantic import field_validator
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=".env", extra="ignore")

    # ---- LLM (any OpenAI-compatible provider) ----
    llm_api_key: str
    llm_model: str
    llm_base_url: str | None = None
    llm_timeout_seconds: float = 30.0
    llm_temperature: float | None = 0.0  # some reasoning models reject temperature; set to empty to omit
    llm_json_mode: bool = True
    llm_max_retries: int = 2

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

    # ---- Action Agent ----
    gmail_label_prefix: str = "Pitsch"

    @field_validator("llm_base_url", "llm_temperature", "tavily_api_key", mode="before")
    @classmethod
    def _empty_to_none(cls, v):
        return None if v == "" else v


@lru_cache
def get_settings() -> Settings:
    return Settings()
