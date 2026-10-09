import { useState } from 'react';
import { Link } from 'react-router-dom';
import { approvals as approvalsApi } from '../lib/api/endpoints';
import type { ApprovalView } from '../lib/api/types';
import { useAuth } from '../lib/auth/AuthContext';
import { useAsync } from '../lib/hooks';
import { formatDateTime, humanize } from '../lib/format';
import { Badge, Button, Card, ConfirmDialog, EmptyState, ErrorState, PageHeader, Pagination, Skeleton, Tabs, TextArea, useToast } from '../components/ui';

/**
 * Actions the AI proposed that wait for a human (e.g. syncing the pipeline sheet when the policy is "ask first").
 * Sending email and creating meetings are approved directly where they are reviewed (draft / slot picker).
 */
export default function Approvals() {
  const { can } = useAuth();
  const toast = useToast();
  const [status, setStatus] = useState<'PENDING' | 'ALL'>('PENDING');
  const [page, setPage] = useState(0);
  const [rejecting, setRejecting] = useState<ApprovalView | null>(null);
  const [reason, setReason] = useState('');
  const [busy, setBusy] = useState<number | null>(null);
  const list = useAsync(() => approvalsApi.list(status === 'ALL' ? undefined : status, page), [status, page]);

  const approve = async (a: ApprovalView) => {
    setBusy(a.id);
    try {
      await approvalsApi.approve(a.id);
      toast('Approved — executing.', 'success');
      list.reload();
    } catch (e) {
      toast(e instanceof Error ? e.message : 'Could not approve', 'error');
    } finally {
      setBusy(null);
    }
  };
  const reject = async () => {
    if (!rejecting) return;
    setBusy(rejecting.id);
    try {
      await approvalsApi.reject(rejecting.id, reason);
      setRejecting(null);
      setReason('');
      list.reload();
    } catch (e) {
      toast(e instanceof Error ? e.message : 'Could not reject', 'error');
    } finally {
      setBusy(null);
    }
  };

  return (
    <div className="stack-lg">
      <PageHeader title="Approvals" subtitle="Nothing leaves Pitsch without a person approving it." />
      <Tabs value={status} onChange={(v) => { setStatus(v); setPage(0); }} tabs={[{ value: 'PENDING', label: 'Waiting' }, { value: 'ALL', label: 'History' }]} />
      <Card padded={false}>
        {list.error ? <ErrorState error={list.error} onRetry={list.reload} /> : !list.data ? <div className="pad"><Skeleton /></div> : list.data.items.length === 0 ? (
          <EmptyState title={status === 'PENDING' ? 'Nothing waiting for approval' : 'No approvals yet'} />
        ) : (
          <>
            <ul className="list">
              {list.data.items.map((a) => (
                <li key={a.id} className="list-row">
                  <div>
                    <strong>{a.summary}</strong>
                    <div className="muted small">
                      {humanize(a.type)} · proposed by {a.proposedBy.toLowerCase()} · {formatDateTime(a.createdAt)}
                      {a.workflowId && <> · <Link to={`/workflows/${a.workflowId}`}>workflow</Link></>}
                    </div>
                    {a.error && <div className="error-text small">{a.error}</div>}
                  </div>
                  <div className="row gap-sm">
                    {a.status === 'PENDING' && can('ACTION_APPROVE') ? (
                      <>
                        <Button size="sm" variant="primary" loading={busy === a.id} onClick={() => approve(a)}>Approve</Button>
                        <Button size="sm" variant="ghost" onClick={() => setRejecting(a)}>Reject</Button>
                      </>
                    ) : <Badge tone={a.status === 'EXECUTED' ? 'green' : a.status === 'PENDING' ? 'orange' : a.status === 'REJECTED' || a.status === 'FAILED' ? 'red' : 'neutral'}>{humanize(a.status)}</Badge>}
                  </div>
                </li>
              ))}
            </ul>
            <div className="pad"><Pagination page={list.data.page} totalPages={list.data.totalPages} totalItems={list.data.totalItems} onPage={setPage} /></div>
          </>
        )}
      </Card>
      <ConfirmDialog open={!!rejecting} title="Reject this action?" confirmLabel="Reject" danger busy={busy === rejecting?.id}
        onCancel={() => setRejecting(null)} onConfirm={reject}>
        <TextArea label="Reason (optional)" rows={3} value={reason} onChange={(e) => setReason(e.target.value)} />
      </ConfirmDialog>
    </div>
  );
}
