import { useState } from 'react';
import { Badge } from '../../components/ui/Badge';
import { Button } from '../../components/ui/Button';
import { Card } from '../../components/ui/Card';
import { Icon } from '../../components/ui/Icon';
import { Input, Textarea } from '../../components/ui/Input';
import { getErrorMessage } from '../../services/api';
import { workflowService } from '../../services/workflowService';
import type { AgentStatus, EmailDraft, EmailView, MeetingSlot, Workflow } from '../../types';
import { cx, formatBytes, formatDateTime, formatSlot } from '../../utils/format';

/* ---------------- Agent progress ---------------- */

const AGENT_ICON: Record<AgentStatus['status'], 'check' | 'clock' | 'alert' | 'close'> = {
  COMPLETED: 'check',
  RUNNING: 'clock',
  PENDING: 'clock',
  FAILED: 'alert',
  SKIPPED: 'close',
};

export function AgentProgress({ agents }: { agents: AgentStatus[] }) {
  if (agents.length === 0) return null;
  return (
    <Card title="Agent progress">
      <ul className="agent-steps">
        {agents.map((a) => (
          <li key={a.agent} className={cx('agent-step', `agent-${a.status.toLowerCase()}`)}>
            <span className="agent-icon" aria-hidden="true">
              {a.status === 'RUNNING' ? <span className="spinner spinner-xs" /> : <Icon name={AGENT_ICON[a.status]} size={14} />}
            </span>
            <div className="agent-text">
              <p>{a.label}</p>
              <small className="muted">
                {a.status === 'COMPLETED'
                  ? `${(a.latencyMs / 1000).toFixed(1)}s · ${a.promptTokens + a.completionTokens} tokens`
                  : a.status === 'FAILED'
                    ? a.errorMessage ?? 'Failed'
                    : a.status.toLowerCase()}
              </small>
            </div>
          </li>
        ))}
      </ul>
    </Card>
  );
}

/* ---------------- Original email ---------------- */

export function EmailCard({ email }: { email: EmailView }) {
  return (
    <Card title="Email">
      <dl className="kv">
        <dt>From</dt>
        <dd>{email.senderName ? `${email.senderName} <${email.sender}>` : email.sender}</dd>
        <dt>Subject</dt>
        <dd>{email.subject || '—'}</dd>
        <dt>Received</dt>
        <dd>{formatDateTime(email.receivedAt)}</dd>
      </dl>
      {email.attachments.length > 0 && (
        <ul className="attachments">
          {email.attachments.map((a) => (
            <li key={a.id}>
              <Icon name="pin" size={14} /> {a.filename} <small className="muted">{formatBytes(a.sizeBytes)}</small>
            </li>
          ))}
        </ul>
      )}
      {email.labels.length > 0 && (
        <div className="label-chips" aria-label="Labels">
          {email.labels.map((l) => (
            <span key={l} className="label-chip">{l}</span>
          ))}
        </div>
      )}
      <details className="email-body">
        <summary>Show message</summary>
        <pre>{email.body || '(empty)'}</pre>
      </details>
    </Card>
  );
}

/* ---------------- Meeting slots ---------------- */

interface MeetingCardProps {
  workflow: Workflow;
  onUpdated: (w: Workflow) => void;
}

export function MeetingCard({ workflow, onUpdated }: MeetingCardProps) {
  const slots: MeetingSlot[] = workflow.slots ?? [];
  const [selected, setSelected] = useState(0);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const canSchedule = workflow.status === 'WAITING_FOR_APPROVAL' || workflow.status === 'AWAITING_USER';

  if (workflow.meeting) {
    return (
      <Card title="Meeting">
        <p className="meeting-done">
          <Icon name="calendar" size={16} /> {formatSlot(workflow.meeting.start, workflow.meeting.end)}
        </p>
        <p className="muted small">Added to your Pitsch calendar with the founder invited.</p>
      </Card>
    );
  }
  if (!workflow.slots) return null; // no meeting planned yet
  if (slots.length === 0) {
    return (
      <Card title="Meeting">
        <p className="muted">No free slot matched your calendar and preferences. Try planning again later.</p>
      </Card>
    );
  }

  const schedule = async () => {
    const slot = slots[selected];
    setBusy(true);
    setError(null);
    try {
      onUpdated(await workflowService.scheduleMeeting(workflow.id, slot.start, slot.end));
    } catch (err: unknown) {
      setError(getErrorMessage(err));
    } finally {
      setBusy(false);
    }
  };

  return (
    <Card title="Suggested meeting times">
      <ul className="slot-list" role="radiogroup" aria-label="Meeting slots">
        {slots.map((slot, i) => (
          <li key={slot.start}>
            <label className={cx('slot-option', selected === i && 'selected')}>
              <input type="radio" name="slot" checked={selected === i} onChange={() => setSelected(i)} />
              <span>
                <strong>{formatSlot(slot.start, slot.end)}</strong>
                <small className="muted"> · score {Math.round(slot.score)}</small>
                <ul className="slot-reasons">
                  {slot.reasons.slice(0, 4).map((r) => (
                    <li key={r}>{r}</li>
                  ))}
                </ul>
              </span>
            </label>
          </li>
        ))}
      </ul>
      {error && <p className="form-error" role="alert">{error}</p>}
      <div className="form-actions">
        <Button icon="calendar" onClick={() => void schedule()} loading={busy} disabled={!canSchedule}>
          Schedule this meeting
        </Button>
      </div>
      <p className="muted small">Nothing is booked until you click Schedule.</p>
    </Card>
  );
}

/* ---------------- Email draft ---------------- */

interface DraftCardProps {
  draft: EmailDraft;
  onUpdated: (w: Workflow) => void;
}

export function DraftCard({ draft, onUpdated }: DraftCardProps) {
  const [subject, setSubject] = useState(draft.subject);
  const [body, setBody] = useState(draft.body);
  const [busy, setBusy] = useState<'send' | 'cancel' | null>(null);
  const [error, setError] = useState<string | null>(null);

  if (draft.status !== 'DRAFT') {
    return (
      <Card title={draft.status === 'SENT' ? 'Email sent' : 'Draft cancelled'}>
        <p className="muted small">
          {draft.status === 'SENT' ? `To ${draft.recipient}${draft.sentAt ? ` · ${formatDateTime(draft.sentAt)}` : ''}` : 'This draft was discarded.'}
        </p>
        <details className="email-body">
          <summary>{draft.subject}</summary>
          <pre>{draft.body}</pre>
        </details>
      </Card>
    );
  }

  const act = async (kind: 'send' | 'cancel') => {
    setBusy(kind);
    setError(null);
    try {
      onUpdated(kind === 'send' ? await workflowService.sendDraft(draft.id, subject, body) : await workflowService.cancelDraft(draft.id));
    } catch (err: unknown) {
      setError(getErrorMessage(err));
      setBusy(null);
    }
  };

  return (
    <Card title="Email draft" action={<Badge tone="purple">{draft.purpose.replaceAll('_', ' ').toLowerCase()}</Badge>}>
      <p className="muted small">To {draft.recipientName ? `${draft.recipientName} <${draft.recipient}>` : draft.recipient}</p>
      {draft.reviewReasons.length > 0 && (
        <ul className="review-list">
          {draft.reviewReasons.map((r) => (
            <li key={r}><Icon name="alert" size={14} /> {r}</li>
          ))}
        </ul>
      )}
      <div className="form">
        <Input label="Subject" value={subject} onChange={(e) => setSubject(e.target.value)} />
        <Textarea label="Message" rows={12} value={body} onChange={(e) => setBody(e.target.value)} />
      </div>
      {error && <p className="form-error" role="alert">{error}</p>}
      <div className="form-actions">
        <Button variant="ghost" onClick={() => void act('cancel')} loading={busy === 'cancel'} disabled={busy !== null}>
          Cancel
        </Button>
        <Button icon="send" onClick={() => void act('send')} loading={busy === 'send'} disabled={busy !== null || !body.trim()}>
          Send
        </Button>
      </div>
      <p className="muted small">Sending needs your click — Pitsch never emails founders on its own.</p>
    </Card>
  );
}
