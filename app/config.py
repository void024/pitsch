from functools import lru_cache

from pydantic import field_validator
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=".env", extra="ignore")

    llm_api_key: str
    llm_model: str
    llm_base_url: str | None = None
    llm_timeout_seconds: float = 30.0
    llm_temperature: float | None = 0.0  # some reasoning models reject temperature; set to empty to omit
    llm_json_mode: bool = True
    llm_max_retries: int = 2

    classifier_review_threshold: float = 0.75  # calibrate this on your labelled test set
    classifier_max_body_chars: int = 12000

    @field_validator("llm_base_url", "llm_temperature", mode="before")
    @classmethod
    def _empty_to_none(cls, v):
        return None if v == "" else v


@lru_cache
def get_settings() -> Settings:
    return Settings()
