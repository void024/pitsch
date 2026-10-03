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

/* ---------- AI / workflows ---------- */
export type WorkflowStatus = 'PENDING' | 'RUNNING' | 'COMPLETED' | 'FAILED';

export interface Workflow {
  id: ID;
  prompt: string;
  status: WorkflowStatus;
  result?: string | null;
  error?: string | null;
  createdAt: string;
  completedAt?: string | null;
}

export interface WorkflowRequest {
  prompt: string;
}

/* ---------- Activity ---------- */
export type ActivityType = 'TASK' | 'EVENT' | 'WORKFLOW' | 'SYSTEM';

export interface ActivityItem {
  id: ID;
  message: string;
  type: ActivityType;
  createdAt: string;
}