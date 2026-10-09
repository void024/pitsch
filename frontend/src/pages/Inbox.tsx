import { useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { emails as emailsApi, integrations as integrationsApi } from '../lib/api/endpoints';
import { useAuth } from '../lib/auth/AuthContext';
import { useAsync } from '../lib/hooks';
import { formatDateTime, humanize } from '../lib/format';
import { ImportEmailModal } from '../components/ImportEmailModal';
import { Badge, Button, Card, EmptyState, ErrorState, PageHeader, Pagination, Skeleton, useToast } from '../components/ui';

export default function Inbox() {
  const { can } = useAuth();
  const toast = useToast();
  const [page, setPage] = useState(0);
  const [importOpen, setImportOpen] = useState(false);
  const list = useAsync(() => emailsApi.list(page), [page]);
  const status = useAsync(() => integrationsApi.list(), []);
  const gmail = status.data?.find((s) => s.integration === 'GMAIL');

  const syncNow = async () => {
    try {
      await emailsApi.gmailSync();
      toast('Gmail sync queued. New emails appear here within a minute.', 'success');
      window.setTimeout(list.reload, 8000);
    } catch (e) {
      toast(e instanceof Error ? e.message : 'Sync failed', 'error');
    }
  };

  return (
    <div className="stack-lg">
      <PageHeader title="Inbox" subtitle="Every email Pitsch has received, from Gmail or imported by hand."
        actions={<>
          {gmail?.connected && can('EMAIL_IMPORT') && <Button onClick={syncNow}>Sync Gmail now</Button>}
          {can('EMAIL_IMPORT') && <Button variant="primary" onClick={() => setImportOpen(true)}>Import email</Button>}
        </>} />
      {gmail && !gmail.connected && (
        <div className="banner banner-info">Connect Gmail to bring pitch emails in automatically. <Link to="/integrations">Integrations</Link></div>
      )}
      <Card padded={false}>
        {list.error ? <ErrorState error={list.error} onRetry={list.reload} /> : !list.data ? <div className="pad"><Skeleton /></div> : list.data.items.length === 0 ? (
          <EmptyState title="No emails yet" action={can('EMAIL_IMPORT') && <Button variant="primary" onClick={() => setImportOpen(true)}>Import your first pitch</Button>}>
            Imported and synced emails will show up here.
          </EmptyState>
        ) : (
          <>
            <div className="table-wrap">
              <table className="table">
                <thead><tr><th>From</th><th>Subject</th><th>Classified as</th><th>Files</th><th>Received</th></tr></thead>
                <tbody>
                  {list.data.items.map((e) => (
                    <tr key={e.id}>
                      <td><strong>{e.senderName ?? e.sender}</strong><div className="muted small">{e.sender}</div></td>
                      <td><EmailLink id={e.id} subject={e.subject} /></td>
                      <td>{e.category ? <Badge tone={e.isPitch ? 'purple' : 'neutral'}>{humanize(e.category)}</Badge> : <span className="muted small">pending</span>}
                        {e.source === 'GMAIL' && <span className="muted small"> · Gmail</span>}</td>
                      <td className="small">{e.attachments.length || '—'}</td>
                      <td className="small">{formatDateTime(e.receivedAt)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
            <div className="pad"><Pagination page={list.data.page} totalPages={list.data.totalPages} totalItems={list.data.totalItems} onPage={setPage} /></div>
          </>
        )}
      </Card>
      <ImportEmailModal open={importOpen} onClose={() => { setImportOpen(false); list.reload(); }} />
    </div>
  );
}

function EmailLink({ id, subject }: { id: number; subject: string | null }) {
  const [busy, setBusy] = useState(false);
  const toast = useToast();
  const navigate = useNavigate();
  const open = async () => {
    setBusy(true);
    try {
      const res = await emailsApi.get(id);
      if (res.workflowId) navigate(`/workflows/${res.workflowId}`);
    } catch (e) {
      toast(e instanceof Error ? e.message : 'Could not open', 'error');
    } finally {
      setBusy(false);
    }
  };
  return <button type="button" className="link-btn text-left" onClick={open} disabled={busy}>{subject || '(no subject)'}</button>;
}
