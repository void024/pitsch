import { useMemo } from 'react';
import { Icon } from '../../components/ui/Icon';
import type { CalendarEvent } from '../../types';
import { cx, dateKey, todayKey } from '../../utils/format';

const WEEKDAYS = ['Sun', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat'];

interface MonthGridProps {
  month: Date;
  events: CalendarEvent[];
  selected?: Date;
  onSelect: (date: Date) => void;
  onMonthChange: (month: Date) => void;
}

export function MonthGrid({ month, events, selected, onSelect, onMonthChange }: MonthGridProps) {
  const counts = useMemo(() => {
    const map = new Map<string, number>();
    for (const event of events) {
      const key = dateKey(new Date(event.startTime));
      map.set(key, (map.get(key) ?? 0) + 1);
    }
    return map;
  }, [events]);

  const first = new Date(month.getFullYear(), month.getMonth(), 1);
  const cells = Array.from(
    { length: 42 },
    (_, i) => new Date(first.getFullYear(), first.getMonth(), 1 - first.getDay() + i),
  );
  const today = todayKey();
  const selectedKey = selected ? dateKey(selected) : null;

  return (
    <div className="month-grid">
      <div className="month-header">
        <h3>{first.toLocaleDateString(undefined, { month: 'long', year: 'numeric' })}</h3>
        <div className="month-nav">
          <button
            type="button"
            className="icon-btn"
            aria-label="Previous month"
            onClick={() => onMonthChange(new Date(first.getFullYear(), first.getMonth() - 1, 1))}
          >
            <Icon name="chevronLeft" />
          </button>
          <button type="button" className="btn btn-ghost btn-sm" onClick={() => onMonthChange(new Date())}>
            Today
          </button>
          <button
            type="button"
            className="icon-btn"
            aria-label="Next month"
            onClick={() => onMonthChange(new Date(first.getFullYear(), first.getMonth() + 1, 1))}
          >
            <Icon name="chevronRight" />
          </button>
        </div>
      </div>

      <div className="weekdays" aria-hidden="true">
        {WEEKDAYS.map((d) => (
          <span key={d}>{d}</span>
        ))}
      </div>

      <div className="days">
        {cells.map((cell) => {
          const key = dateKey(cell);
          const count = counts.get(key) ?? 0;
          return (
            <button
              type="button"
              key={key}
              className={cx(
                'day',
                cell.getMonth() !== first.getMonth() && 'day-outside',
                key === today && 'day-today',
                key === selectedKey && 'day-selected',
              )}
              aria-label={`${cell.toLocaleDateString(undefined, { dateStyle: 'full' })}${
                count ? `, ${count} event${count > 1 ? 's' : ''}` : ''
              }`}
              onClick={() => onSelect(cell)}
            >
              <span>{cell.getDate()}</span>
              {count > 0 && (
                <span className="day-dots" aria-hidden="true">
                  {Array.from({ length: Math.min(count, 3) }, (_, i) => (
                    <i key={i} />
                  ))}
                </span>
              )}
            </button>
          );
        })}
      </div>
    </div>
  );
}