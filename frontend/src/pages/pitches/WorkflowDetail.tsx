import { useCallback, useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { PageContainer } from '../../components/layout/PageContainer';
import { Badge } from '../../components/ui/Badge';
import { Button } from '../../components/ui/Button';
import { Card } from '../../components/ui/Card';
import { ErrorState } from '../../components/ui/ErrorState';
import { Icon } from '../../components/ui/Icon';
import { Loading } from '../../components/ui/Loading';
import { useFetch } from '../../hooks/useFetch';
import { usePolling } from '../../hooks/usePolling';
import { getErrorMessage } from '../../services/api';
import { workflowService } from '../../services/workflowService';
import type { Workflow, WorkflowAction } from '../../types';
import { ACTION_LABEL, WORKFLOW_LABEL, WORKFLOW_TONE, WORKFLOW_TYPE_LABEL, isWorkflowActive } from '../../utils/format';
import { BriefView } from './BriefView';
import { AgentProgress, DraftCard, EmailCard, MeetingCard } from './WorkflowPanels';
import './pitches.css';

const STEP_LABEL: Record<string, string> = {
  DOCUMENT: 'Reading the pitch deck…',
  RESEARCH: 'Researching the company on the web…',
  VERIFICATION: 'Checking the founder’s claims against the evidence…',
  ANALYSIS: 'Writing the research brief…',
  CALENDAR: 'Finding meeting slots…',
  EMAIL_DRAFTING: 'Drafting the email…',
  CLASSIFYING: 'Reading the email…',
};

/** What the decision panel says, based on the backend's status/type/step (no workflow logic here). */
function headline(w: Workflow): string {
  const company = w.companyName ?? 'this startup';
  switch (w.status) {
    case 'RECEIVED':
    case 'CLASSIFYING':
      return 'Analyzing email…';
    case 'NOT_PITCH':
      return w.availableActions.length > 0
        ? 'Pitsch wasn’t sure this is a pitch. You can handle it anyway.'
        : 'This email isn’t a pitch. No workflow was started.';
    case 'AWAITING_USER':
      return w.type === 'FOLLOW_UP'
        ? `Follow-up detected for ${company}.`
        : 'New pitch detected. Would you like Pitsch to handle this pitch?';
    case 'PROCESSING':
      return STEP_LABEL[w.currentStep ?? ''] ?? 'Agents are working…';
    case 'WAITING_FOR_APPROVAL':
      switch (w.currentStep) {
        case 'MEETING_SLOTS_READY':
          return 'Meeting slots are ready — pick one to schedule.';
        case 'MEETING_SCHEDULED':
          return 'Meeting scheduled. You can email the founder or finish the workflow.';
        case 'EMAIL_DRAFT_READY':
          return 'An email draft is ready for your review.';
        case 'EMAIL_SENT':
          return 'Email sent. Anything else?';
        default:
          return 'The research brief is ready. Choose what happens next.';
      }
    case 'COMPLETED':
      return 'Workflow completed.';
    case 'STOPPED':
      return 'Workflow stopped.';
    case 'FAILED':
      return 'An agent failed. You can retry.';
  }
}

function actionLabel(w: Workflow, action: WorkflowAction): string {
  if (action === 'COMPLETE_WORKFLOW') {
    if (w.status === 'NOT_PITCH') return 'Handle anyway';
    if (w.brief || w.status === 'WAITING_FOR_APPROVAL') return w.brief ? 'Mark complete' : 'Run full research';
  }
  return ACTION_LABEL[action];
}

export default function WorkflowDetail() {
  const { id = '' } = useParams();
  const fetcher = useCallback(() => workflowService.get(id), [id]);
  const { data, loading, error, reload } = useFetch(fetcher);
  const [override, setOverride] = useState<Workflow | null>(null);
  const [busy, setBusy] = useState<WorkflowAction | null>(null);
  const [actionError, setActionError] = useState<string | null>(null);

  // Responses from actions are fresher than the last poll until the next fetch arrives.
  const w = override && data && new Date(override.updatedAt ?? 0) > new Date(data.updatedAt ?? 0) ? override : data;
  usePolling(reload, Boolean(w && isWorkflowActive(w.status)), 2500);

  const updated = (next: Workflow) => {
    setOverride(next);
    reload();
  };

  const run = async (action: WorkflowAction) => {
    if (!w) return;
    setBusy(action);
    setActionError(null);
    try {
      updated(await workflowService.action(w.id, { action }));
    } catch (err: unknown) {
      setActionError(getErrorMessage(err));
    } finally {
      setBusy(null);
    }
  };

  if (loading) return <Loading label="Loading workflow…" />;
  if (error || !w) {
    return (
      <PageContainer title="Workflow">
        <ErrorState message={error ?? 'Workflow not found.'} onRetry={reload} />
      </PageContainer>
    );
  }

  const active = isWorkflowActive(w.status);
  return (
    <PageContainer
      title={w.companyName ?? w.prompt}
      description={w.companyName ? w.prompt : undefined}
      actions={
        <Link to="/pitches" className="btn btn-ghost btn-sm">
          <Icon name="chevronLeft" size={16} /> All pitches
        </Link>
      }
    >
      <div className="wf-grid">
        <div className="stack">
          <Card className="decision">
            <div className="decision-head">
              <Badge tone={WORKFLOW_TONE[w.status]}>
                {active && <span className="spinner spinner-xs" aria-hidden="true" />}
                {WORKFLOW_LABEL[w.status]}
              </Badge>
              {w.type && w.type !== 'NOT_PITCH' && <Badge tone="neutral">{WORKFLOW_TYPE_LABEL[w.type] ?? w.type}</Badge>}
              {typeof w.confidence === 'number' && <small className="muted">AI confidence {Math.round(w.confidence * 100)}%</small>}
            </div>
            <h2 className="decision-title">{headline(w)}</h2>
            {w.classificationReason && !w.brief && <p className="muted">{w.classificationReason}</p>}
            {w.recommendedAction && (w.availableActions.includes(w.recommendedAction as WorkflowAction) || (w.recommendedAction === 'ASK_TO_HANDLE' && w.availableActions.includes('COMPLETE_WORKFLOW'))) && (
              <p className="recommendation">
                <Icon name="ai" size={16} /> AI recommendation:{' '}
                <strong>{w.recommendedAction === 'ASK_TO_HANDLE' ? ACTION_LABEL.COMPLETE_WORKFLOW : ACTION_LABEL[w.recommendedAction as WorkflowAction] ?? w.recommendedAction.replaceAll('_', ' ').toLowerCase()}</strong>
              </p>
            )}
            {w.error && <p className="form-error" role="alert">{w.error}</p>}
            {actionError && <p className="form-error" role="alert">{actionError}</p>}
            {w.availableActions.length > 0 && (
              <div className="action-bar">
                {w.availableActions.map((a) => (
                  <Button
                    key={a}
                    variant={a === 'STOP' ? 'ghost' : a === w.recommendedAction || (a === 'COMPLETE_WORKFLOW' && w.recommendedAction === 'ASK_TO_HANDLE') || a === 'RETRY' ? 'primary' : 'secondary'}
                    loading={busy === a}
                    disabled={busy !== null}
                    onClick={() => void run(a)}
                  >
                    {actionLabel(w, a)}
                  </Button>
                ))}
              </div>
            )}
            {w.reviewReasons.length > 0 && (
              <ul className="review-list">
                {w.reviewReasons.map((r) => (
                  <li key={r}><Icon name="alert" size={14} /> {r}</li>
                ))}
              </ul>
            )}
            {w.warnings.length > 0 && (
              <details className="warnings">
                <summary>{w.warnings.length} note{w.warnings.length > 1 ? 's' : ''} from the agents</summary>
                <ul>
                  {w.warnings.map((x) => (
                    <li key={x}>{x}</li>
                  ))}
                </ul>
              </details>
            )}
          </Card>

          {w.brief ? (
            <BriefView workflowId={w.id} brief={w.brief} companyName={w.companyName} />
          ) : w.status === 'PROCESSING' && ['DOCUMENT', 'RESEARCH', 'VERIFICATION', 'ANALYSIS'].includes(w.currentStep ?? '') ? (
            <Card title="Research brief">
              <Loading label="The agents are researching this pitch. This usually takes a minute or two." />
            </Card>
          ) : null}
        </div>

        <div className="stack">
          {w.draft && <DraftCard key={`${w.draft.id}-${w.draft.status}`} draft={w.draft} onUpdated={updated} />}
          <MeetingCard key={`${w.slots?.[0]?.start ?? 'none'}-${w.meeting?.start ?? ''}`} workflow={w} onUpdated={updated} />
          {w.agents && <AgentProgress agents={w.agents} />}
          {w.email && <EmailCard email={w.email} />}
        </div>
      </div>
    </PageContainer>
  );
}
