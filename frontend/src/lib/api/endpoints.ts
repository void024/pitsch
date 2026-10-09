import { request } from './client';
import type {
  ApprovalView, AuditView, AuthResponse, BillingView, CalendarEvent, Dashboard, EmailView, ExecutionView,
  IntegrationName, IntegrationStatus, Invitation, InvitationLookup, Me, Member, NotificationPreference,
  NotificationView, Page, Pitch, PitchDetail, PitchInput, PlanView, Role, SessionInfo, SheetConfig, SheetPreview,
  Task, TaskPriority, TaskStatus, TimelineEntry, UserSettings, WorkflowAction, WorkflowView, WorkspaceSettings,
  DraftView, DealStage,
} from './types';

type Query = Record<string, string | number | boolean | null | undefined>;

/* ---------------- auth ---------------- */
export const auth = {
  login: (email: string, password: string) =>
    request<AuthResponse>('/v1/auth/login', { method: 'POST', body: { email, password }, skipRefresh: true }),
  signup: (input: { name: string; email: string; password: string; workspaceName?: string; timezone?: string; inviteToken?: string }) =>
    request<AuthResponse>('/v1/auth/signup', { method: 'POST', body: input, skipRefresh: true }),
  logout: () => request<void>('/v1/auth/logout', { method: 'POST', skipRefresh: true }),
  me: () => request<Me>('/v1/me'),
  verifyEmail: (token: string) => request<void>('/v1/auth/verify-email', { method: 'POST', body: { token }, skipRefresh: true }),
  resendVerification: () => request<void>('/v1/auth/resend-verification', { method: 'POST' }),
  forgotPassword: (email: string) =>
    request<{ message: string }>('/v1/auth/forgot-password', { method: 'POST', body: { email }, skipRefresh: true }),
  resetPassword: (token: string, password: string) =>
    request<void>('/v1/auth/reset-password', { method: 'POST', body: { token, password }, skipRefresh: true }),
  changePassword: (currentPassword: string, newPassword: string) =>
    request<void>('/v1/auth/change-password', { method: 'POST', body: { currentPassword, newPassword } }),
  sessions: () => request<SessionInfo[]>('/v1/auth/sessions'),
  revokeSession: (id: string) => request<void>(`/v1/auth/sessions/${encodeURIComponent(id)}`, { method: 'DELETE' }),
  switchWorkspace: (organizationId: number) =>
    request<Me>('/v1/auth/switch-workspace', { method: 'POST', body: { organizationId } }),
  googleStart: (returnPath?: string) =>
    request<{ authorizationUrl: string }>('/v1/auth/google/start', { query: { returnPath }, skipRefresh: true }),
};

/* ---------------- profile ---------------- */
export const profile = {
  update: (input: { name?: string; email?: string; currentPassword?: string }) =>
    request<unknown>('/v1/users/me', { method: 'PUT', body: input }),
  settings: () => request<UserSettings>('/v1/users/me/settings'),
  saveSettings: (s: UserSettings) => request<UserSettings>('/v1/users/me/settings', { method: 'PUT', body: s }),
};

/* ---------------- workspace ---------------- */
export const workspace = {
  create: (name: string, timezone?: string) => request<Me>('/v1/workspaces', { method: 'POST', body: { name, timezone } }),
  get: () => request<WorkspaceSettings>('/v1/workspace'),
  update: (patch: Partial<Omit<WorkspaceSettings, 'id' | 'slug' | 'createdAt' | 'onboardingCompleted'>> & { clearDataRetention?: boolean }) =>
    request<WorkspaceSettings>('/v1/workspace', { method: 'PATCH', body: patch }),
  completeOnboarding: () => request<WorkspaceSettings>('/v1/workspace/onboarding/complete', { method: 'POST' }),
  members: () => request<Member[]>('/v1/workspace/members'),
  changeRole: (membershipId: number, role: Role) =>
    request<Member>(`/v1/workspace/members/${membershipId}`, { method: 'PATCH', body: { role } }),
  removeMember: (membershipId: number) => request<void>(`/v1/workspace/members/${membershipId}`, { method: 'DELETE' }),
  invitations: () => request<Invitation[]>('/v1/workspace/invitations'),
  invite: (email: string, role: Role) => request<Invitation>('/v1/workspace/invitations', { method: 'POST', body: { email, role } }),
  revokeInvitation: (id: number) => request<void>(`/v1/workspace/invitations/${id}`, { method: 'DELETE' }),
  lookupInvitation: (token: string) =>
    request<InvitationLookup>(`/v1/invitations/${encodeURIComponent(token)}`, { skipRefresh: true }),
  acceptInvitation: (token: string) =>
    request<Me>(`/v1/invitations/${encodeURIComponent(token)}/accept`, { method: 'POST' }),
};

/* ---------------- dashboard / activity ---------------- */
export const dashboard = {
  get: () => request<Dashboard>('/v1/dashboard'),
};

/* ---------------- pitches ---------------- */
export interface PitchQuery extends Query {
  q?: string;
  dealStage?: string;
  status?: string;
  sector?: string;
  ownerUserId?: number;
  riskLevel?: string;
  minConfidence?: number;
  hasFollowUp?: boolean;
  source?: string;
  createdFrom?: string;
  createdTo?: string;
  page?: number;
  size?: number;
  sort?: string;
}

export const pitches = {
  list: (q: PitchQuery, signal?: AbortSignal) => request<Page<Pitch>>('/v1/pitches', { query: q, signal }),
  stages: () => request<{ dealStage: DealStage; count: number }[]>('/v1/pitches/stages'),
  get: (id: number) => request<PitchDetail>(`/v1/pitches/${id}`),
  timeline: (id: number) => request<TimelineEntry[]>(`/v1/pitches/${id}/timeline`),
  create: (input: PitchInput, idempotencyKey?: string) =>
    request<Pitch>('/v1/pitches', { method: 'POST', body: input, idempotencyKey }),
  update: (id: number, input: PitchInput) => request<Pitch>(`/v1/pitches/${id}`, { method: 'PATCH', body: input }),
  remove: (id: number) => request<void>(`/v1/pitches/${id}`, { method: 'DELETE' }),
};

/* ---------------- emails ---------------- */
export const emails = {
  list: (page: number, size = 25) => request<Page<EmailView>>('/v1/emails', { query: { page, size } }),
  get: (id: number) => request<{ email: EmailView; workflowId: number | null }>(`/v1/emails/${id}`),
  import: (form: FormData, idempotencyKey?: string) =>
    request<{ emailId: number; workflowId: number | null; duplicate: boolean; skippedFiles: string[]; workflow: WorkflowView | null }>(
      '/v1/emails/import', { method: 'POST', body: form, idempotencyKey }),
  attachmentUrl: (emailId: number, attachmentId: number) =>
    request<{ url: string; filename: string; expiresAt: string }>(`/v1/emails/${emailId}/attachments/${attachmentId}/download`),
  /** Erases the email, its attachments and its workflows (data-subject requests, mistaken imports). */
  remove: (id: number) => request<void>(`/v1/emails/${id}`, { method: 'DELETE' }),
  gmailSync: () => request<{ queued: boolean }>('/v1/integrations/gmail/sync', { method: 'POST' }),
};

/* ---------------- workflows ---------------- */
export interface WorkflowQuery extends Query {
  status?: string;
  type?: string;
  needsReview?: boolean;
  needsAction?: boolean;
  pitchId?: number;
  q?: string;
  page?: number;
  size?: number;
  sort?: string;
}

export const workflows = {
  list: (q: WorkflowQuery, signal?: AbortSignal) => request<Page<WorkflowView>>('/v1/workflows', { query: q, signal }),
  get: (id: number, signal?: AbortSignal) => request<WorkflowView>(`/v1/workflows/${id}`, { signal }),
  action: (id: number, action: WorkflowAction, extra: { durationMinutes?: number; purpose?: string; instructions?: string; questions?: string[] } = {}) =>
    request<WorkflowView>(`/v1/workflows/${id}/actions`, { method: 'POST', body: { action, ...extra } }),
  executions: (id: number) => request<ExecutionView[]>(`/v1/workflows/${id}/executions`),
  approvals: (id: number) => request<ApprovalView[]>(`/v1/workflows/${id}/approvals`),
  scheduleMeeting: (workflowId: number, start: string, end: string, title?: string) =>
    request<WorkflowView>('/v1/calendar/meetings', { method: 'POST', body: { workflowId, start, end, title } }),
  cancelMeeting: (workflowId: number) => request<WorkflowView>(`/v1/workflows/${workflowId}/meeting/cancel`, { method: 'POST' }),
  briefMarkdownUrl: (id: number) => `/v1/workflows/${id}/brief.md`,
};

export const drafts = {
  update: (id: number, subject: string, body: string) =>
    request<DraftView>(`/v1/drafts/${id}`, { method: 'PUT', body: { subject, body } }),
  send: (id: number, subject: string, body: string, idempotencyKey: string) =>
    request<WorkflowView>(`/v1/drafts/${id}/send`, { method: 'POST', body: { subject, body }, idempotencyKey }),
  cancel: (id: number) => request<WorkflowView>(`/v1/drafts/${id}/cancel`, { method: 'POST' }),
};

/* ---------------- approvals ---------------- */
export const approvals = {
  list: (status: string | undefined, page: number) =>
    request<Page<ApprovalView>>('/v1/approvals', { query: { status, page, size: 25 } }),
  approve: (id: number) => request<ApprovalView>(`/v1/approvals/${id}/approve`, { method: 'POST' }),
  reject: (id: number, reason: string) => request<ApprovalView>(`/v1/approvals/${id}/reject`, { method: 'POST', body: { reason } }),
};

/* ---------------- calendar / tasks ---------------- */
export const events = {
  list: (from?: string, to?: string) => request<CalendarEvent[]>('/v1/events', { query: { from, to } }),
  create: (input: { title: string; description?: string; location?: string; startTime: string; endTime: string }) =>
    request<CalendarEvent>('/v1/events', { method: 'POST', body: input }),
  update: (id: number, input: { title?: string; description?: string; location?: string; startTime?: string; endTime?: string }) =>
    request<CalendarEvent>(`/v1/events/${id}`, { method: 'PUT', body: input }),
  remove: (id: number) => request<void>(`/v1/events/${id}`, { method: 'DELETE' }),
};

export interface TaskInput {
  title: string;
  description?: string;
  status?: TaskStatus;
  priority?: TaskPriority;
  dueDate?: string | null;
  pitchId?: number | null;
  assigneeUserId?: number | null;
}

export const tasks = {
  list: (pitchId?: number) => request<Task[]>('/v1/tasks', { query: { pitchId } }),
  create: (input: TaskInput) => request<Task>('/v1/tasks', { method: 'POST', body: input }),
  update: (id: number, input: TaskInput) => request<Task>(`/v1/tasks/${id}`, { method: 'PUT', body: input }),
  setStatus: (id: number, status: TaskStatus) => request<Task>(`/v1/tasks/${id}/status`, { method: 'PATCH', body: { status } }),
  remove: (id: number) => request<void>(`/v1/tasks/${id}`, { method: 'DELETE' }),
};

/* ---------------- notifications ---------------- */
export const notifications = {
  list: (page: number, unread?: boolean) =>
    request<Page<NotificationView>>('/v1/notifications', { query: { page, size: 25, unread } }),
  unreadCount: () => request<{ count: number }>('/v1/notifications/unread-count'),
  markRead: (id: number) => request<unknown>(`/v1/notifications/${id}/read`, { method: 'PATCH' }),
  markAllRead: () => request<unknown>('/v1/notifications/read-all', { method: 'PATCH' }),
  preferences: () => request<NotificationPreference[]>('/v1/notifications/preferences'),
  savePreferences: (preferences: NotificationPreference[]) =>
    request<NotificationPreference[]>('/v1/notifications/preferences', { method: 'PUT', body: { preferences } }),
  test: () => request<unknown>('/v1/notifications/test', { method: 'POST' }),
};

/* ---------------- integrations ---------------- */
export const integrations = {
  list: () => request<IntegrationStatus[]>('/v1/integrations'),
  connect: (names: IntegrationName[], returnPath: string) =>
    request<{ authorizationUrl: string }>('/v1/integrations/connect', { method: 'POST', body: { integrations: names, returnPath } }),
  disconnect: (name: IntegrationName) => request<void>(`/v1/integrations/${name}`, { method: 'DELETE' }),
  sheetConfig: () => request<SheetConfig | undefined>('/v1/integrations/sheets/config'),
  sheetFields: () => request<string[]>('/v1/integrations/sheets/fields'),
  sheetPreview: (spreadsheet: string, worksheet?: string, headerRow = 1) =>
    request<SheetPreview>('/v1/integrations/sheets/preview', { query: { spreadsheet, worksheet, headerRow } }),
  saveSheetConfig: (input: { spreadsheet: string; worksheetTitle: string; headerRow: number; mapping: { field: string; column: string }[] }) =>
    request<SheetConfig>('/v1/integrations/sheets/config', { method: 'PUT', body: input }),
  removeSheetConfig: () => request<void>('/v1/integrations/sheets/config', { method: 'DELETE' }),
  syncAllPitches: () => request<{ queued: number }>('/v1/integrations/sheets/sync-all', { method: 'POST' }),
};

/* ---------------- billing / audit / privacy ---------------- */
export const billing = {
  get: () => request<BillingView>('/v1/billing'),
  plans: () => request<PlanView[]>('/v1/billing/plans'),
  checkout: (planCode: string) => request<{ url: string }>('/v1/billing/checkout', { method: 'POST', body: { planCode } }),
  portal: () => request<{ url: string }>('/v1/billing/portal', { method: 'POST' }),
};

export const audit = {
  list: (q: { page?: number; action?: string; resourceType?: string; actorUserId?: number }) =>
    request<Page<AuditView>>('/v1/audit-events', { query: { ...q, size: 50 } }),
};

export const privacy = {
  exportWorkspace: () => request<Response>('/v1/workspace/export', { raw: true }),
  exportAccount: () => request<Response>('/v1/me/export', { raw: true }),
  deleteWorkspace: (confirmName: string, password: string) =>
    request<{ status: string }>('/v1/workspace/delete', { method: 'POST', body: { confirmName, password } }),
  deleteAccount: (password: string) =>
    request<{ status: string }>('/v1/me/delete', { method: 'POST', body: { confirm: 'DELETE', password } }),
};
