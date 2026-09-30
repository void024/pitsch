import { useState } from 'react';
import type { FormEvent } from 'react';
import { Modal } from '../../components/ui/Modal';
import { Button } from '../../components/ui/Button';
import { Input, Textarea } from '../../components/ui/Input';
import { calendarService } from '../../services/calendarService';
import { getErrorMessage } from '../../services/api';
import type { CalendarEvent } from '../../types';
import { dayAt, fromDateTimeLocal, toDateTimeLocal } from '../../utils/format';

interface EventFormModalProps {
  event: CalendarEvent | null;
  initialDate: Date;
  onClose: () => void;
  onSaved: () => void;
}

interface Errors {
  title?: string;
  startTime?: string;
  endTime?: string;
}

export function EventFormModal({ event, initialDate, onClose, onSaved }: EventFormModalProps) {
  const [title, setTitle] = useState(event?.title ?? '');
  const [description, setDescription] = useState(event?.description ?? '');
  const [location, setLocation] = useState(event?.location ?? '');
  const [startTime, setStartTime] = useState(event ? toDateTimeLocal(event.startTime) : dayAt(initialDate, 9));
  const [endTime, setEndTime] = useState(event ? toDateTimeLocal(event.endTime) : dayAt(initialDate, 10));
  const [errors, setErrors] = useState<Errors>({});
  const [formError, setFormError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);

  const handleSubmit = async (e: FormEvent<HTMLFormElement>) => {
    e.preventDefault();
    const next: Errors = {};
    if (!title.trim()) next.title = 'Title is required.';
    if (!startTime) next.startTime = 'Start time is required.';
    if (!endTime) next.endTime = 'End time is required.';
    if (startTime && endTime && new Date(endTime) <= new Date(startTime)) {
      next.endTime = 'End must be after the start.';
    }
    setErrors(next);
    setFormError(null);
    if (Object.keys(next).length > 0) return;

    const payload = {
      title: title.trim(),
      description: description.trim(),
      location: location.trim(),
      startTime: fromDateTimeLocal(startTime),
      endTime: fromDateTimeLocal(endTime),
    };

    setSaving(true);
    try {
      if (event) await calendarService.update(event.id, payload);
      else await calendarService.create(payload);
      onSaved();
    } catch (err: unknown) {
      setFormError(getErrorMessage(err));
    } finally {
      setSaving(false);
    }
  };

  return (
    <Modal
      title={event ? 'Edit event' : 'New event'}
      onClose={onClose}
      footer={
        <>
          <Button variant="ghost" onClick={onClose} disabled={saving}>
            Cancel
          </Button>
          <Button type="submit" form="event-form" loading={saving}>
            {event ? 'Save changes' : 'Create event'}
          </Button>
        </>
      }
    >
      <form id="event-form" className="form" onSubmit={handleSubmit} noValidate>
        <Input label="Title" value={title} onChange={(e) => setTitle(e.target.value)} error={errors.title} autoFocus />
        <div className="form-row">
          <Input
            label="Starts"
            type="datetime-local"
            value={startTime}
            onChange={(e) => setStartTime(e.target.value)}
            error={errors.startTime}
          />
          <Input
            label="Ends"
            type="datetime-local"
            value={endTime}
            onChange={(e) => setEndTime(e.target.value)}
            error={errors.endTime}
          />
        </div>
        <Input label="Location" value={location} onChange={(e) => setLocation(e.target.value)} />
        <Textarea
          label="Description"
          rows={3}
          value={description}
          onChange={(e) => setDescription(e.target.value)}
        />
        {formError && (
          <p className="form-error" role="alert">
            {formError}
          </p>
        )}
      </form>
    </Modal>
  );
}