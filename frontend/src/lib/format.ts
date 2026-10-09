import type { DealStage, Provenance, Role, WorkflowAction, WorkflowStatus } from './api/types';

export type Tone = 'neutral' | 'purple' | 'blue' | 'green' | 'orange' | 'red';

export function cx(...parts: Array<string | false | null | undefined>): string {
  return parts.filter(Boolean).join(' ');
}

const dateFmt = new Intl.DateTimeFormat(undefined, { day: 'numeric', month: 'short', year: 'numeric' });
const dateTimeFmt = new Intl.DateTimeFormat(undefined, { day: 'numeric', month: 'short', hour: 'numeric', minute: '2-digit' });
const timeFmt = new Intl.DateTimeFormat(undefined, { hour: 'numeric', minute: '2-digit' });

function parse(value: string | null | undefined): Date | null {
  if (!value) return null;
  const d = new Date(value);
  return Number.isNaN(d.getTime()) ? null : d;
}

export function formatDate(value: string | null | undefined): string {
  const d = parse(value);
  return d ? dateFmt.format(d) : '—';
}

export function formatDateTime(value: string | null | undefined): string {
  const d = parse(value);
  return d ? dateTimeFmt.format(d) : '—';
}

export function formatTime(value: string | null | undefined): string {
  const d = parse(value);
  return d ? timeFmt.format(d) : '—';
}

export function formatDateTimeIn(value: string, timeZone: string | null | undefined): string {
  const d = parse(value);
  if (!d) return '—';
  try {
    return new Intl.DateTimeFormat(undefined, {
      weekday: 'short', day: 'numeric', month: 'short', hour: 'numeric', minute: '2-digit', timeZone: timeZone ?? undefined,
      timeZoneName: 'short',
    }).format(d);
  } catch {
    return dateTimeFmt.format(d);
  }
}

export function timeAgo(value: string | null | undefined, now: Date = new Date()): string {
  const d = parse(value);
  if (!d) return '—';
  const s = Math.round((now.getTime() - d.getTime()) / 1000);
  if (s < 45) return 'just now';
  const m = Math.round(s / 60);
  if (m < 60) return `${m} min ago`;
  const h = Math.round(m / 60);
  if (h < 24) return `${h} h ago`;
  const days = Math.round(h / 24);
  if (days < 7) return `${days} d ago`;
  return formatDate(value);
}

export function formatBytes(n: number): string {
  if (n < 1024) return `${n} B`;
  if (n < 1024 * 1024) return `${(n / 1024).toFixed(0)} KB`;
  return `${(n / 1024 / 1024).toFixed(1)} MB`;
}

export function formatUsd(n: number | null | undefined): string {
  if (n === null || n === undefined) return '—';
  return n < 0.01 && n > 0 ? `$${n.toFixed(4)}` : `$${n.toFixed(2)}`;
}

export function formatPercent(n: number | null | undefined): string {
  return n === null || n === undefined ? '—' : `${Math.round(n * 100)}%`;
}

export function humanize(value: string | null | undefined): string {
  if (!value) return '—';
  const s = value.replace(/_/g, ' ').toLowerCase();
  return s.charAt(0).toUpperCase() + s.slice(1);
}

export function initials(name: string | null | undefined): string {
  const parts = (name ?? '').trim().split(/\s+/).filter(Boolean);
  return ((parts[0]?.[0] ?? '?') + (parts.length > 1 ? parts[parts.length - 1][0] : '')).toUpperCase();
}

/* ---------------- labels and tones ---------------- */

export const WORKFLOW_LABEL: Record<WorkflowStatus, string> = {
  RECEIVED: 'Received',
  CLASSIFYING: 'Reading email',
  NOT_PITCH: 'Not a pitch',
  AWAITING_USER: 'Needs your decision',
  PROCESSING: 'Agents working',
  WAITING_FOR_APPROVAL: 'Ready for review',
  COMPLETED: 'Completed',
  STOPPED: 'Stopped',
  FAILED: 'Needs attention',
};

export const WORKFLOW_TONE: Record<WorkflowStatus, Tone> = {
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

export function isWorkflowActive(status: WorkflowStatus | undefined | null): boolean {
  return status === 'RECEIVED' || status === 'CLASSIFYING' || status === 'PROCESSING';
}

export const ACTION_LABEL: Record<WorkflowAction, string> = {
  COMPLETE_WORKFLOW: 'Handle pitch',
  STOP: 'Stop',
  PLAN_MEETING: 'Plan meeting',
  PLAN_EMAIL_RESPONSE: 'Draft reply',
  RETRY: 'Retry',
};

export const STAGE_LABEL: Record<DealStage, string> = {
  NEW: 'New', SCREENING: 'Screening', DILIGENCE: 'Diligence', MEETING: 'Meeting', DECISION: 'Decision',
  INVESTED: 'Invested', PASSED: 'Passed', ARCHIVED: 'Archived',
};

export const STAGE_TONE: Record<DealStage, Tone> = {
  NEW: 'blue', SCREENING: 'purple', DILIGENCE: 'purple', MEETING: 'orange', DECISION: 'orange',
  INVESTED: 'green', PASSED: 'neutral', ARCHIVED: 'neutral',
};

/** Claim assessment (product vocabulary). Older briefs carry only `status`; map it. */
const STATUS_TO_ASSESSMENT: Record<string, string> = {
  VERIFIED: 'SUPPORTED', PARTIALLY_VERIFIED: 'PARTIALLY_SUPPORTED', UNVERIFIED: 'UNSUPPORTED',
  CONTRADICTED: 'CONTRADICTED', NOT_FOUND: 'NOT_FOUND', NOT_CHECKED: 'NOT_CHECKED',
};

export function assessmentOf(row: { assessment?: string; status: string }): string {
  return row.assessment ?? STATUS_TO_ASSESSMENT[row.status] ?? row.status;
}

export const ASSESSMENT_LABEL: Record<string, string> = {
  SUPPORTED: 'Supported', PARTIALLY_SUPPORTED: 'Partially supported', UNSUPPORTED: 'Unsupported',
  CONTRADICTED: 'Contradicted', NOT_FOUND: 'Not found', NOT_CHECKED: 'Not checked',
};

export const ASSESSMENT_TONE: Record<string, Tone> = {
  SUPPORTED: 'green', PARTIALLY_SUPPORTED: 'blue', UNSUPPORTED: 'orange', CONTRADICTED: 'red',
  NOT_FOUND: 'neutral', NOT_CHECKED: 'neutral',
};

export const PROVENANCE_LABEL: Record<Provenance, string> = {
  PITCH: 'Pitch', COMPANY: 'Company source', EXTERNAL: 'External source', AI_INFERENCE: 'AI inference',
};

export const PROVENANCE_TONE: Record<Provenance, Tone> = {
  PITCH: 'purple', COMPANY: 'blue', EXTERNAL: 'green', AI_INFERENCE: 'orange',
};

export const ROLE_LABEL: Record<Role, string> = {
  OWNER: 'Owner', ADMIN: 'Admin', INVESTOR: 'Investor', ANALYST: 'Analyst', MEMBER: 'Member',
};

export const RISK_TONE: Record<string, Tone> = { LOW: 'green', MEDIUM: 'orange', HIGH: 'red' };
