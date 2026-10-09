import { useState, type FormEvent } from 'react';
import { Link } from 'react-router-dom';
import { tasks as tasksApi, workspace as workspaceApi } from '../lib/api/endpoints';
import type { Task, TaskPriority, TaskStatus } from '../lib/api/types';
import { useAuth } from '../lib/auth/AuthContext';
import { useAsync } from '../lib/hooks';
import { cx, formatDate } from '../lib/format';
import { Badge, Button, EmptyState, ErrorState, InlineError, Modal, PageHeader, SelectInput, Skeleton, TextArea, TextInput } from '../components/ui';

const COLUMNS: { status: TaskStatus; label: string }[] = [
  { status: 'TODO', label: 'To do' }, { status: 'IN_PROGRESS', label: 'In progress' }, { status: 'DONE', label: 'Done' },
];
const PRIORITY_TONE = { LOW: 'neutral', MEDIUM: 'orange', HIGH: 'red' } as const;

export default function Tasks() {
  const { can, me } = useAuth();
  const [editing, setEditing] = useState<Partial<Task> | null>(null);
  const [mine, setMine] = useState(false);
  const list = useAsync(() => tasksApi.list(), []);
  const members = useAsync(() => (can('MEMBER_READ') ? workspaceApi.members() : Promise.resolve([])), []);
  const tasks = (list.data ?? []).filter((t) => !mine || t.assigneeUserId === me?.user.id);
  const nameOf = (userId: number | null) => members.data?.find((m) => m.userId === userId)?.name;

  const setStatus = async (t: Task, status: TaskStatus) => {
    list.setData((prev) => (prev ?? []).map((x) => (x.id === t.id ? { ...x, status } : x)));
    try {
      await tasksApi.setStatus(t.id, status);
    } finally {
      list.reload();
    }
  };

  return (
    <div className="stack-lg">
      <PageHeader title="Tasks" subtitle="Follow-ups for your team."
        actions={<>
          <label className="checkbox"><input type="checkbox" checked={mine} onChange={(e) => setMine(e.target.checked)} /><span>Assigned to me</span></label>
          {can('TASK_WRITE') && <Button variant="primary" onClick={() => setEditing({ status: 'TODO', priority: 'MEDIUM' })}>New task</Button>}
        </>} />
      {list.error ? <ErrorState error={list.error} onRetry={list.reload} /> : !list.data ? <Skeleton lines={5} /> : list.data.length === 0 ? (
        <EmptyState title="No tasks yet" action={can('TASK_WRITE') && <Button variant="primary" onClick={() => setEditing({ status: 'TODO', priority: 'MEDIUM' })}>Create a task</Button>} />
      ) : (
        <div className="board board-3">
          {COLUMNS.map((col) => (
            <section key={col.status} className="board-col">
              <header><strong>{col.label}</strong><span className="muted small">{tasks.filter((t) => t.status === col.status).length}</span></header>
              <div className="board-cards">
                {tasks.filter((t) => t.status === col.status).map((t) => (
                  <article key={t.id} className={cx('board-card', t.status === 'DONE' && 'faded')}>
                    <button type="button" className="link-btn text-left" onClick={() => setEditing(t)}><strong>{t.title}</strong></button>
                    <div className="row gap-sm wrap">
                      <Badge tone={PRIORITY_TONE[t.priority]}>{t.priority.toLowerCase()}</Badge>
                      {t.dueDate && <span className="muted small">due {formatDate(t.dueDate)}</span>}
                      {t.assigneeUserId && <span className="muted small">{nameOf(t.assigneeUserId) ?? ''}</span>}
                      {t.pitchId && <Link to={`/pitches/${t.pitchId}`} className="small">pitch</Link>}
                    </div>
                    {can('TASK_WRITE') && (
                      <select className="input input-sm" aria-label="Status" value={t.status} onChange={(e) => setStatus(t, e.target.value as TaskStatus)}>
                        {COLUMNS.map((c) => <option key={c.status} value={c.status}>{c.label}</option>)}
                      </select>
                    )}
                  </article>
                ))}
              </div>
            </section>
          ))}
        </div>
      )}
      {editing && <TaskModal task={editing} members={members.data ?? []} canWrite={can('TASK_WRITE')}
        onClose={() => setEditing(null)} onSaved={() => { setEditing(null); list.reload(); }} />}
    </div>
  );
}

function TaskModal({ task, members, canWrite, onClose, onSaved }: {
  task: Partial<Task>; members: { userId: number; name: string }[]; canWrite: boolean; onClose: () => void; onSaved: () => void;
}) {
  const [title, setTitle] = useState(task.title ?? '');
  const [description, setDescription] = useState(task.description ?? '');
  const [priority, setPriority] = useState<TaskPriority>(task.priority ?? 'MEDIUM');
  const [status, setStatus] = useState<TaskStatus>(task.status ?? 'TODO');
  const [dueDate, setDueDate] = useState(task.dueDate ?? '');
  const [assignee, setAssignee] = useState(task.assigneeUserId ? String(task.assigneeUserId) : '');
  const [error, setError] = useState<unknown>(null);
  const [busy, setBusy] = useState(false);

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    setBusy(true);
    const body = { title: title.trim(), description, priority, status, dueDate: dueDate || null,
      assigneeUserId: assignee ? Number(assignee) : null, pitchId: task.pitchId ?? null };
    try {
      if (task.id) await tasksApi.update(task.id, body); else await tasksApi.create(body);
      onSaved();
    } catch (err) {
      setError(err);
    } finally {
      setBusy(false);
    }
  };
  const remove = async () => {
    if (!task.id) return;
    try {
      await tasksApi.remove(task.id);
      onSaved();
    } catch (err) {
      setError(err);
    }
  };
  return (
    <Modal open title={task.id ? 'Edit task' : 'New task'} onClose={onClose}
      footer={canWrite ? <>
        {task.id && <Button variant="danger" onClick={remove}>Delete</Button>}
        <Button variant="ghost" onClick={onClose}>Cancel</Button>
        <Button variant="primary" type="submit" form="task-form" loading={busy} disabled={!title.trim()}>Save</Button>
      </> : <Button onClick={onClose}>Close</Button>}>
      <form id="task-form" className="stack" onSubmit={submit}>
        <fieldset disabled={!canWrite} className="stack">
          <TextInput label="Title" required value={title} onChange={(e) => setTitle(e.target.value)} maxLength={255} />
          <TextArea label="Description" rows={3} value={description} onChange={(e) => setDescription(e.target.value)} />
          <div className="grid-2">
            <SelectInput label="Priority" value={priority} onChange={(e) => setPriority(e.target.value as TaskPriority)}
              options={[{ value: 'LOW', label: 'Low' }, { value: 'MEDIUM', label: 'Medium' }, { value: 'HIGH', label: 'High' }]} />
            <SelectInput label="Status" value={status} onChange={(e) => setStatus(e.target.value as TaskStatus)}
              options={COLUMNS.map((c) => ({ value: c.status, label: c.label }))} />
            <TextInput label="Due date" type="date" value={dueDate} onChange={(e) => setDueDate(e.target.value)} />
            <SelectInput label="Assignee" value={assignee} onChange={(e) => setAssignee(e.target.value)}
              options={[{ value: '', label: 'Unassigned' }, ...members.map((m) => ({ value: String(m.userId), label: m.name }))]} />
          </div>
        </fieldset>
        <InlineError error={error} />
      </form>
    </Modal>
  );
}
