import { useMemo, useState } from 'react';
import { Link, useNavigate, useSearchParams } from 'react-router-dom';
import { PageContainer } from '../../components/layout/PageContainer';
import { Badge } from '../../components/ui/Badge';
import { Button } from '../../components/ui/Button';
import { Card } from '../../components/ui/Card';
import { EmptyState } from '../../components/ui/EmptyState';
import { ErrorState } from '../../components/ui/ErrorState';
import { Loading } from '../../components/ui/Loading';
import { Select } from '../../components/ui/Input';
import { useFetch } from '../../hooks/useFetch';
import { usePolling } from '../../hooks/usePolling';
import { workflowService } from '../../services/workflowService';
import type { Workflow } from '../../types';
import { WORKFLOW_LABEL, WORKFLOW_TONE, WORKFLOW_TYPE_LABEL, isWorkflowActive, sortNewest, timeAgo } from '../../utils/format';
import { SubmitEmailModal } from './SubmitEmailModal';
import './pitches.css';

type Filter = 'all' | 'decision' | 'working' | 'done';

const FILTERS: Record<Filter, (w: Workflow) => boolean> = {
  all: () => true,
  decision: (w) => w.status === 'AWAITING_USER' || w.status === 'WAITING_FOR_APPROVAL' || w.status === 'FAILED'
    || (w.status === 'NOT_PITCH' && w.availableActions.length > 0),
  working: (w) => isWorkflowActive(w.status),
  done: (w) => w.status === 'COMPLETED' || w.status === 'STOPPED' || w.status === 'NOT_PITCH',
};

export default function Pitches() {
  const navigate = useNavigate();
  const [params, setParams] = useSearchParams();
  const { data, loading, error, reload } = useFetch(workflowService.list);
  const [filter, setFilter] = useState<Filter>('all');
  const showModal = params.get('new') === '1';

  const workflows = useMemo(() => sortNewest(data ?? []), [data]);
  const visible = workflows.filter(FILTERS[filter]);
  usePolling(reload, workflows.some((w) => isWorkflowActive(w.status)));

  const closeModal = () => {
    params.delete('new');
    setParams(params, { replace: true });
  };

  return (
    <PageContainer
      title="Pitches"
      description="Every incoming email, classified by AI. Open one to see the research and decide what happens next."
      actions={
        <Button icon="plus" onClick={() => setParams({ new: '1' })}>
          Submit pitch email
        </Button>
      }
    >
      <Card
        title="Inbox"
        action={
          <div className="toolbar-inline">
            <Select label="Filter" hideLabel value={filter} onChange={(e) => setFilter(e.target.value as Filter)}>
              <option value="all">All</option>
              <option value="decision">Needs you</option>
              <option value="working">In progress</option>
              <option value="done">Done</option>
            </Select>
            <Button variant="ghost" size="sm" icon="refresh" onClick={reload}>
              Refresh
            </Button>
          </div>
        }
      >
        {loading ? (
          <Loading label="Loading pitches…" />
        ) : error ? (
          <ErrorState message={error} onRetry={reload} />
        ) : visible.length === 0 ? (
          <EmptyState
            icon="ai"
            title={workflows.length === 0 ? 'No pitches yet' : 'Nothing here'}
            description={workflows.length === 0 ? 'Submit a pitch email to see the agents at work.' : 'Try another filter.'}
            action={workflows.length === 0 ? <Button icon="plus" onClick={() => setParams({ new: '1' })}>Submit pitch email</Button> : undefined}
          />
        ) : (
          <ul className="wf-list">
            {visible.map((w) => (
              <li key={w.id}>
                <Link to={`/workflows/${w.id}`} className="wf-row">
                  <div className="wf-row-main">
                    <p className="wf-row-title">
                      {w.companyName ?? w.prompt}
                      {w.type && <span className="wf-type">{WORKFLOW_TYPE_LABEL[w.type] ?? w.type}</span>}
                    </p>
                    <p className="muted clamp">{w.companyName ? w.prompt : w.result ?? ''}</p>
                    <small className="muted">
                      {w.senderName ?? w.sender} · {timeAgo(w.createdAt)}
                      {w.needsHumanReview && <span className="wf-flag"> · needs a human look</span>}
                    </small>
                  </div>
                  <Badge tone={WORKFLOW_TONE[w.status]}>
                    {isWorkflowActive(w.status) && <span className="spinner spinner-xs" aria-hidden="true" />}
                    {WORKFLOW_LABEL[w.status]}
                  </Badge>
                </Link>
              </li>
            ))}
          </ul>
        )}
      </Card>

      {showModal && (
        <SubmitEmailModal onClose={closeModal} onCreated={(id) => navigate(`/workflows/${id}`)} />
      )}
    </PageContainer>
  );
}
