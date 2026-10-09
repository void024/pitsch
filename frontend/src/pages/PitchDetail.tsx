import { useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { ApiError } from '../lib/api/client';
import { pitches as pitchesApi, tasks as tasksApi, workspace as workspaceApi } from '../lib/api/endpoints';
import { DEAL_STAGES, type DealStage, type PitchInput } from '../lib/api/types';
import { useAuth } from '../lib/auth/AuthContext';
import { useAsync } from '../lib/hooks';
import { STAGE_LABEL, formatDateTime, humanize, timeAgo } from '../lib/format';
import { safeHttpUrl } from '../lib/safeUrl';
import { RiskBadge, StageBadge, WorkflowBadge } from '../components/badges';
import { BriefView } from '../components/BriefView';
import {
  Badge, Button, Card, ConfirmDialog, EmptyState, ErrorState, InlineError, PageHeader, Skeleton, Tabs, TextArea, TextInput, useToast,
} from '../components/ui';

type Tab = 'brief' | 'timeline' | 'workflows' | 'tasks' | 'details';

export default function PitchDetail() {
  const id = Number(useParams().id);
  const { can } = useAuth();
  const navigate = useNavigate();
  const toast = useToast();
  const [tab, setTab] = useState<Tab>('brief');
  const [confirmDelete, setConfirmDelete] = useState(false);
  const [busy, setBusy] = useState(false);
  const detail = useAsync(() => pitchesApi.get(id), [id]);
  const members = useAsync(() => (can('MEMBER_READ') ? workspaceApi.members() : Promise.resolve([])), []);

  if (detail.error) return <ErrorState error={detail.error} onRetry={detail.reload} />;
  if (!detail.data) return <Skeleton lines={6} />;
  const { pitch } = detail.data;
  const website = safeHttpUrl(pitch.website);

  const patch = async (input: PitchInput) => {
    try {
      const updated = await pitchesApi.update(pitch.id, { ...input, version: pitch.version });
      detail.setData((d) => ({ ...d!, pitch: updated }));
      toast('Saved', 'success');
    } catch (e) {
      if (e instanceof ApiError && e.status === 409) detail.reload();
      toast(e instanceof Error ? e.message : 'Could not save', 'error');
    }
  };

  const remove = async () => {
    setBusy(true);
    try {
      await pitchesApi.remove(pitch.id);
      toast('Pitch deleted', 'success');
      navigate('/pitches');
    } catch (e) {
      toast(e instanceof Error ? e.message : 'Could not delete', 'error');
      setBusy(false);
      setConfirmDelete(false);
    }
  };

  return (
    <div className="stack-lg">
      <PageHeader
        title={pitch.companyName ?? 'Unknown company'}
        subtitle={<span className="row gap-sm wrap">
          <StageBadge stage={pitch.dealStage} /><RiskBadge level={pitch.riskLevel} />
          {pitch.hasFollowUp && <Badge tone="blue">follow-up</Badge>}
          {website && <a href={website} target="_blank" rel="noopener noreferrer nofollow">{pitch.companyDomain ?? website}</a>}
          <span className="muted small">Updated {timeAgo(pitch.updatedAt)}</span>
        </span>}
        actions={<>
          {can('PITCH_WRITE') && (
            <select className="input" aria-label="Move to stage" value={pitch.dealStage} onChange={(e) => patch({ dealStage: e.target.value as DealStage })}>
              {DEAL_STAGES.map((s) => <option key={s} value={s}>{STAGE_LABEL[s]}</option>)}
            </select>
          )}
          {can('PITCH_WRITE') && (members.data?.length ?? 0) > 0 && (
            <select className="input" aria-label="Owner" value={pitch.ownerUserId ?? ''} onChange={(e) => e.target.value && patch({ ownerUserId: Number(e.target.value) })}>
              <option value="">Owner…</option>
              {members.data!.map((m) => <option key={m.userId} value={m.userId}>{m.name}</option>)}
            </select>
          )}
          {pitch.latestWorkflowId && <Link className="btn btn-secondary" to={`/workflows/${pitch.latestWorkflowId}`}>Open workflow</Link>}
          {can('PITCH_DELETE') && <Button variant="danger" onClick={() => setConfirmDelete(true)}>Delete</Button>}
        </>}
      />

      <Tabs value={tab} onChange={setTab} tabs={[
        { value: 'brief', label: 'Brief' }, { value: 'timeline', label: 'Timeline' },
        { value: 'workflows', label: `Workflows (${detail.data.workflows.length})` }, { value: 'tasks', label: 'Tasks' },
        { value: 'details', label: 'Details' },
      ]} />

      {tab === 'brief' && <BriefView brief={detail.data.brief} updatedAt={detail.data.briefUpdatedAt} />}
      {tab === 'timeline' && <Timeline pitchId={pitch.id} />}
      {tab === 'workflows' && (
        <Card padded={false}>
          {detail.data.workflows.length === 0 ? <EmptyState title="No workflows">Manually added pitches have no email workflow.</EmptyState> : (
            <ul className="list">
              {detail.data.workflows.map((w) => (
                <li key={w.id}><Link className="list-row" to={`/workflows/${w.id}`}>
                  <div><strong>{w.prompt ?? 'Workflow'}</strong><div className="muted small">{humanize(w.type)} · {formatDateTime(w.createdAt)}</div></div>
                  <WorkflowBadge status={w.status} />
                </Link></li>
              ))}
            </ul>
          )}
        </Card>
      )}
      {tab === 'tasks' && <PitchTasks pitchId={pitch.id} />}
      {tab === 'details' && <PitchDetailsForm pitch={pitch} canEdit={can('PITCH_WRITE')} onSave={patch} />}

      <ConfirmDialog open={confirmDelete} title="Delete this pitch?" danger confirmLabel="Delete pitch" busy={busy}
        onCancel={() => setConfirmDelete(false)} onConfirm={remove}>
        <p>The pitch record is deleted. Its emails and workflow history stay in the audit trail. This cannot be undone.</p>
      </ConfirmDialog>
    </div>
  );
}

function Timeline({ pitchId }: { pitchId: number }) {
  const t = useAsync(() => pitchesApi.timeline(pitchId), [pitchId]);
  if (t.error) return <ErrorState error={t.error} onRetry={t.reload} />;
  if (!t.data) return <Skeleton />;
  if (!t.data.length) return <EmptyState title="Nothing yet" />;
  return (
    <Card>
      <ol className="timeline">
        {t.data.map((e, i) => (
          <li key={`${e.kind}-${e.refId}-${i}`}>
            <span className="timeline-dot" aria-hidden />
            <div>
              <strong>{e.title}</strong>
              {e.detail && <div className="muted small">{e.detail}</div>}
              <div className="muted small">{formatDateTime(e.at)}{e.workflowId && <> · <Link to={`/workflows/${e.workflowId}`}>workflow</Link></>}</div>
            </div>
          </li>
        ))}
      </ol>
    </Card>
  );
}

function PitchTasks({ pitchId }: { pitchId: number }) {
  const { can } = useAuth();
  const list = useAsync(() => tasksApi.list(pitchId), [pitchId]);
  const [title, setTitle] = useState('');
  const [error, setError] = useState<unknown>(null);
  const add = async () => {
    try {
      await tasksApi.create({ title: title.trim(), pitchId });
      setTitle('');
      list.reload();
    } catch (e) {
      setError(e);
    }
  };
  return (
    <Card title="Tasks">
      {can('TASK_WRITE') && (
        <div className="row gap-sm">
          <input className="input grow" placeholder="Add a task for this pitch" value={title} onChange={(e) => setTitle(e.target.value)}
            onKeyDown={(e) => e.key === 'Enter' && title.trim() && add()} aria-label="New task" />
          <Button onClick={add} disabled={!title.trim()}>Add</Button>
        </div>
      )}
      <InlineError error={error} />
      {list.data?.length ? (
        <ul className="list">
          {list.data.map((t) => (
            <li key={t.id} className="list-row">
              <label className="checkbox">
                <input type="checkbox" checked={t.status === 'DONE'} disabled={!can('TASK_WRITE')}
                  onChange={async (e) => { await tasksApi.setStatus(t.id, e.target.checked ? 'DONE' : 'TODO'); list.reload(); }} />
                <span className={t.status === 'DONE' ? 'done' : ''}>{t.title}</span>
              </label>
              <span className="muted small">{t.dueDate ?? ''}</span>
            </li>
          ))}
        </ul>
      ) : <p className="muted">No tasks.</p>}
    </Card>
  );
}

function PitchDetailsForm({ pitch, canEdit, onSave }: { pitch: import('../lib/api/types').Pitch; canEdit: boolean; onSave: (i: PitchInput) => Promise<void> }) {
  const [form, setForm] = useState({
    companyName: pitch.companyName ?? '', founderName: pitch.founderName ?? '', founderEmail: pitch.founderEmail ?? '',
    website: pitch.website ?? '', sector: pitch.sector ?? '', stage: pitch.stage ?? '', amountRequested: pitch.amountRequested ?? '',
    oneLiner: pitch.oneLiner ?? '', description: pitch.description ?? '',
  });
  const [busy, setBusy] = useState(false);
  const upd = (k: keyof typeof form) => (e: { target: { value: string } }) => setForm({ ...form, [k]: e.target.value });
  return (
    <Card title="Details" actions={canEdit && <Button variant="primary" loading={busy} onClick={async () => { setBusy(true); await onSave(form); setBusy(false); }}>Save</Button>}>
      <fieldset disabled={!canEdit} className="stack">
        <div className="grid-2">
          <TextInput label="Company" value={form.companyName} onChange={upd('companyName')} />
          <TextInput label="Website" value={form.website} onChange={upd('website')} />
          <TextInput label="Founder" value={form.founderName} onChange={upd('founderName')} />
          <TextInput label="Founder email" value={form.founderEmail} onChange={upd('founderEmail')} />
          <TextInput label="Sector" value={form.sector} onChange={upd('sector')} />
          <TextInput label="Funding stage" value={form.stage} onChange={upd('stage')} />
          <TextInput label="Raising" value={form.amountRequested} onChange={upd('amountRequested')} />
        </div>
        <TextArea label="One-liner" rows={2} value={form.oneLiner} onChange={upd('oneLiner')} />
        <TextArea label="Notes" rows={5} value={form.description} onChange={upd('description')} />
        <p className="muted small">Source: {pitch.source === 'EMAIL' ? 'email (AI-extracted, editable)' : 'manual'} · created {formatDateTime(pitch.createdAt)}</p>
      </fieldset>
    </Card>
  );
}
