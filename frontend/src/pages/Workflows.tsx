import { Link, useSearchParams } from 'react-router-dom';
import { workflows as workflowsApi } from '../lib/api/endpoints';
import { useAsync, useInterval } from '../lib/hooks';
import { WORKFLOW_LABEL, humanize, timeAgo } from '../lib/format';
import type { WorkflowStatus } from '../lib/api/types';
import { WorkflowBadge } from '../components/badges';
import { Badge, Card, EmptyState, ErrorState, PageHeader, Pagination, Skeleton } from '../components/ui';

const STATUSES = Object.keys(WORKFLOW_LABEL) as WorkflowStatus[];

export default function Workflows() {
  const [params, setParams] = useSearchParams();
  const status = params.get('status') ?? '';
  const type = params.get('type') ?? '';
  const needsAction = params.get('needsAction') === 'true';
  const page = Number(params.get('page') ?? 0);
  const set = (key: string, value: string) => {
    const next = new URLSearchParams(params);
    if (value) next.set(key, value); else next.delete(key);
    if (key !== 'page') next.delete('page');
    setParams(next, { replace: true });
  };
  const list = useAsync((signal) => workflowsApi.list({
    status: status || undefined, type: type || undefined, needsAction: needsAction || undefined, page, size: 25,
  }, signal), [status, type, needsAction, page]);
  useInterval(list.reload, 10_000, !!list.data?.items.some((w) => ['RECEIVED', 'CLASSIFYING', 'PROCESSING'].includes(w.status)));

  return (
    <div className="stack-lg">
      <PageHeader title="Workflows" subtitle="One workflow per incoming email: what the agents did and what is waiting for you." />
      <div className="filters">
        <label className="checkbox"><input type="checkbox" checked={needsAction} onChange={(e) => set('needsAction', e.target.checked ? 'true' : '')} /><span>Needs my decision</span></label>
        <select className="input" aria-label="Status" value={status} onChange={(e) => set('status', e.target.value)}>
          <option value="">All statuses</option>
          {STATUSES.map((s) => <option key={s} value={s}>{WORKFLOW_LABEL[s]}</option>)}
        </select>
        <select className="input" aria-label="Type" value={type} onChange={(e) => set('type', e.target.value)}>
          <option value="">All types</option>
          <option value="NEW_PITCH">New pitch</option><option value="FOLLOW_UP">Follow-up</option><option value="NOT_PITCH">Not a pitch</option>
        </select>
      </div>
      <Card padded={false}>
        {list.error ? <ErrorState error={list.error} onRetry={list.reload} /> : !list.data ? <div className="pad"><Skeleton lines={5} /></div> : list.data.items.length === 0 ? (
          <EmptyState title="No workflows">Workflows start when an email arrives.</EmptyState>
        ) : (
          <>
            <ul className="list">
              {list.data.items.map((w) => (
                <li key={w.id}>
                  <Link to={`/workflows/${w.id}`} className="list-row">
                    <div>
                      <strong>{w.companyName ?? w.prompt ?? 'Email'}</strong>
                      <div className="muted small">{w.sender ?? ''} · {humanize(w.type)} · {timeAgo(w.updatedAt)}</div>
                    </div>
                    <div className="row gap-sm">
                      {w.promptInjectionSuspected && <Badge tone="red">injection?</Badge>}
                      {w.needsHumanReview && <Badge tone="orange">review</Badge>}
                      <WorkflowBadge status={w.status} />
                    </div>
                  </Link>
                </li>
              ))}
            </ul>
            <div className="pad"><Pagination page={list.data.page} totalPages={list.data.totalPages} totalItems={list.data.totalItems} onPage={(p) => set('page', String(p))} /></div>
          </>
        )}
      </Card>
    </div>
  );
}
