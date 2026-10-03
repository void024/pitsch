import { useEffect, useMemo, useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import { PageContainer } from '../../components/layout/PageContainer';
import { Badge } from '../../components/ui/Badge';
import { Button } from '../../components/ui/Button';
import { Card } from '../../components/ui/Card';
import { EmptyState } from '../../components/ui/EmptyState';
import { ErrorState } from '../../components/ui/ErrorState';
import { Icon } from '../../components/ui/Icon';
import { Input, Select } from '../../components/ui/Input';
import { Loading } from '../../components/ui/Loading';
import { ConfirmDialog } from '../../components/ui/Modal';
import { taskService } from '../../services/taskService';
import { getErrorMessage } from '../../services/api';
import { useFetch } from '../../hooks/useFetch';
import type { ID, Task, TaskPriority, TaskStatus } from '../../types';
import {
  PRIORITY_LABEL,
  PRIORITY_TONE,
  STATUS_LABEL,
  STATUS_TONE,
  cx,
  formatDate,
  isOverdue,
  sortNewest,
} from '../../utils/format';
import { TaskFormModal } from './TaskFormModal';

export default function Tasks() {
  const { data, loading, error, reload } = useFetch(taskService.list);
  const [params, setParams] = useSearchParams();
  const query = params.get('q') ?? '';

  const [status, setStatus] = useState<TaskStatus | 'ALL'>('ALL');
  const [priority, setPriority] = useState<TaskPriority | 'ALL'>('ALL');
  const [creating, setCreating] = useState(() => params.get('new') === '1');
  const [editing, setEditing] = useState<Task | null>(null);
  const [deleting, setDeleting] = useState<Task | null>(null);
  const [deleteBusy, setDeleteBusy] = useState(false);
  const [deleteError, setDeleteError] = useState<string | null>(null);
  const [busyId, setBusyId] = useState<ID | null>(null);
  const [actionError, setActionError] = useState<string | null>(null);

  useEffect(() => {
    if (params.get('new')) {
      const next = new URLSearchParams(params);
      next.delete('new');
      setParams(next, { replace: true });
    }
  }, [params, setParams]);

  const setQuery = (value: string) => {
    const next = new URLSearchParams(params);
    if (value) next.set('q', value);
    else next.delete('q');
    setParams(next, { replace: true });
  };

  const tasks = useMemo(() => sortNewest(data ?? []), [data]);
  const filtered = useMemo(() => {
    const needle = query.trim().toLowerCase();
    return tasks.filter((task) => {
      if (status !== 'ALL' && task.status !== status) return false;
      if (priority !== 'ALL' && task.priority !== priority) return false;
      if (!needle) return true;
      return (
        task.title.toLowerCase().includes(needle) || (task.description ?? '').toLowerCase().includes(needle)
      );
    });
  }, [tasks, query, status, priority]);

  const filtersActive = Boolean(query.trim()) || status !== 'ALL' || priority !== 'ALL';

  const clearFilters = () => {
    setQuery('');
    setStatus('ALL');
    setPriority('ALL');
  };

  const toggleComplete = async (task: Task) => {
    setBusyId(task.id);
    setActionError(null);
    try {
      await taskService.setStatus(task.id, task.status === 'DONE' ? 'TODO' : 'DONE');
      reload();
    } catch (err: unknown) {
      setActionError(getErrorMessage(err));
    } finally {
      setBusyId(null);
    }
  };

  const confirmDelete = async () => {
    if (!deleting) return;
    setDeleteBusy(true);
    setDeleteError(null);
    try {
      await taskService.remove(deleting.id);
      setDeleting(null);
      reload();
    } catch (err: unknown) {
      setDeleteError(getErrorMessage(err));
    } finally {
      setDeleteBusy(false);
    }
  };

  const handleSaved = () => {
    setCreating(false);
    setEditing(null);
    reload();
  };

  return (
    <PageContainer
      title="Tasks"
      description="Create, track and complete your work."
      actions={
        <Button icon="plus" onClick={() => setCreating(true)}>
          New task
        </Button>
      }
    >
      <Card>
        <div className="toolbar">
          <Input
            label="Search tasks"
            hideLabel
            type="search"
            placeholder="Search tasks…"
            value={query}
            onChange={(e) => setQuery(e.target.value)}
          />
          <Select
            label="Filter by status"
            hideLabel
            value={status}
            onChange={(e) => setStatus(e.target.value as TaskStatus | 'ALL')}
          >
            <option value="ALL">All statuses</option>
            {(Object.keys(STATUS_LABEL) as TaskStatus[]).map((s) => (
              <option key={s} value={s}>
                {STATUS_LABEL[s]}
              </option>
            ))}
          </Select>
          <Select
            label="Filter by priority"
            hideLabel
            value={priority}
            onChange={(e) => setPriority(e.target.value as TaskPriority | 'ALL')}
          >
            <option value="ALL">All priorities</option>
            {(Object.keys(PRIORITY_LABEL) as TaskPriority[]).map((p) => (
              <option key={p} value={p}>
                {PRIORITY_LABEL[p]}
              </option>
            ))}
          </Select>
        </div>

        {actionError && (
          <p className="form-error" role="alert">
            {actionError}
          </p>
        )}

        {loading ? (
          <Loading label="Loading tasks…" />
        ) : error ? (
          <ErrorState message={error} onRetry={reload} />
        ) : filtered.length === 0 ? (
          filtersActive ? (
            <EmptyState
              icon="search"
              title="No matching tasks"
              description="Try a different search or clear your filters."
              action={
                <Button variant="secondary" size="sm" onClick={clearFilters}>
                  Clear filters
                </Button>
              }
            />
          ) : (
            <EmptyState
              icon="tasks"
              title="No tasks yet"
              description="Create your first task to get started."
              action={
                <Button size="sm" icon="plus" onClick={() => setCreating(true)}>
                  New task
                </Button>
              }
            />
          )
        ) : (
          <ul className="task-list">
            {filtered.map((task) => {
              const done = task.status === 'DONE';
              const overdue = isOverdue(task.dueDate, task.status);
              return (
                <li key={task.id} className={cx('task-row', done && 'task-done')}>
                  <button
                    type="button"
                    className={cx('check-btn', done && 'checked')}
                    aria-label={done ? `Mark “${task.title}” as not done` : `Mark “${task.title}” as complete`}
                    aria-pressed={done}
                    disabled={busyId === task.id}
                    onClick={() => void toggleComplete(task)}
                  >
                    {done && <Icon name="check" size={14} />}
                  </button>
                  <div className="task-main">
                    <p className="task-title">{task.title}</p>
                    {task.description && <p className="muted clamp">{task.description}</p>}
                    <div className="task-meta">
                      <Badge tone={STATUS_TONE[task.status]}>{STATUS_LABEL[task.status]}</Badge>
                      <Badge tone={PRIORITY_TONE[task.priority]}>{PRIORITY_LABEL[task.priority]}</Badge>
                      {task.dueDate && (
                        <span className={cx('due', overdue && 'due-overdue')}>
                          <Icon name="clock" size={14} />
                          {overdue ? 'Overdue · ' : ''}
                          {formatDate(task.dueDate)}
                        </span>
                      )}
                    </div>
                  </div>
                  <div className="row-actions">
                    <button
                      type="button"
                      className="icon-btn"
                      aria-label={`Edit ${task.title}`}
                      onClick={() => setEditing(task)}
                    >
                      <Icon name="edit" size={16} />
                    </button>
                    <button
                      type="button"
                      className="icon-btn icon-btn-danger"
                      aria-label={`Delete ${task.title}`}
                      onClick={() => setDeleting(task)}
                    >
                      <Icon name="trash" size={16} />
                    </button>
                  </div>
                </li>
              );
            })}
          </ul>
        )}
      </Card>

      {(creating || editing) && (
        <TaskFormModal
          key={editing?.id ?? 'new'}
          task={editing}
          onClose={() => {
            setCreating(false);
            setEditing(null);
          }}
          onSaved={handleSaved}
        />
      )}

      {deleting && (
        <ConfirmDialog
          title="Delete task"
          message={`Delete “${deleting.title}”? This can't be undone.`}
          busy={deleteBusy}
          error={deleteError}
          onConfirm={() => void confirmDelete()}
          onCancel={() => {
            setDeleting(null);
            setDeleteError(null);
          }}
        />
      )}
    </PageContainer>
  );
}