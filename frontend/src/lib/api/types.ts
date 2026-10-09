/* API contract types (mirror the backend's /api/v1 records). IDs are numbers; timestamps are ISO-8601 strings. */

export interface Page<T> {
  items: T[];
  page: number;
  size: number;
  totalItems: number;
  totalPages: number;
}

/* ---------- Auth / me ---------- */
export type Role = 'OWNER' | 'ADMIN' | 'INVESTOR' | 'ANALYST' | 'MEMBER';
export type Permission =
  | 'PITCH_READ' | 'PITCH_WRITE' | 'PITCH_DELETE' | 'EMAIL_IMPORT' | 'WORKFLOW_RUN' | 'ACTION_APPROVE'
  | 'CALENDAR_READ' | 'CALENDAR_WRITE' | 'TASK_READ' | 'TASK_WRITE' | 'INTEGRATION_CONNECT' | 'INTEGRATION_MANAGE'
  | 'MEMBER_READ' | 'MEMBER_MANAGE' | 'ORG_SETTINGS' | 'AUDIT_READ' | 'DATA_EXPORT' | 'ORG_DELETE' | 'USAGE_READ'
  | 'BILLING_MANAGE';

export interface UserView {
  id: number;
  name: string;
  email: string;
  avatarUrl: string | null;
  role: Role | null;
  emailVerified: boolean;
}

export interface WorkspaceSummary {
  id: number;
  name: string;
  slug: string;
  timezone: string;
  role: Role;
  plan: string;
  onboardingCompleted: boolean;
  createdAt: string;
}

export interface Me {
  user: UserView;
  workspace: WorkspaceSummary | null;
  permissions: Permission[];
  memberships: { organizationId: number; organizationName: string; role: Role }[];
  features: { mode: string; googleConfigured: boolean; googleLoginEnabled: boolean; billingEnabled: boolean; demo: boolean };
}

export interface AuthResponse {
  accessToken: string;
  tokenType: string;
  expiresIn: number;
  user: UserView;
  me: Me;
}

export interface SessionInfo {
  id: string;
  current: boolean;
  userAgent: string;
  ipAddress: string;
  createdAt: string;
  lastSeenAt: string;
}

/* ---------- Workspace ---------- */
export type ActionPolicy = 'AUTO' | 'APPROVAL' | 'OFF';

export interface WorkspaceSettings {
  id: number;
  name: string;
  slug: string;
  timezone: string;
  workingHoursStart: string;
  workingHoursEnd: string;
  workingDays: string[];
  meetingDurationMinutes: number;
  dataRetentionDays: number | null;
  gmailLabelPolicy: ActionPolicy;
  sheetsSyncPolicy: ActionPolicy;
  onboardingCompleted: boolean;
  createdAt: string;
}

export interface Member {
  membershipId: number;
  userId: number;
  name: string;
  email: string;
  role: Role;
  joinedAt: string;
}

export interface Invitation {
  id: number;
  email: string;
  role: Role;
  expiresAt: string;
  createdAt: string;
}

export interface InvitationLookup {
  workspaceName: string;
  email: string;
  role: Role;
  expiresAt: string;
}

/* ---------- Pitches ---------- */
export type DealStage = 'NEW' | 'SCREENING' | 'DILIGENCE' | 'MEETING' | 'DECISION' | 'INVESTED' | 'PASSED' | 'ARCHIVED';
export const DEAL_STAGES: DealStage[] = ['NEW', 'SCREENING', 'DILIGENCE', 'MEETING', 'DECISION', 'INVESTED', 'PASSED', 'ARCHIVED'];

export interface Pitch {
  id: number;
  companyName: string | null;
  founderName: string | null;
  founderEmail: string | null;
  companyDomain: string | null;
  website: string | null;
  sector: string | null;
  stage: string | null;
  oneLiner: string | null;
  description: string | null;
  amountRequested: string | null;
  source: 'EMAIL' | 'MANUAL';
  status: string;
  dealStage: DealStage;
  ownerUserId: number | null;
  aiConfidence: number | null;
  claimsSupported: number | null;
  claimsContradicted: number | null;
  claimsUnresolved: number | null;
  riskLevel: 'LOW' | 'MEDIUM' | 'HIGH' | null;
  hasFollowUp: boolean;
  latestWorkflowId: number | null;
  latestBriefWorkflowId: number | null;
  lastActivityAt: string | null;
  createdAt: string;
  updatedAt: string;
  version: number;
}

export interface PitchInput {
  companyName?: string;
  founderName?: string;
  founderEmail?: string;
  website?: string;
  sector?: string;
  stage?: string;
  oneLiner?: string;
  description?: string;
  amountRequested?: string;
  dealStage?: DealStage;
  ownerUserId?: number;
  version?: number;
}

export interface PitchDetail {
  pitch: Pitch;
  workflows: WorkflowView[];
  briefWorkflowId?: number;
  brief?: Brief | null;
  briefMarkdown?: string | null;
  briefUpdatedAt?: string | null;
}

export interface TimelineEntry {
  at: string;
  kind: string;
  title: string;
  detail: string | null;
  workflowId: number | null;
  refId: number | null;
}

/* ---------- Workflows ---------- */
export type WorkflowStatus =
  | 'RECEIVED' | 'CLASSIFYING' | 'NOT_PITCH' | 'AWAITING_USER' | 'PROCESSING' | 'WAITING_FOR_APPROVAL'
  | 'COMPLETED' | 'STOPPED' | 'FAILED';
export type WorkflowAction = 'COMPLETE_WORKFLOW' | 'STOP' | 'PLAN_MEETING' | 'PLAN_EMAIL_RESPONSE' | 'RETRY';

export interface AgentStatus {
  agent: string;
  label: string;
  status: 'RUNNING' | 'COMPLETED' | 'FAILED' | 'PENDING' | 'SKIPPED' | string;
  errorMessage: string | null;
  startedAt: string | null;
  completedAt: string | null;
  latencyMs: number;
  promptTokens: number;
  completionTokens: number;
  model: string | null;
  estimatedCostUsd: number | null;
  attempts: number;
}

export interface Attachment {
  id: number;
  filename: string;
  mimeType: string;
  sizeBytes: number;
}

export interface EmailView {
  id: number;
  sender: string;
  senderName: string | null;
  subject: string | null;
  body: string | null;
  receivedAt: string;
  threadId: string | null;
  attachments: Attachment[];
  labels: string[];
  isPitch: boolean | null;
  isFollowUp: boolean | null;
  category: string | null;
  source: 'MANUAL' | 'GMAIL';
}

export interface DraftView {
  id: number;
  recipient: string;
  recipientName: string | null;
  subject: string | null;
  body: string | null;
  purpose: string | null;
  status: 'DRAFT' | 'SENDING' | 'SENT' | 'FAILED' | 'CANCELLED' | 'SIMULATED' | 'DEMO_SENT';
  needsHumanReview: boolean;
  reviewReasons: string[];
  createdAt: string;
  sentAt: string | null;
  failureReason: string | null;
}

export interface MeetingView {
  start: string;
  end: string;
  eventId: number | null;
  syncStatus: string | null;
  conferenceLink: string | null;
  htmlLink: string | null;
  provider: string | null;
}

export interface Slot {
  rank?: number;
  start: string;
  end: string;
  score?: number;
  reasons?: string[];
}

export interface WorkflowView {
  id: number;
  type: 'NEW_PITCH' | 'FOLLOW_UP' | 'NOT_PITCH' | null;
  status: WorkflowStatus;
  currentStep: string | null;
  recommendedAction: string | null;
  availableActions: WorkflowAction[];
  prompt: string | null;
  result?: string | null;
  error?: string | null;
  createdAt: string;
  updatedAt: string;
  completedAt?: string | null;
  pitchId?: number | null;
  companyName?: string | null;
  emailId?: number | null;
  sender?: string | null;
  senderName?: string | null;
  confidence?: number | null;
  classificationReason?: string | null;
  needsHumanReview: boolean;
  reviewReasons: string[];
  warnings: string[];
  agents?: AgentStatus[];
  email?: EmailView;
  brief?: Brief | null;
  briefMarkdown?: string | null;
  slots?: Slot[] | null;
  slotsTimezone?: string | null;
  meeting?: MeetingView | null;
  draft?: DraftView | null;
  ownerUserId?: number | null;
  emailSource?: string | null;
  promptInjectionSuspected?: boolean | null;
}

export interface ExecutionView {
  id: number;
  agentName: string;
  step: number;
  status: string;
  model: string | null;
  attempts: number;
  promptTokens: number;
  completionTokens: number;
  estimatedCostUsd: number | null;
  latencyMs: number;
  errorCode: string | null;
  errorCategory: string | null;
  errorMessage: string | null;
  inputHash: string | null;
  startedAt: string | null;
  completedAt: string | null;
}

/* ---------- Brief (Analysis Agent output) ---------- */
export type Provenance = 'PITCH' | 'COMPANY' | 'EXTERNAL' | 'AI_INFERENCE';

export interface Finding {
  statement: string;
  citations: string[];
  provenance: Provenance;
}

export interface EvidenceLink {
  evidenceId: string;
  sourceTitle: string;
  sourceUrl: string;
  publishedAt: string | null;
  sourceType: string;
}

export interface ClaimRow {
  claimId: string;
  claim: string;
  category: string | null;
  status: string;
  assessment?: string;
  independentlyVerified: boolean;
  finding: string | null;
  evidenceOutdated: boolean;
  supporting: EvidenceLink[];
  contradicting: EvidenceLink[];
}

export interface Brief {
  companyOverview: { field: string; value: string; provenance: Provenance; source: string }[];
  executiveSummary: Finding[];
  claimsMatrix: ClaimRow[];
  tractionMetrics: { metric: string; value: string; period: string | null; sourceRef: string | null }[];
  market: Finding[];
  competition: Finding[];
  competitors: { name: string; description?: string | null }[];
  founders: Finding[];
  fundingHistory: Finding[];
  risks: { risk: string; category: string; citations: string[] }[];
  openQuestions: { question: string; reason: string | null; citations: string[]; origin: string }[];
  sources: { sourceId: string; title: string; url: string; sourceType: string; publishedAt: string | null; possiblyOutdated: boolean }[];
  problem?: Finding[];
  solution?: Finding[];
  product?: Finding[];
  businessModel?: Finding[];
  opportunities?: Finding[];
  fundraising?: { amountRequested: string | null; currency: string | null; instrument: string | null; valuation: string | null; useOfFunds: string[] } | null;
  missingInformation?: string[];
  aiConfidence?: { level: 'LOW' | 'MEDIUM' | 'HIGH'; score: number; reasons: string[]; note: string } | null;
  generatedAt?: string | null;
}

/* ---------- Approvals ---------- */
export interface ApprovalView {
  id: number;
  type: 'SEND_EMAIL' | 'CREATE_MEETING' | 'CANCEL_MEETING' | 'SYNC_PIPELINE' | 'APPLY_LABELS';
  status: 'PENDING' | 'APPROVED' | 'REJECTED' | 'EXECUTING' | 'EXECUTED' | 'FAILED' | 'SUPERSEDED';
  summary: string;
  payload: Record<string, unknown> | null;
  workflowId: number | null;
  pitchId: number | null;
  proposedBy: string;
  reason: string | null;
  decidedByUserId: number | null;
  decidedAt: string | null;
  executedAt: string | null;
  error: string | null;
  createdAt: string;
}

/* ---------- Calendar / tasks ---------- */
export interface CalendarEvent {
  id: number;
  title: string;
  description: string | null;
  location: string | null;
  startTime: string;
  endTime: string;
  source: string | null;
  provider: string | null;
  syncStatus: string | null;
  conferenceLink: string | null;
  htmlLink: string | null;
  pitchId: number | null;
  workflowId: number | null;
  userId: number | null;
}

export type TaskStatus = 'TODO' | 'IN_PROGRESS' | 'DONE';
export type TaskPriority = 'LOW' | 'MEDIUM' | 'HIGH';
export interface Task {
  id: number;
  title: string;
  description: string | null;
  status: TaskStatus;
  priority: TaskPriority;
  dueDate: string | null;
  pitchId: number | null;
  assigneeUserId: number | null;
  createdAt: string;
  updatedAt: string | null;
}

/* ---------- Notifications ---------- */
export interface NotificationView {
  id: number;
  type: string;
  title: string;
  message: string;
  workflowId: number | null;
  read: boolean;
  createdAt: string;
}

export interface NotificationPreference {
  type: string;
  inApp: boolean;
  email: boolean;
}

/* ---------- Integrations ---------- */
export type IntegrationName = 'GMAIL' | 'CALENDAR' | 'SHEETS';
export interface IntegrationStatus {
  integration: IntegrationName;
  configured: boolean;
  demo: boolean;
  connected: boolean;
  status: string | null;
  accountEmail: string | null;
  scopes: string[];
  lastSyncAt: string | null;
  lastSyncStatus: string | null;
  error: string | null;
  pushEnabled: boolean;
}

export interface SheetConfig {
  spreadsheetId: string;
  worksheetTitle: string;
  headerRow: number;
  mapping: { field: string; column: string }[];
  accountEmail: string | null;
  connected: boolean;
}

export interface SheetPreview {
  spreadsheetId: string;
  title: string;
  worksheets: string[];
  worksheet: string;
  header: string[];
}

/* ---------- Dashboard / billing / audit / activity ---------- */
export interface Quota {
  metric: string;
  used: number;
  limit: number;
  unlimited: boolean;
}

export interface Dashboard {
  pitchesByStage: Record<DealStage, number>;
  totalPitches: number;
  workflowsNeedingAction: number;
  workflowsRunning: number;
  pendingApprovals: number;
  openTasks: number;
  myOpenTasks: number;
  unreadNotifications: number;
  upcomingMeetings: CalendarEvent[];
  recentActivity: { id: number; type: string; message: string; createdAt: string }[];
  aiUsageThisMonth: { agent: string; calls: number; tokens: number; costUsd: number; avgLatencyMs: number }[];
  aiCostThisMonthUsd: number;
  quotas: Record<string, Quota>;
  plan: string;
}

export interface PlanView {
  code: string;
  name: string;
  monthlyPriceCents: number;
  limits: Record<string, number>;
  purchasable: boolean;
}

export interface BillingView {
  planCode: string;
  effectivePlanCode: string;
  status: string;
  provider: string;
  currentPeriodEnd: string | null;
  cancelAtPeriodEnd: boolean;
  onlineBillingEnabled: boolean;
  hasBillingAccount: boolean;
  usage: Record<string, Quota>;
  plans: PlanView[];
}

export interface AuditView {
  id: number;
  action: string;
  actorType: string;
  actorUserId: number | null;
  actorName: string | null;
  resourceType: string | null;
  resourceId: string | null;
  ipAddress: string | null;
  requestId: string | null;
  metadata: Record<string, unknown> | null;
  createdAt: string;
}

export interface UserSettings {
  timeFormat: '12h' | '24h';
  compactMode: boolean;
  notifications: { email: boolean; taskReminders: boolean; eventReminders: boolean; workflowUpdates: boolean };
  timezone: string | null;
  firmName: string | null;
  investorTitle: string | null;
}
