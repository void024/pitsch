export type ID = number | string;

/* ---------- Shared UI ---------- */
export type BadgeTone = 'neutral' | 'purple' | 'blue' | 'green' | 'orange' | 'red';

/* ---------- Auth / user ---------- */
export interface User {
  id: ID;
  name: string;
  email: string;
  avatarUrl?: string | null;
}

export interface LoginRequest {
  email: string;
  password: string;
}

export interface AuthResponse {
  token: string;
  user: User;
}

export interface UpdateProfileRequest {
  name: string;
  email: string;
}

export type TimeFormat = '12h' | '24h';

export interface NotificationSettings {
  email: boolean;
  taskReminders: boolean;
  eventReminders: boolean;
  workflowUpdates: boolean;
}

export interface UserSettings {
  timeFormat: TimeFormat;
  compactMode: boolean;
  notifications: NotificationSettings;
  /** Used by the AI agents: meeting time zone and email signature. */
  timezone?: string | null;
  firmName?: string | null;
  investorTitle?: string | null;
}

/* ---------- Tasks ---------- */
export type TaskStatus = 'TODO' | 'IN_PROGRESS' | 'DONE';
export type TaskPriority = 'LOW' | 'MEDIUM' | 'HIGH';

export interface Task {
  id: ID;
  title: string;
  description?: string | null;
  status: TaskStatus;
  priority: TaskPriority;
  /** ISO date (YYYY-MM-DD) */
  dueDate?: string | null;
  createdAt: string;
  updatedAt?: string | null;
}

export interface TaskInput {
  title: string;
  description: string;
  status: TaskStatus;
  priority: TaskPriority;
  dueDate: string | null;
}

/* ---------- Calendar ---------- */
export interface CalendarEvent {
  id: ID;
  title: string;
  description?: string | null;
  location?: string | null;
  /** ISO date-time */
  startTime: string;
  /** ISO date-time */
  endTime: string;
}

export interface CalendarEventInput {
  title: string;
  description: string;
  location: string;
  startTime: string;
  endTime: string;
}

/* ---------- Pitch workflows (backend contract) ---------- */
export type WorkflowStatus =
  | 'RECEIVED'
  | 'CLASSIFYING'
  | 'NOT_PITCH'
  | 'AWAITING_USER'
  | 'PROCESSING'
  | 'WAITING_FOR_APPROVAL'
  | 'COMPLETED'
  | 'STOPPED'
  | 'FAILED';

export type WorkflowType = 'NEW_PITCH' | 'FOLLOW_UP' | 'NOT_PITCH';

/** Exact action values the backend accepts — never send UI text. */
export type WorkflowAction = 'COMPLETE_WORKFLOW' | 'STOP' | 'PLAN_MEETING' | 'PLAN_EMAIL_RESPONSE' | 'RETRY';

export type Provenance = 'PITCH' | 'COMPANY' | 'EXTERNAL' | 'AI_INFERENCE';

export type AgentRunStatus = 'RUNNING' | 'COMPLETED' | 'FAILED' | 'PENDING' | 'SKIPPED';

export interface AgentStatus {
  agent: string;
  label: string;
  status: AgentRunStatus;
  errorMessage?: string | null;
  startedAt?: string | null;
  completedAt?: string | null;
  latencyMs: number;
  promptTokens: number;
  completionTokens: number;
}

export interface EmailAttachmentInfo {
  id: ID;
  filename: string;
  mimeType: string;
  sizeBytes: number;
}

export interface EmailView {
  id: ID;
  sender: string;
  senderName?: string | null;
  subject?: string | null;
  body?: string | null;
  receivedAt: string;
  threadId?: string | null;
  attachments: EmailAttachmentInfo[];
  labels: string[];
  isPitch?: boolean | null;
  isFollowUp?: boolean | null;
  category?: string | null;
}

export interface MeetingSlot {
  rank: number;
  start: string;
  end: string;
  founderLocalStart?: string | null;
  score: number;
  reasons: string[];
}

export interface EmailDraft {
  id: ID;
  recipient: string;
  recipientName?: string | null;
  subject: string;
  body: string;
  purpose: string;
  status: 'DRAFT' | 'SENT' | 'CANCELLED';
  needsHumanReview: boolean;
  reviewReasons: string[];
  createdAt: string;
  sentAt?: string | null;
}

export interface Finding {
  statement: string;
  citations: string[];
  provenance: Provenance;
}

export interface EvidenceLink {
  evidenceId: string;
  sourceTitle: string;
  sourceUrl: string;
  publishedAt?: string | null;
  sourceType: string;
}

export interface ClaimRow {
  claimId: string;
  claim: string;
  category?: string | null;
  status: string;
  independentlyVerified: boolean;
  finding?: string | null;
  evidenceOutdated: boolean;
  supporting: EvidenceLink[];
  contradicting: EvidenceLink[];
}

export interface Brief {
  companyOverview: { field: string; value: string; provenance: Provenance; source: string }[];
  executiveSummary: Finding[];
  claimsMatrix: ClaimRow[];
  tractionMetrics: { metric: string; value: string; period?: string | null }[];
  market: Finding[];
  competition: Finding[];
  competitors: { name: string; description?: string | null }[];
  founders: Finding[];
  fundingHistory: Finding[];
  risks: { risk: string; category: string; citations: string[] }[];
  openQuestions: { question: string; reason?: string | null; origin: string }[];
  sources: {
    sourceId: string;
    title: string;
    url: string;
    sourceType: string;
    publishedAt?: string | null;
    possiblyOutdated: boolean;
  }[];
}

export interface Workflow {
  id: ID;
  workflowId: ID;
  type?: WorkflowType | null;
  status: WorkflowStatus;
  currentStep?: string | null;
  recommendedAction?: string | null;
  availableActions: WorkflowAction[];
  /** One-line description (email subject). */
  prompt: string;
  /** Short summary (brief executive summary or classification reason). */
  result?: string | null;
  error?: string | null;
  createdAt: string;
  updatedAt?: string | null;
  completedAt?: string | null;
  pitchId?: ID | null;
  companyName?: string | null;
  emailId?: ID | null;
  sender?: string | null;
  senderName?: string | null;
  confidence?: number | null;
  classificationReason?: string | null;
  needsHumanReview: boolean;
  reviewReasons: string[];
  warnings: string[];
  agents?: AgentStatus[];
  email?: EmailView;
  brief?: Brief;
  briefMarkdown?: string;
  slots?: MeetingSlot[];
  slotsTimezone?: string;
  meeting?: { start: string; end: string; eventId?: ID | null };
  draft?: EmailDraft;
}

export interface ActionRequest {
  action: WorkflowAction;
  purpose?: string;
  instructions?: string;
  durationMinutes?: number;
}

export interface SubmitEmailRequest {
  sender: string;
  senderName?: string;
  subject: string;
  body: string;
  threadId?: string;
  attachments?: { filename: string; mimeType: string; contentBase64: string }[];
}

export interface SubmitEmailResponse {
  emailId: ID;
  workflowId: ID;
  workflow: Workflow;
}

export interface AvailabilityResponse {
  workflowId: ID;
  timezone: string;
  slots: MeetingSlot[];
}

export interface AppNotification {
  id: ID;
  type: string;
  title: string;
  message: string;
  workflowId?: ID | null;
  read: boolean;
  createdAt: string;
}

/* ---------- Activity ---------- */
export type ActivityType = 'TASK' | 'EVENT' | 'WORKFLOW' | 'SYSTEM';

export interface ActivityItem {
  id: ID;
  message: string;
  type: ActivityType;
  createdAt: string;
}