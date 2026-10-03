import type {
  ActivityItem,
  BadgeTone,
  CalendarEvent,
  TaskPriority,
  TaskStatus,
  Provenance,
  TimeFormat,
  UserSettings,
  WorkflowAction,
  WorkflowStatus,
} from '../types';

/* ---------- Class names ---------- */
export function cx(...parts: Array<string | false | null | undefined>): string {
  return parts.filter(Boolean).join(' ');
}

/* ---------- Labels / tones ---------- */
export const STATUS_LABEL: Record<TaskStatus, string> = {
  TODO: 'To do',
  IN_PROGRESS: 'In progress',
  DONE: 'Done',
};
export const STATUS_TONE: Record<TaskStatus, BadgeTone> = {
  TODO: 'neutral',
  IN_PROGRESS: 'blue',
  DONE: 'green',
};
export const PRIORITY_LABEL: Record<TaskPriority, string> = {
  LOW: 'Low',
  MEDIUM: 'Medium',
  HIGH: 'High',
};
export const PRIORITY_TONE: Record<TaskPriority, BadgeTone> = {
  LOW: 'neutral',
  MEDIUM: 'orange',
  HIGH: 'red',
};
export const WORKFLOW_LABEL: Record<WorkflowStatus, string> = {
  RECEIVED: 'Received',
  CLASSIFYING: 'Analyzing email…',
  NOT_PITCH: 'Not a pitch',
  AWAITING_USER: 'Needs your decision',
  PROCESSING: 'Agents working',
  WAITING_FOR_APPROVAL: 'Ready for review',
  COMPLETED: 'Completed',
  STOPPED: 'Stopped',
  FAILED: 'Failed',
};
export const WORKFLOW_TONE: Record<WorkflowStatus, BadgeTone> = {
  RECEIVED: 'neutral',
  CLASSIFYING: 'blue',
  NOT_PITCH: 'neutral',
  AWAITING_USER: 'orange',
  PROCESSING: 'blue',
  WAITING_FOR_APPROVAL: 'purple',
  COMPLETED: 'green',
  STOPPED: 'neutral',
  FAILED: 'red',
};
/** Statuses where the backend is still working (the UI polls). */
export function isWorkflowActive(status: WorkflowStatus): boolean {
  return status === 'RECEIVED' || status === 'CLASSIFYING' || status === 'PROCESSING';
}
export const WORKFLOW_TYPE_LABEL: Record<string, string> = {
  NEW_PITCH: 'New pitch',
  FOLLOW_UP: 'Follow-up',
  NOT_PITCH: 'Not a pitch',
};
export const ACTION_LABEL: Record<WorkflowAction, string> = {
  COMPLETE_WORKFLOW: 'Handle pitch',
  STOP: 'Stop',
  PLAN_MEETING: 'Plan meeting',
  PLAN_EMAIL_RESPONSE: 'Draft email',
  RETRY: 'Retry',
};
export const CLAIM_STATUS_LABEL: Record<string, string> = {
  VERIFIED: 'Verified',
  PARTIALLY_VERIFIED: 'Partially verified',
  UNVERIFIED: 'Unverified',
  CONTRADICTED: 'Contradicted',
  NOT_FOUND: 'Not found',
  NOT_CHECKED: 'Not checked',
};
export const CLAIM_STATUS_TONE: Record<string, BadgeTone> = {
  VERIFIED: 'green',
  PARTIALLY_VERIFIED: 'orange',
  UNVERIFIED: 'neutral',
  CONTRADICTED: 'red',
  NOT_FOUND: 'neutral',
  NOT_CHECKED: 'neutral',
};
export const PROVENANCE_LABEL: Record<Provenance, string> = {
  PITCH: 'Pitch',
  COMPANY: 'Company',
  EXTERNAL: 'External',
  AI_INFERENCE: 'AI inference',
};
export const PROVENANCE_TONE: Record<Provenance, BadgeTone> = {
  PITCH: 'purple',
  COMPANY: 'blue',
  EXTERNAL: 'green',
  AI_INFERENCE: 'orange',
};

/* ---------- User preferences (mirrored locally so formatting works everywhere) ---------- */
const TIME_FORMAT_KEY = 'pitsch_time_format';

export function applyPreferences(settings: Pick<UserSettings, 'timeFormat' | 'compactMode'>): void {
  try {
    localStorage.setItem(TIME_FORMAT_KEY, settings.timeFormat);
  } catch {
    // Storage may be unavailable; formatting falls back to the 12h default.
  }
  document.documentElement.dataset.compact = String(settings.compactMode);
}

function getTimeFormat(): TimeFormat {
  try {
    return localStorage.getItem(TIME_FORMAT_KEY) === '24h' ? '24h' : '12h';
  } catch {
    return '12h';
  }
}

/* ---------- Dates ---------- */
function pad(n: number): string {
  return String(n).padStart(2, '0');
}

/** Parses either a plain YYYY-MM-DD date (as local time) or a full ISO string. */
export function parseDate(value: string): Date {
  if (/^\d{4}-\d{2}-\d{2}$/.test(value)) {
    const [y, m, d] = value.split('-').map(Number);
    return new Date(y, m - 1, d);
  }
  return new Date(value);
}

export function dateKey(d: Date): string {
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
}

export function todayKey(): string {
  return dateKey(new Date());
}

export function formatDate(value: string): string {
  const d = parseDate(value);
  if (Number.isNaN(d.getTime())) return '';
  return d.toLocaleDateString(undefined, { day: 'numeric', month: 'short', year: 'numeric' });
}

export function formatTime(value: string): string {
  const d = parseDate(value);
  if (Number.isNaN(d.getTime())) return '';
  return d.toLocaleTimeString(undefined, {
    hour: 'numeric',
    minute: '2-digit',
    hour12: getTimeFormat() === '12h',
  });
}

export function formatDateTime(value: string): string {
  return `${formatDate(value)}, ${formatTime(value)}`;
}

/** Value for <input type="datetime-local"> from an ISO string. */
export function toDateTimeLocal(iso: string): string {
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return '';
  return `${dateKey(d)}T${pad(d.getHours())}:${pad(d.getMinutes())}`;
}

export function fromDateTimeLocal(value: string): string {
  return new Date(value).toISOString();
}

/** datetime-local value for a given day at a given hour. */
export function dayAt(date: Date, hour: number): string {
  return `${dateKey(date)}T${pad(hour)}:00`;
}

export function isOverdue(dueDate: string | null | undefined, status: TaskStatus): boolean {
  if (!dueDate || status === 'DONE') return false;
  const today = parseDate(todayKey());
  return parseDate(dueDate).getTime() < today.getTime();
}

export function greeting(): string {
  const hour = new Date().getHours();
  if (hour < 12) return 'Good morning';
  if (hour < 18) return 'Good afternoon';
  return 'Good evening';
}

export function timeAgo(iso: string): string {
  const diff = Date.now() - new Date(iso).getTime();
  if (Number.isNaN(diff)) return '';
  const minutes = Math.floor(diff / 60000);
  if (minutes < 1) return 'Just now';
  if (minutes < 60) return `${minutes}m ago`;
  const hours = Math.floor(minutes / 60);
  if (hours < 24) return `${hours}h ago`;
  const days = Math.floor(hours / 24);
  if (days < 7) return `${days}d ago`;
  return formatDate(iso);
}

export function initials(name: string): string {
  const parts = name.trim().split(/\s+/).filter(Boolean);
  if (parts.length === 0) return '?';
  return parts
    .slice(0, 2)
    .map((p) => p[0].toUpperCase())
    .join('');
}

/* ---------- Collections ---------- */
export function sortNewest<T extends { createdAt: string }>(items: T[]): T[] {
  return [...items].sort((a, b) => new Date(b.createdAt).getTime() - new Date(a.createdAt).getTime());
}

export function sortActivity(items: ActivityItem[]): ActivityItem[] {
  return sortNewest(items);
}

export function upcomingEvents(events: CalendarEvent[]): CalendarEvent[] {
  const now = Date.now();
  return events
    .filter((e) => new Date(e.endTime).getTime() >= now)
    .sort((a, b) => new Date(a.startTime).getTime() - new Date(b.startTime).getTime());
}

export function formatBytes(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${Math.round(bytes / 1024)} KB`;
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
}

/** "Wed, 7 Oct · 2:00 PM – 2:30 PM" in the viewer's locale/time format. */
export function formatSlot(start: string, end: string): string {
  const s = parseDate(start);
  const day = s.toLocaleDateString(undefined, { weekday: 'short', day: 'numeric', month: 'short' });
  return `${day} · ${formatTime(start)} – ${formatTime(end)}`;
}
