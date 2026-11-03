from app.agents.base import execute
from app.agents.classifier.matcher import score_candidates
from app.agents.classifier.prompt import SYSTEM_PROMPT, build_user_prompt
from app.agents.classifier.schemas import (
    ClassifierLLMOutput,
    ClassifierOutput,
    EmailCategory,
    EmailInput,
    MatchSignal,
    NotPitchType,
    RecommendedAction,
)
from app.core.injection import describe, detect_prompt_injection
from app.core.llm import CallStats, LLMClient, call_structured
from app.schemas.common import AgentRequest, AgentResult

AGENT_NAME = "EMAIL_CLASSIFIER"

PITCH_RELATED = {EmailCategory.NEW_PITCH, EmailCategory.PITCH_FOLLOW_UP, EmailCategory.PITCH_UPDATE}
EXISTING_PITCH = {EmailCategory.PITCH_FOLLOW_UP, EmailCategory.PITCH_UPDATE}


def decide_action(
    category: EmailCategory,
    meeting_requested: bool,
    workflow_closed: bool,
) -> RecommendedAction:
    """Deterministic category-to-action mapping.

    The LLM interprets the email; this function decides the application-level
    recommendation so workflow semantics remain testable and predictable.
    """
    if category == EmailCategory.NOT_PITCH:
        return RecommendedAction.STOP
    if category == EmailCategory.PITCH_FOLLOW_UP:
        if workflow_closed:
            return RecommendedAction.COMPLETE_WORKFLOW
        return RecommendedAction.PLAN_MEETING if meeting_requested else RecommendedAction.PLAN_EMAIL_RESPONSE
    return RecommendedAction.ASK_TO_HANDLE


class EmailClassifierAgent:
    """Classifies one email. No tools, no side effects: it never starts workflows, sends email,
    touches the database, or makes investment judgements."""

    def __init__(
        self,
        llm: LLMClient,
        *,
        review_threshold: float = 0.75,
        max_body_chars: int = 12000,
        max_retries: int = 2,
        retry_backoff_seconds: float = 0.5,
    ):
        self.llm = llm
        self.review_threshold = review_threshold
        self.max_body_chars = max_body_chars
        self.max_retries = max_retries
        self.retry_backoff_seconds = retry_backoff_seconds

    def run(self, request: AgentRequest[EmailInput]) -> AgentResult[ClassifierOutput]:
        return execute(AGENT_NAME, request, ClassifierOutput, self._classify,
                       log_fields=lambda d: {"category": d.category.value})

    # ------------------------------------------------------------------

    def _classify(self, email: EmailInput, stats: CallStats) -> ClassifierOutput:
        signals = score_candidates(email)
        user_prompt, truncated = build_user_prompt(email, signals, self.max_body_chars)
        raw = call_structured(
            self.llm, SYSTEM_PROMPT, user_prompt, ClassifierLLMOutput,
            stats=stats, max_retries=self.max_retries, backoff_seconds=self.retry_backoff_seconds,
        )
        return self._postprocess(email, raw, signals, truncated)

    def _postprocess(
        self, email: EmailInput, raw: ClassifierLLMOutput, signals: list[MatchSignal], truncated: bool
    ) -> ClassifierOutput:
        review: list[str] = []
        warnings: list[str] = []
        category = raw.category
        pitch_id = raw.previous_pitch_id
        confidence = raw.confidence
        overridden = False
        valid_ids = {c.pitch_id for c in email.candidate_pitches}

        # 1. Never trust an ID the model produced unless it was offered.
        if pitch_id is not None and pitch_id not in valid_ids:
            review.append("Model returned a pitch ID that was not among the candidates; it was discarded.")
            pitch_id = None

        # 2. Internal consistency between category and pitch link.
        if category in (EmailCategory.NEW_PITCH, EmailCategory.NOT_PITCH) and pitch_id is not None:
            review.append(f"{category.value} cannot be linked to an existing pitch; link removed.")
            pitch_id = None
        if category in EXISTING_PITCH and pitch_id is None:
            review.append("Email looks related to an existing pitch, but no valid pitch could be linked.")
            category, overridden = EmailCategory.AMBIGUOUS, True

        # 3. Several companies pitched at once.
        if category == EmailCategory.NEW_PITCH and len(raw.detected_companies) > 1:
            review.append(f"Multiple companies pitched in one email: {', '.join(raw.detected_companies)}.")
            category, overridden = EmailCategory.AMBIGUOUS, True

        # 4. Prompt-injection heuristics: the email is routed to a human, never acted on automatically.
        injection = detect_prompt_injection(email.subject, email.body, email.sender.name,
                                            *(a.filename for a in email.attachments))
        if injection:
            review.append("Possible prompt-injection attempt in the email (" + describe(injection)
                          + "). Its content was treated as data only; check it before acting.")

        # 5. Strong deterministic signal disagrees with the model.
        if signals and "SAME_THREAD" in signals[0].signals and category == EmailCategory.NEW_PITCH:
            review.append("Email is in the same thread as an existing pitch but was classified as a new pitch.")

        if overridden:
            confidence = min(confidence, 0.5)
        if category == EmailCategory.AMBIGUOUS and not overridden:
            review.append("Model could not classify the email confidently.")
        if not overridden and confidence < self.review_threshold:
            review.append(f"Confidence {confidence:.2f} is below the review threshold {self.review_threshold:.2f}.")

        not_pitch_type = None
        if category == EmailCategory.NOT_PITCH:
            not_pitch_type = raw.not_pitch_type or NotPitchType.OTHER

        # Non-blocking warnings for the analyst.
        if truncated:
            warnings.append("Email body was truncated before classification.")
        if category in PITCH_RELATED:
            if not email.attachments:
                warnings.append("Pitch has no attachments.")
            elif any(not a.readable for a in email.attachments):
                warnings.append("One or more attachments could not be read.")

        return ClassifierOutput(
            category=category,
            is_pitch=category in PITCH_RELATED,
            is_follow_up=category in EXISTING_PITCH,
            not_pitch_type=not_pitch_type,
            previous_pitch_id=pitch_id,
            detected_companies=raw.detected_companies,
            is_forwarded=raw.is_forwarded,
            original_sender_email=raw.original_sender_email,
            meeting_requested=raw.meeting_requested,
            workflow_closed=raw.workflow_closed,
            confidence=round(confidence, 3),
            recommended_action=(
                RecommendedAction.ASK_TO_HANDLE
                if review and category != EmailCategory.NOT_PITCH
                else decide_action(category, raw.meeting_requested, raw.workflow_closed)
            ),
            needs_human_review=bool(review),
            review_reasons=review,
            warnings=warnings,
            reason=raw.reason,
            match_signals=signals,
            prompt_injection_suspected=bool(injection),
            prompt_injection_signals=injection,
        )
