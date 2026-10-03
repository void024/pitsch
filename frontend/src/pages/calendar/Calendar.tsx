import { useEffect, useMemo, useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import { PageContainer } from '../../components/layout/PageContainer';
import { Button } from '../../components/ui/Button';
import { Card } from '../../components/ui/Card';
import { ConfirmDialog } from '../../components/ui/Modal';
import { EmptyState } from '../../components/ui/EmptyState';
import { ErrorState } from '../../components/ui/ErrorState';
import { Icon } from '../../components/ui/Icon';
import { Loading } from '../../components/ui/Loading';
import { calendarService } from '../../services/calendarService';
import { getErrorMessage } from '../../services/api';
import { useFetch } from '../../hooks/useFetch';
import type { CalendarEvent } from '../../types';
import { dateKey, formatDate, formatTime, upcomingEvents } from '../../utils/format';
import { EventFormModal } from './EventFormModal';
import { MonthGrid } from './MonthGrid';

interface EventRowProps {
  event: CalendarEvent;
  onEdit: (event: CalendarEvent) => void;
  onDelete: (event: CalendarEvent) => void;
  showDate?: boolean;
}

function EventRow({ event, onEdit, onDelete, showDate }: EventRowProps) {
  return (
    <li className="list-item">
      <div className="date-chip" aria-hidden="true">
        <strong>{new Date(event.startTime).getDate()}</strong>
        <small>{new Date(event.startTime).toLocaleDateString(undefined, { month: 'short' })}</small>
      </div>
      <div className="list-main">
        <p className="list-title">{event.title}</p>
        <small className="muted">
          {showDate && `${formatDate(event.startTime)} · `}
          {formatTime(event.startTime)} – {formatTime(event.endTime)}
          {event.location ? ` · ${event.location}` : ''}
        </small>
        {event.description && <small className="muted clamp">{event.description}</small>}
      </div>
      <div className="row-actions">
        <button type="button" className="icon-btn" aria-label={`Edit ${event.title}`} onClick={() => onEdit(event)}>
          <Icon name="edit" size={16} />
        </button>
        <button
          type="button"
          className="icon-btn icon-btn-danger"
          aria-label={`Delete ${event.title}`}
          onClick={() => onDelete(event)}
        >
          <Icon name="trash" size={16} />
        </button>
      </div>
    </li>
  );
}

export default function Calendar() {
  const { data, loading, error, reload } = useFetch(calendarService.list);
  const [params, setParams] = useSearchParams();

  const [month, setMonth] = useState(() => new Date());
  const [selected, setSelected] = useState(() => new Date());
  const [creating, setCreating] = useState(() => params.get('new') === '1');
  const [editing, setEditing] = useState<CalendarEvent | null>(null);
  const [deleting, setDeleting] = useState<CalendarEvent | null>(null);
  const [deleteBusy, setDeleteBusy] = useState(false);
  const [deleteError, setDeleteError] = useState<string | null>(null);

  useEffect(() => {
    if (params.get('new')) {
      const next = new URLSearchParams(params);
      next.delete('new');
      setParams(next, { replace: true });
    }
  }, [params, setParams]);

  const events = useMemo(() => data ?? [], [data]);
  const selectedKey = dateKey(selected);
  const dayEvents = useMemo(
    () =>
      events
        .filter((e) => dateKey(new Date(e.startTime)) === selectedKey)
        .sort((a, b) => new Date(a.startTime).getTime() - new Date(b.startTime).getTime()),
    [events, selectedKey],
  );
  const upcoming = useMemo(() => upcomingEvents(events).slice(0, 6), [events]);

  const confirmDelete = async () => {
    if (!deleting) return;
    setDeleteBusy(true);
    setDeleteError(null);
    try {
      await calendarService.remove(deleting.id);
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
      title="Calendar"
      description="Plan your schedule and keep track of upcoming events."
      actions={
        <Button icon="plus" onClick={() => setCreating(true)}>
          New event
        </Button>
      }
    >
      {loading ? (
        <Loading label="Loading events…" />
      ) : error ? (
        <ErrorState message={error} onRetry={reload} />
      ) : (
        <div className="calendar-layout">
          <Card>
            <MonthGrid
              month={month}
              events={events}
              selected={selected}
              onSelect={(date) => {
                setSelected(date);
                setMonth(date);
              }}
              onMonthChange={setMonth}
            />
          </Card>

          <div className="stack">
            <Card title={formatDate(dateKey(selected))}>
              {dayEvents.length === 0 ? (
                <EmptyState
                  icon="calendar"
                  title="No events this day"
                  description="Nothing scheduled for the selected date."
                  action={
                    <Button size="sm" variant="secondary" icon="plus" onClick={() => setCreating(true)}>
                      Add event
                    </Button>
                  }
                />
              ) : (
                <ul className="list">
                  {dayEvents.map((event) => (
                    <EventRow key={event.id} event={event} onEdit={setEditing} onDelete={setDeleting} />
                  ))}
                </ul>
              )}
            </Card>

            <Card title="Upcoming events">
              {upcoming.length === 0 ? (
                <EmptyState icon="clock" title="No upcoming events" description="Create an event to see it here." />
              ) : (
                <ul className="list">
                  {upcoming.map((event) => (
                    <EventRow key={event.id} event={event} onEdit={setEditing} onDelete={setDeleting} showDate />
                  ))}
                </ul>
              )}
            </Card>
          </div>
        </div>
      )}

      {(creating || editing) && (
        <EventFormModal
          key={editing?.id ?? 'new'}
          event={editing}
          initialDate={selected}
          onClose={() => {
            setCreating(false);
            setEditing(null);
          }}
          onSaved={handleSaved}
        />
      )}

      {deleting && (
        <ConfirmDialog
          title="Delete event"
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