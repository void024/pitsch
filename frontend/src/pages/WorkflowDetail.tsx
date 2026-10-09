import { useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { newIdempotencyKey, request } from '../lib/api/client';
import { drafts as draftsApi, emails as emailsApi, workflows as workflowsApi } from '../lib/api/endpoints';
import type { DraftView, Slot, WorkflowAction, WorkflowView } from '../lib/api/types';
import { useAuth } from '../lib/auth/AuthContext';
import { downloadResponse, useAsync, useInterval } from '../lib/hooks';
import {
  ACTION_LABEL, cx, formatBytes, formatDateTime, formatDateTimeIn, formatPercent, formatUsd, humanize, isWorkflowActive,
} from '../lib/format';
import { safeHttpUrl } from '../lib/safeUrl';
import { WorkflowBadge } from '../components/badges';
import { BriefView } from '../components/BriefView';
import {
  Badge, Button, Card, ConfirmDialog, EmptyState, ErrorState, InlineError, PageHeader, Skeleton, Tabs, TextArea, TextInput, useToast,
} from '../components/ui';

type Tab = 'overview' | 'brief' | 'meeting' | 'email' | 'activity';

const ACTION_HELP: Record<WorkflowAction, string> = {
  COMPLETE_WORKFLOW: 'Run the research pipeline (or close the workflow when the work is done).',
  PLAN_MEETING: 'Ask the Calendar Agent for free slots. Nothing is booked until you pick one.',
  PLAN_EMAIL_RESPONSE: 'Ask the Email Agent for a draft reply. Nothing is sent until you approve it.',
  RETRY: 'Run the failed step again.',
  STOP: 'Stop working on this email.',
};

export default function WorkflowDetail() {
  const id = Number(useParams().id);
  const { can } = useAuth();
  const toast = useToast();
  const wf = useAsync((signal) => workflowsApi.get(id, signal), [id]);
  const [tab, setTab] = useState<Tab>('overview');
  const [busyAction, setBusyAction] = useState<WorkflowAction | null>(null);
  const [confirmStop, setConfirmStop] = useState(false);
  const [actionError, setActionError] = useState<unknown>(null);
  const active = isWorkflowActive(wf.data?.status);
  useInterval(wf.reload, 2500, active);

  // Jump to the tab that matches what the workflow just produced.
  const step = wf.data?.currentStep;
  const [seenStep, setSeenStep] = useState(step);
  if (step !== seenStep) {
    setSeenStep(step);
    if (step === 'BRIEF_READY') setTab('brief');
    else if (step === 'MEETING_SLOTS_READY' || step === 'MEETING_SCHEDULED') setTab('meeting');
    else if (step === 'EMAIL_DRAFT_READY' || step === 'EMAIL_SENT') setTab('email');
  }

  if (wf.error && !wf.data) return <ErrorState error={wf.error} onRetry={wf.reload} />;
  if (!wf.data) return <Skeleton lines={8} />;
  const w = wf.data;

  const run = async (action: WorkflowAction) => {
    setBusyAction(action);
    setActionError(null);
    try {
      const next = await workflowsApi.action(w.id, action);
      wf.setData(next);
      if (action === 'STOP') toast('Workflow stopped', 'info');
    } catch (e) {
      setActionError(e);
      wf.reload();
    } finally {
      setBusyAction(null);
      setConfirmStop(false);
    }
  };

  const actions = w.availableActions.filter((a) => a !== 'STOP');
  return (
    <div className="stack-lg">
      <PageHeader
        title={w.companyName ?? w.prompt ?? 'Workflow'}
        subtitle={<span className="row gap-sm wrap">
          <WorkflowBadge status={w.status} />
          {w.type && <Badge>{humanize(w.type)}</Badge>}
          {w.emailSource === 'GMAIL' && <Badge tone="blue">Gmail</Badge>}
          <span className="muted small">{w.senderName ?? w.sender} · {formatDateTime(w.createdAt)}</span>
          {w.pitchId && <Link to={`/pitches/${w.pitchId}`} className="small">Open pitch</Link>}
        </span>}
        actions={<>
          {can('WORKFLOW_RUN') && actions.map((a) => (
            <Button key={a} variant={w.recommendedAction === a ? 'primary' : 'secondary'} loading={busyAction === a}
              disabled={!!busyAction} title={ACTION_HELP[a]} onClick={() => run(a)}>{ACTION_LABEL[a]}</Button>
          ))}
          {can('WORKFLOW_RUN') && w.availableActions.includes('STOP') && (
            <Button variant="ghost" disabled={!!busyAction} onClick={() => setConfirmStop(true)}>Stop</Button>
          )}
        </>}
      />

      <InlineError error={actionError} />
      {w.promptInjectionSuspected && (
        <div className="banner banner-danger" role="alert">
          <strong>Possible prompt-injection attempt.</strong> This email contains text aimed at manipulating AI systems.
          It was treated as data only. Review it carefully; no action is taken without your approval.
        </div>
      )}
      {w.status === 'FAILED' && w.error && <div className="banner banner-danger">{w.error}</div>}
      {w.needsHumanReview && w.reviewReasons.length > 0 && (
        <div className="banner banner-warn"><strong>Please review:</strong><ul className="bullets">{w.reviewReasons.map((r, i) => <li key={i}>{r}</li>)}</ul></div>
      )}

      <AgentProgress workflow={w} />

      <Tabs value={tab} onChange={setTab} tabs={[
        { value: 'overview', label: 'Email' },
        { value: 'brief', label: 'Brief' },
        { value: 'meeting', label: 'Meeting' },
        { value: 'email', label: 'Reply' },
        { value: 'activity', label: 'Agent log & approvals' },
      ]} />

      {tab === 'overview' && <EmailPanel workflow={w} />}
      {tab === 'brief' && (
        <>
          {w.briefMarkdown && <div className="row"><DownloadBrief id={w.id} /></div>}
          <BriefView brief={w.brief} updatedAt={w.completedAt ?? w.updatedAt} />
        </>
      )}
      {tab === 'meeting' && <MeetingPanel workflow={w} onChange={wf.setData} />}
      {tab === 'email' && <DraftPanel workflow={w} onChange={wf.setData} reload={wf.reload} />}
      {tab === 'activity' && <ActivityPanel workflowId={w.id} />}

      <ConfirmDialog open={confirmStop} title="Stop this workflow?" confirmLabel="Stop workflow" danger busy={busyAction === 'STOP'}
        onCancel={() => setConfirmStop(false)} onConfirm={() => run('STOP')}>
        <p>Queued agent work is cancelled. The email and anything already produced are kept.</p>
      </ConfirmDialog>
    </div>
  );
}

function AgentProgress({ workflow }: { workflow: WorkflowView }) {
  const agents = workflow.agents ?? [];
  if (!agents.length) return null;
  return (
    <Card title="Agents" actions={isWorkflowActive(workflow.status) && <span className="muted small"><span className="pulse" /> working on {humanize(workflow.currentStep)}</span>}>
      <ol className="agents">
        {agents.map((a) => (
          <li key={a.agent} className={cx('agent', `agent-${a.status.toLowerCase()}`)}>
            <span className="agent-dot" aria-hidden />
            <div className="agent-body">
              <strong>{a.label}</strong>
              <span className="muted small">
                {a.status === 'RUNNING' ? 'running…' : humanize(a.status)}
                {a.latencyMs > 0 && ` · ${(a.latencyMs / 1000).toFixed(1)} s`}
                {a.model && ` · ${a.model}`}
                {a.attempts > 1 && ` · ${a.attempts} attempts`}
                {a.estimatedCostUsd != null && ` · ${formatUsd(a.estimatedCostUsd)}`}
              </span>
              {a.errorMessage && <span className="error-text small">{a.errorMessage}</span>}
            </div>
          </li>
        ))}
      </ol>
    </Card>
  );
}

function EmailPanel({ workflow }: { workflow: WorkflowView }) {
  const toast = useToast();
  const { can } = useAuth();
  const navigate = useNavigate();
  const [confirmDelete, setConfirmDelete] = useState(false);
  const [deleting, setDeleting] = useState(false);
  const [deleteError, setDeleteError] = useState<unknown>(null);
  const e = workflow.email;
  if (!e) return <EmptyState title="Email not available" />;
  const erase = async () => {
    setDeleting(true);
    setDeleteError(null);
    try {
      await emailsApi.remove(e.id);
      toast('Email and its analysis deleted', 'success');
      navigate('/inbox', { replace: true });
    } catch (err) {
      setDeleteError(err);
      setDeleting(false);
    }
  };
  const download = async (attachmentId: number) => {
    try {
      const res = await emailsApi.attachmentUrl(e.id, attachmentId);
      const safe = safeHttpUrl(res.url);
      if (safe) window.open(safe, '_blank', 'noopener,noreferrer');
    } catch (err) {
      toast(err instanceof Error ? err.message : 'Download failed', 'error');
    }
  };
  return (
    <div className="grid-main">
      <Card title={e.subject || '(no subject)'}>
        <p className="muted small">From {e.senderName ? `${e.senderName} <${e.sender}>` : e.sender} · {formatDateTime(e.receivedAt)}</p>
        <pre className="email-body">{e.body}</pre>
        {e.attachments.length > 0 && (
          <ul className="file-list">
            {e.attachments.map((a) => (
              <li key={a.id}><span>{a.filename}</span><span className="muted small">{formatBytes(a.sizeBytes)}</span>
                <button type="button" className="link-btn" onClick={() => download(a.id)}>Download</button></li>
            ))}
          </ul>
        )}
        {can('PITCH_DELETE') && (
          <div className="row">
            <button type="button" className="link-btn danger-text small" onClick={() => setConfirmDelete(true)}>Delete this email…</button>
          </div>
        )}
        <ConfirmDialog open={confirmDelete} danger title="Delete this email?" confirmLabel="Delete permanently" busy={deleting}
          onCancel={() => setConfirmDelete(false)} onConfirm={erase}>
          <p>The email, its attachments and the agents' work on it (brief, drafts, pending approvals) are erased. Meetings
            already created stay in your calendar. This cannot be undone.</p>
          <InlineError error={deleteError} />
        </ConfirmDialog>
      </Card>
      <Card title="Classification">
        <dl className="overview">
          <div><dt>Type</dt><dd>{humanize(workflow.type)}</dd></div>
          <div><dt>Confidence</dt><dd>{formatPercent(workflow.confidence)}</dd></div>
          <div><dt>Reason</dt><dd>{workflow.classificationReason ?? '—'}</dd></div>
          {e.labels.length > 0 && <div><dt>Labels</dt><dd>{e.labels.join(', ')}</dd></div>}
        </dl>
        {workflow.warnings.length > 0 && <ul className="bullets muted small">{workflow.warnings.map((w, i) => <li key={i}>{w}</li>)}</ul>}
      </Card>
    </div>
  );
}

function DownloadBrief({ id }: { id: number }) {
  const toast = useToast();
  const go = async () => {
    try {
      const res = await request<Response>(workflowsApi.briefMarkdownUrl(id), { raw: true });
      await downloadResponse(res, `brief-${id}.md`);
    } catch (e) {
      toast(e instanceof Error ? e.message : 'Download failed', 'error');
    }
  };
  return <Button size="sm" onClick={go}>Download brief (.md)</Button>;
}

function MeetingPanel({ workflow, onChange }: { workflow: WorkflowView; onChange: (w: WorkflowView) => void }) {
  const { can } = useAuth();
  const toast = useToast();
  const [chosen, setChosen] = useState<Slot | null>(null);
  const [title, setTitle] = useState('');
  const [busy, setBusy] = useState(false);
  const [confirmCancel, setConfirmCancel] = useState(false);
  const [error, setError] = useState<unknown>(null);
  const m = workflow.meeting;

  const schedule = async () => {
    if (!chosen) return;
    setBusy(true);
    setError(null);
    try {
      onChange(await workflowsApi.scheduleMeeting(workflow.id, chosen.start, chosen.end, title.trim() || undefined));
      toast('Meeting approved — creating the calendar event.', 'success');
      setChosen(null);
    } catch (e) {
      setError(e);
    } finally {
      setBusy(false);
    }
  };

  const cancel = async () => {
    setBusy(true);
    try {
      onChange(await workflowsApi.cancelMeeting(workflow.id));
      toast('Cancellation approved.', 'info');
    } catch (e) {
      setError(e);
    } finally {
      setBusy(false);
      setConfirmCancel(false);
    }
  };

  if (m) {
    const join = safeHttpUrl(m.conferenceLink);
    const open = safeHttpUrl(m.htmlLink);
    return (
      <Card title="Scheduled meeting">
        <dl className="overview">
          <div><dt>When</dt><dd>{formatDateTimeIn(m.start, workflow.slotsTimezone)} – {formatDateTime(m.end)}</dd></div>
          <div><dt>Calendar</dt><dd>{m.syncStatus === 'DEMO' ? 'Demo (no invite sent)' : humanize(m.syncStatus)}{m.provider ? ` · ${m.provider}` : ''}</dd></div>
          {join && <div><dt>Video</dt><dd><a href={join} target="_blank" rel="noopener noreferrer">Join link</a></dd></div>}
          {open && <div><dt>Event</dt><dd><a href={open} target="_blank" rel="noopener noreferrer">Open in Google Calendar</a></dd></div>}
        </dl>
        {can('ACTION_APPROVE') && m.syncStatus !== 'CANCELLED' && <Button variant="danger" onClick={() => setConfirmCancel(true)}>Cancel meeting</Button>}
        <InlineError error={error} />
        <ConfirmDialog open={confirmCancel} title="Cancel the meeting?" danger confirmLabel="Cancel meeting" busy={busy}
          onCancel={() => setConfirmCancel(false)} onConfirm={cancel}>
          <p>The event is cancelled in your calendar and attendees are notified by Google.</p>
        </ConfirmDialog>
      </Card>
    );
  }

  const slots = workflow.slots ?? [];
  if (!slots.length) {
    return <EmptyState title="No meeting planned">{workflow.availableActions.includes('PLAN_MEETING') ? 'Use “Plan meeting” to get suggested times from your calendar.' : 'Meetings can be planned once the brief is ready.'}</EmptyState>;
  }
  return (
    <Card title="Suggested times" actions={<span className="muted small">{workflow.slotsTimezone}</span>}>
      <p className="muted small">Checked against your calendar's free/busy. Picking a time is your approval: Pitsch then creates the event and invites the founder.</p>
      <div className="slots">
        {slots.map((s) => (
          <button key={s.start} type="button" className={cx('slot', chosen?.start === s.start && 'slot-on')} onClick={() => setChosen(s)}
            disabled={!can('ACTION_APPROVE')}>
            <strong>{formatDateTimeIn(s.start, workflow.slotsTimezone)}</strong>
            {s.reasons && <span className="muted small">{s.reasons[0]}</span>}
          </button>
        ))}
      </div>
      <InlineError error={error} />
      <ConfirmDialog open={!!chosen} title="Schedule this meeting?" confirmLabel="Approve and schedule" busy={busy}
        onCancel={() => setChosen(null)} onConfirm={schedule}>
        {chosen && <div className="stack">
          <p><strong>{formatDateTimeIn(chosen.start, workflow.slotsTimezone)}</strong></p>
          <TextInput label="Title (optional)" value={title} onChange={(e) => setTitle(e.target.value)} placeholder={`Pitch meeting: ${workflow.companyName ?? ''}`} />
          <p className="muted small">The founder receives a calendar invitation with a video link.</p>
        </div>}
      </ConfirmDialog>
    </Card>
  );
}

function DraftPanel({ workflow, onChange, reload }: { workflow: WorkflowView; onChange: (w: WorkflowView) => void; reload: () => void }) {
  const { can } = useAuth();
  const d = workflow.draft;
  if (!d) {
    return <EmptyState title="No reply drafted">{workflow.availableActions.includes('PLAN_EMAIL_RESPONSE') ? 'Use “Draft reply” to have the Email Agent write a draft you can edit.' : 'Replies can be drafted once the brief is ready.'}</EmptyState>;
  }
  return <DraftEditor key={`${d.id}-${d.status}`} draft={d} canSend={can('ACTION_APPROVE')} canEdit={can('WORKFLOW_RUN')} onSent={onChange} reload={reload} />;
}

function DraftEditor({ draft, canSend, canEdit, onSent, reload }: {
  draft: DraftView; canSend: boolean; canEdit: boolean; onSent: (w: WorkflowView) => void; reload: () => void;
}) {
  const toast = useToast();
  const [subject, setSubject] = useState(draft.subject ?? '');
  const [body, setBody] = useState(draft.body ?? '');
  const [confirm, setConfirm] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<unknown>(null);
  // One key per send intent: a double click or network retry cannot send twice.
  const [sendKey, setSendKey] = useState(() => newIdempotencyKey('send'));
  const editable = draft.status === 'DRAFT' || draft.status === 'FAILED';

  const save = async () => {
    setBusy(true);
    try {
      await draftsApi.update(draft.id, subject, body);
      toast('Draft saved', 'success');
      setSendKey(newIdempotencyKey('send'));
    } catch (e) {
      setError(e);
    } finally {
      setBusy(false);
    }
  };
  const send = async () => {
    setBusy(true);
    setError(null);
    try {
      onSent(await draftsApi.send(draft.id, subject, body, sendKey));
      toast('Approved — sending the email.', 'success');
    } catch (e) {
      setError(e);
      reload();
    } finally {
      setBusy(false);
      setConfirm(false);
    }
  };
  const discard = async () => {
    try {
      onSent(await draftsApi.cancel(draft.id));
    } catch (e) {
      setError(e);
    }
  };

  return (
    <Card title={`Reply to ${draft.recipientName ? `${draft.recipientName} <${draft.recipient}>` : draft.recipient}`}
      actions={<Badge tone={draft.status === 'SENT' ? 'green' : draft.status === 'DEMO_SENT' ? 'purple' : draft.status === 'FAILED' ? 'red' : 'neutral'}>
        {draft.status === 'DEMO_SENT' ? 'Sent (demo, not delivered)' : humanize(draft.status)}</Badge>}>
      {draft.needsHumanReview && draft.reviewReasons.length > 0 && (
        <div className="banner banner-warn"><ul className="bullets">{draft.reviewReasons.map((r, i) => <li key={i}>{r}</li>)}</ul></div>
      )}
      {draft.failureReason && <div className="banner banner-danger">{draft.failureReason}</div>}
      <div className="stack">
        <TextInput label="Subject" value={subject} onChange={(e) => setSubject(e.target.value)} disabled={!editable || !canEdit} />
        <TextArea label="Message" rows={14} value={body} onChange={(e) => setBody(e.target.value)} disabled={!editable || !canEdit} />
        {draft.sentAt && <p className="muted small">Sent {formatDateTime(draft.sentAt)}</p>}
        <InlineError error={error} />
        {editable && (
          <div className="row gap-sm">
            {canSend && <Button variant="primary" onClick={() => setConfirm(true)} disabled={!body.trim()}>Review and send</Button>}
            {canEdit && <Button onClick={save} loading={busy && !confirm}>Save draft</Button>}
            {canEdit && <Button variant="ghost" onClick={discard}>Discard</Button>}
          </div>
        )}
      </div>
      <ConfirmDialog open={confirm} title="Send this email?" confirmLabel="Approve and send" busy={busy}
        onCancel={() => setConfirm(false)} onConfirm={send}>
        <p>To <strong>{draft.recipient}</strong> from your connected Gmail account.</p>
        <p className="muted small">Subject: {subject}</p>
        <p className="muted small">Sending is recorded in the audit log. It is sent exactly once, even if you click twice.</p>
      </ConfirmDialog>
    </Card>
  );
}

function ActivityPanel({ workflowId }: { workflowId: number }) {
  const executions = useAsync(() => workflowsApi.executions(workflowId), [workflowId]);
  const approvals = useAsync(() => workflowsApi.approvals(workflowId), [workflowId]);
  return (
    <div className="stack-lg">
      <Card title="Approvals">
        {approvals.error ? <ErrorState error={approvals.error} /> : !approvals.data ? <Skeleton /> : approvals.data.length === 0 ? <p className="muted">No approvals yet.</p> : (
          <ul className="list">
            {approvals.data.map((a) => (
              <li key={a.id} className="list-row">
                <div><strong>{a.summary}</strong><div className="muted small">{humanize(a.type)} · proposed by {a.proposedBy.toLowerCase()} · {formatDateTime(a.createdAt)}{a.error ? ` · ${a.error}` : ''}</div></div>
                <Badge tone={a.status === 'EXECUTED' ? 'green' : a.status === 'FAILED' || a.status === 'REJECTED' ? 'red' : a.status === 'PENDING' ? 'orange' : 'neutral'}>{humanize(a.status)}</Badge>
              </li>
            ))}
          </ul>
        )}
      </Card>
      <Card title="Agent calls" padded={false}>
        {executions.error ? <ErrorState error={executions.error} /> : !executions.data ? <div className="pad"><Skeleton /></div> : (
          <div className="table-wrap">
            <table className="table">
              <thead><tr><th>Agent</th><th>Status</th><th>Model</th><th>Tokens</th><th>Cost</th><th>Latency</th><th>Started</th></tr></thead>
              <tbody>
                {executions.data.map((x) => (
                  <tr key={x.id}>
                    <td>{humanize(x.agentName)}</td>
                    <td>{humanize(x.status)}{x.errorCategory && <div className="error-text small">{humanize(x.errorCategory)}: {x.errorMessage}</div>}</td>
                    <td className="small mono">{x.model ?? '—'}</td>
                    <td className="small">{x.promptTokens + x.completionTokens}</td>
                    <td className="small">{formatUsd(x.estimatedCostUsd)}</td>
                    <td className="small">{(x.latencyMs / 1000).toFixed(1)} s</td>
                    <td className="small">{formatDateTime(x.startedAt)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </Card>
    </div>
  );
}
