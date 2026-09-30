import { useState } from 'react';
import type { FormEvent } from 'react';
import { Modal } from '../../components/ui/Modal';
import { Button } from '../../components/ui/Button';
import { Input, Select, Textarea } from '../../components/ui/Input';
import { taskService } from '../../services/taskService';
import { getErrorMessage } from '../../services/api';
import type { Task, TaskPriority, TaskStatus } from '../../types';
import { PRIORITY_LABEL, STATUS_LABEL } from '../../utils/format';

interface TaskFormModalProps {
  task: Task | null;
  onClose: () => void;
  onSaved: () => void;
}

export function TaskFormModal({ task, onClose, onSaved }: TaskFormModalProps) {
  const [title, setTitle] = useState(task?.title ?? '');
  const [description, setDescription] = useState(task?.description ?? '');
  const [status, setStatus] = useState<TaskStatus>(task?.status ?? 'TODO');
  const [priority, setPriority] = useState<TaskPriority>(task?.priority ?? 'MEDIUM');
  const [dueDate, setDueDate] = useState(task?.dueDate ?? '');
  const [titleError, setTitleError] = useState<string | undefined>();
  const [formError, setFormError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);

  const handleSubmit = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    setFormError(null);
    if (!title.trim()) {
      setTitleError('Title is required.');
      return;
    }
    setTitleError(undefined);

    const payload = {
      title: title.trim(),
      description: description.trim(),
      status,
      priority,
      dueDate: dueDate || null,
    };

    setSaving(true);
    try {
      if (task) await taskService.update(task.id, payload);
      else await taskService.create(payload);
      onSaved();
    } catch (err: unknown) {
      setFormError(getErrorMessage(err));
    } finally {
      setSaving(false);
    }
  };

  return (
    <Modal
      title={task ? 'Edit task' : 'New task'}
      onClose={onClose}
      footer={
        <>
          <Button variant="ghost" onClick={onClose} disabled={saving}>
            Cancel
          </Button>
          <Button type="submit" form="task-form" loading={saving}>
            {task ? 'Save changes' : 'Create task'}
          </Button>
        </>
      }
    >
      <form id="task-form" className="form" onSubmit={handleSubmit} noValidate>
        <Input label="Title" value={title} onChange={(e) => setTitle(e.target.value)} error={titleError} autoFocus />
        <Textarea
          label="Description"
          rows={3}
          value={description}
          onChange={(e) => setDescription(e.target.value)}
        />
        <div className="form-row">
          <Select label="Status" value={status} onChange={(e) => setStatus(e.target.value as TaskStatus)}>
            {(Object.keys(STATUS_LABEL) as TaskStatus[]).map((s) => (
              <option key={s} value={s}>
                {STATUS_LABEL[s]}
              </option>
            ))}
          </Select>
          <Select label="Priority" value={priority} onChange={(e) => setPriority(e.target.value as TaskPriority)}>
            {(Object.keys(PRIORITY_LABEL) as TaskPriority[]).map((p) => (
              <option key={p} value={p}>
                {PRIORITY_LABEL[p]}
              </option>
            ))}
          </Select>
        </div>
        <Input label="Due date" type="date" value={dueDate} onChange={(e) => setDueDate(e.target.value)} />
        {formError && (
          <p className="form-error" role="alert">
            {formError}
          </p>
        )}
      </form>
    </Modal>
  );
}