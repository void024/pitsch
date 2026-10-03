from functools import lru_cache

from fastapi import APIRouter, Depends

from app.agents.classifier.agent import EmailClassifierAgent
from app.agents.classifier.schemas import ClassifierOutput, EmailInput
from app.config import get_settings
from app.core.llm import OpenAICompatibleClient
from app.schemas.common import AgentRequest, AgentResult

router = APIRouter(prefix="/agents", tags=["agents"])


@lru_cache
def get_classifier() -> EmailClassifierAgent:
    s = get_settings()
    llm = OpenAICompatibleClient(
        api_key=s.llm_api_key,
        model=s.llm_model,
        base_url=s.llm_base_url,
        timeout=s.llm_timeout_seconds,
        temperature=s.llm_temperature,
        json_mode=s.llm_json_mode,
    )
    return EmailClassifierAgent(
        llm,
        review_threshold=s.classifier_review_threshold,
        max_body_chars=s.classifier_max_body_chars,
        max_retries=s.llm_max_retries,
    )


# Handled agent failures return HTTP 200 with success=false; Spring Boot checks
# `success` and uses `error.retryable` to decide whether to retry the step.
@router.post("/email-classifier", response_model=AgentResult[ClassifierOutput])
def classify_email(
    request: AgentRequest[EmailInput],
    agent: EmailClassifierAgent = Depends(get_classifier),
) -> AgentResult[ClassifierOutput]:
    return agent.run(request)
