"""HTTP endpoints — one per agent. Called only by the Spring Boot backend.

Every endpoint takes AgentRequest[<Input>] and returns AgentResult[<Output>] (camelCase JSON).
Handled agent failures return HTTP 200 with success=false; Spring Boot checks `success` and uses
`error.retryable` to decide whether to retry the step. Invalid input returns HTTP 422.
"""

from functools import lru_cache

from fastapi import APIRouter, Depends

from app.agents.action.agent import ActionAgent
from app.agents.action.schemas import ActionInput, ActionOutput
from app.agents.analysis.agent import AnalysisAgent
from app.agents.analysis.schemas import AnalysisInput, AnalysisOutput
from app.agents.calendar.agent import CalendarAgent
from app.agents.calendar.schemas import CalendarInput, CalendarOutput
from app.agents.classifier.agent import EmailClassifierAgent
from app.agents.classifier.schemas import ClassifierOutput, EmailInput
from app.agents.document.agent import DocumentAgent
from app.agents.document.schemas import DocumentAgentInput, DocumentOutput
from app.agents.email_response.agent import EmailResponseAgent
from app.agents.email_response.schemas import EmailResponseInput, EmailResponseOutput
from app.agents.research.agent import ResearchAgent
from app.agents.research.schemas import ResearchInput, ResearchOutput
from app.agents.verification.agent import VerificationAgent
from app.agents.verification.schemas import VerificationInput, VerificationOutput
from app.config import get_settings
from app.core.llm import OpenAICompatibleClient
from app.core.search import NoSearch, SearchProvider, TavilySearch
from app.schemas.common import AgentRequest, AgentResult

router = APIRouter(prefix="/agents", tags=["agents"])


# ---------------- shared dependencies ----------------

@lru_cache
def get_llm() -> OpenAICompatibleClient:
    s = get_settings()
    return OpenAICompatibleClient(api_key=s.llm_api_key, model=s.llm_model, base_url=s.llm_base_url,
                                  timeout=s.llm_timeout_seconds, temperature=s.llm_temperature,
                                  json_mode=s.llm_json_mode)


@lru_cache
def get_search() -> SearchProvider:
    s = get_settings()
    if s.search_provider == "tavily" and s.tavily_api_key:
        return TavilySearch(s.tavily_api_key, timeout=s.search_timeout_seconds)
    return NoSearch()


def _retries() -> dict:
    return {"max_retries": get_settings().llm_max_retries}


@lru_cache
def get_classifier() -> EmailClassifierAgent:
    s = get_settings()
    return EmailClassifierAgent(get_llm(), review_threshold=s.classifier_review_threshold,
                                max_body_chars=s.classifier_max_body_chars, **_retries())


@lru_cache
def get_document_agent() -> DocumentAgent:
    s = get_settings()
    return DocumentAgent(get_llm(), max_chars=s.document_max_chars, max_file_mb=s.document_max_file_mb, **_retries())


@lru_cache
def get_research_agent() -> ResearchAgent:
    s = get_settings()
    return ResearchAgent(get_llm(), get_search(), results_per_query=s.search_results_per_query,
                         max_queries=s.research_max_queries, max_rounds=s.research_max_rounds,
                         staleness_days=s.research_staleness_days, **_retries())


@lru_cache
def get_verification_agent() -> VerificationAgent:
    return VerificationAgent(get_llm(), **_retries())


@lru_cache
def get_analysis_agent() -> AnalysisAgent:
    return AnalysisAgent(get_llm(), **_retries())


@lru_cache
def get_email_response_agent() -> EmailResponseAgent:
    return EmailResponseAgent(get_llm(), **_retries())


@lru_cache
def get_calendar_agent() -> CalendarAgent:
    return CalendarAgent(get_llm(), **_retries())


@lru_cache
def get_action_agent() -> ActionAgent:
    return ActionAgent(label_prefix=get_settings().gmail_label_prefix)


# ---------------- endpoints ----------------

@router.post("/email-classifier", response_model=AgentResult[ClassifierOutput],
             summary="1. Is this email a pitch / follow-up?")
def classify_email(request: AgentRequest[EmailInput],
                   agent: EmailClassifierAgent = Depends(get_classifier)) -> AgentResult[ClassifierOutput]:
    return agent.run(request)


@router.post("/document", response_model=AgentResult[DocumentOutput],
             summary="2. Extract structured pitch data from the deck/email")
def process_document(request: AgentRequest[DocumentAgentInput],
                     agent: DocumentAgent = Depends(get_document_agent)) -> AgentResult[DocumentOutput]:
    return agent.run(request)


@router.post("/research", response_model=AgentResult[ResearchOutput],
             summary="3. Web research with verbatim, sourced evidence")
def research(request: AgentRequest[ResearchInput],
             agent: ResearchAgent = Depends(get_research_agent)) -> AgentResult[ResearchOutput]:
    return agent.run(request)


@router.post("/verification", response_model=AgentResult[VerificationOutput],
             summary="4. Check each pitch claim against the evidence")
def verify(request: AgentRequest[VerificationInput],
           agent: VerificationAgent = Depends(get_verification_agent)) -> AgentResult[VerificationOutput]:
    return agent.run(request)


@router.post("/analysis", response_model=AgentResult[AnalysisOutput],
             summary="5. Build the decision-ready research brief (no recommendation)")
def analyse(request: AgentRequest[AnalysisInput],
            agent: AnalysisAgent = Depends(get_analysis_agent)) -> AgentResult[AnalysisOutput]:
    return agent.run(request)


@router.post("/calendar", response_model=AgentResult[CalendarOutput],
             summary="6. Rank meeting slots (never books)")
def calendar(request: AgentRequest[CalendarInput],
             agent: CalendarAgent = Depends(get_calendar_agent)) -> AgentResult[CalendarOutput]:
    return agent.run(request)


@router.post("/email-response", response_model=AgentResult[EmailResponseOutput],
             summary="7. Draft an email to the founder (never sends)")
def email_response(request: AgentRequest[EmailResponseInput],
                   agent: EmailResponseAgent = Depends(get_email_response_agent)) -> AgentResult[EmailResponseOutput]:
    return agent.run(request)


@router.post("/action", response_model=AgentResult[ActionOutput],
             summary="8. Build Gmail/Calendar/Sheets payloads for the backend to execute")
def action(request: AgentRequest[ActionInput],
           agent: ActionAgent = Depends(get_action_agent)) -> AgentResult[ActionOutput]:
    return agent.run(request)
