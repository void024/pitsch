import { useMemo, useState, type FormEvent } from 'react';
import { Link } from 'react-router-dom';
import { events as eventsApi } from '../lib/api/endpoints';
import type { CalendarEvent } from '../lib/api/types';
import { useAuth } from '../lib/auth/AuthContext';
import { useAsync } from '../lib/hooks';
import { cx, formatDateTime, formatTime, humanize } from '../lib/format';
import { safeHttpUrl } from '../lib/safeUrl';
import { Badge, Button, Card, ConfirmDialog, ErrorState, InlineError, Modal, PageHeader, Skeleton, TextArea, TextInput } from '../components/ui';

function monthGrid(month: Date): Date[] {
  const first = new Date(month.getFullYear(), month.getMonth(), 1);
  const start = new Date(first);
  start.setDate(first.getDate() - ((first.getDay() + 6) % 7));   // weeks start on Monday
  return Array.from({ length: 42 }, (_, i) => new Date(start.getFullYear(), start.getMonth(), start.getDate() + i));
}

const sameDay = (a: Date, b: Date) => a.getFullYear() === b.getFullYear() && a.getMonth() === b.getMonth() && a.getDate() === b.getDate();
const toLocalInput = (d: Date) => new Date(d.getTime() - d.getTimezoneOffset() * 60000).toISOString().slice(0, 16);

export default function Calendar() {
  const { can } = useAuth();
  const [month, setMonth] = useState(() => new Date(new Date().getFullYear(), new Date().getMonth(), 1));
  const [editing, setEditing] = useState<Partial<CalendarEvent> | null>(null);
  const days = useMemo(() => monthGrid(month), [month]);
  const list = useAsync(() => eventsApi.list(days[0].toISOString(), days[41].toISOString()), [month]);

  const byDay = (d: Date) => (list.data ?? []).filter((e) => sameDay(new Date(e.startTime), d));
  return (
    <div className="stack-lg">
      <PageHeader title="Calendar" subtitle="Pitch meetings created by Pitsch and your own events."
        actions={<>
          <Button size="sm" onClick={() => setMonth(new Date(month.getFullYear(), month.getMonth() - 1, 1))}>‹</Button>
          <strong className="month-label">{month.toLocaleDateString(undefined, { month: 'long', year: 'numeric' })}</strong>
          <Button size="sm" onClick={() => setMonth(new Date(month.getFullYear(), month.getMonth() + 1, 1))}>›</Button>
          {can('CALENDAR_WRITE') && <Button variant="primary" onClick={() => {
            const start = new Date(); start.setMinutes(0, 0, 0); start.setHours(start.getHours() + 1);
            setEditing({ startTime: start.toISOString(), endTime: new Date(start.getTime() + 30 * 60000).toISOString() });
          }}>New event</Button>}
        </>} />
      {list.error ? <ErrorState error={list.error} onRetry={list.reload} /> : !list.data ? <Skeleton lines={6} /> : (
        <Card padded={false}>
          <div className="month">
            {['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun'].map((d) => <div key={d} className="month-head">{d}</div>)}
            {days.map((d) => (
              <div key={d.toISOString()} className={cx('month-cell', d.getMonth() !== month.getMonth() && 'month-out', sameDay(d, new Date()) && 'month-today')}>
                <span className="month-day">{d.getDate()}</span>
                {byDay(d).slice(0, 3).map((e) => (
                  <button key={e.id} type="button" className={cx('event-chip', e.source === 'PITSCH' && 'event-pitch', e.syncStatus?.startsWith('CANCELLED') && 'event-cancelled')}
                    onClick={() => setEditing(e)} title={e.title}>
                    {formatTime(e.startTime)} {e.title}
                  </button>
                ))}
                {byDay(d).length > 3 && <span className="muted small">+{byDay(d).length - 3} more</span>}
              </div>
            ))}
          </div>
        </Card>
      )}
      {editing && <EventModal event={editing} canWrite={can('CALENDAR_WRITE')} onClose={() => setEditing(null)} onSaved={() => { setEditing(null); list.reload(); }} />}
    </div>
  );
}

function EventModal({ event, canWrite, onClose, onSaved }: { event: Partial<CalendarEvent>; canWrite: boolean; onClose: () => void; onSaved: () => void }) {
  const isPitsch = event.source === 'PITSCH';
  const [title, setTitle] = useState(event.title ?? '');
  const [description, setDescription] = useState(event.description ?? '');
  const [location, setLocation] = useState(event.location ?? '');
  const [start, setStart] = useState(event.startTime ? toLocalInput(new Date(event.startTime)) : '');
  const [end, setEnd] = useState(event.endTime ? toLocalInput(new Date(event.endTime)) : '');
  const [error, setError] = useState<unknown>(null);
  const [busy, setBusy] = useState(false);
  const [confirmDelete, setConfirmDelete] = useState(false);
  const editable = canWrite && !isPitsch;

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    setBusy(true);
    setError(null);
    const body = { title, description, location, startTime: new Date(start).toISOString(), endTime: new Date(end).toISOString() };
    try {
      if (event.id) await eventsApi.update(event.id, body); else await eventsApi.create(body);
      onSaved();
    } catch (err) {
      setError(err);
    } finally {
      setBusy(false);
    }
  };
  const remove = async () => {
    if (!event.id) return;
    setBusy(true);
    try {
      await eventsApi.remove(event.id);
      onSaved();
    } catch (err) {
      setError(err);
      setBusy(false);
    }
  };
  const join = safeHttpUrl(event.conferenceLink);
  return (
    <Modal open title={event.id ? (isPitsch ? 'Pitch meeting' : 'Edit event') : 'New event'} onClose={onClose}
      footer={editable ? <>
        {event.id && <Button variant="danger" onClick={() => setConfirmDelete(true)}>Delete</Button>}
        <Button variant="ghost" onClick={onClose}>Cancel</Button>
        <Button variant="primary" type="submit" form="event-form" loading={busy}>Save</Button>
      </> : <Button onClick={onClose}>Close</Button>}>
      {isPitsch ? (
        <div className="stack">
          <h3>{event.title}</h3>
          <p>{formatDateTime(event.startTime)} – {formatTime(event.endTime)}</p>
          <div className="row gap-sm"><Badge tone="purple">Pitsch meeting</Badge>{event.syncStatus && <Badge>{humanize(event.syncStatus)}</Badge>}</div>
          {join && <a href={join} target="_blank" rel="noopener noreferrer">Join video call</a>}
          {event.workflowId && <Link to={`/workflows/${event.workflowId}`}>Manage from the workflow (reschedule / cancel)</Link>}
        </div>
      ) : (
        <form id="event-form" className="stack" onSubmit={submit}>
          <TextInput label="Title" required value={title} onChange={(e) => setTitle(e.target.value)} disabled={!editable} />
          <div className="grid-2">
            <TextInput label="Starts" type="datetime-local" required value={start} onChange={(e) => setStart(e.target.value)} disabled={!editable} />
            <TextInput label="Ends" type="datetime-local" required value={end} onChange={(e) => setEnd(e.target.value)} disabled={!editable} />
          </div>
          <TextInput label="Location" value={location} onChange={(e) => setLocation(e.target.value)} disabled={!editable} />
          <TextArea label="Notes" rows={3} value={description} onChange={(e) => setDescription(e.target.value)} disabled={!editable} />
          <p className="muted small">Personal events block these times when the Calendar Agent suggests meeting slots.</p>
          <InlineError error={error} />
        </form>
      )}
      <ConfirmDialog open={confirmDelete} title="Delete this event?" danger confirmLabel="Delete" busy={busy} onCancel={() => setConfirmDelete(false)} onConfirm={remove}>
        <p>This removes the event from Pitsch.</p>
      </ConfirmDialog>
    </Modal>
  );
}
