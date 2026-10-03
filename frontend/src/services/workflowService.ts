import api, { ENDPOINTS } from './api';
import type {
  ActionRequest,
  AvailabilityResponse,
  EmailDraft,
  ID,
  SubmitEmailRequest,
  SubmitEmailResponse,
  Workflow,
} from '../types';

/** The React app only ever talks to Spring Boot. Spring Boot orchestrates the AI agents. */
export const workflowService = {
  list: async (): Promise<Workflow[]> => {
    const { data } = await api.get<Workflow[]>(ENDPOINTS.workflows);
    return data;
  },

  get: async (id: ID): Promise<Workflow> => {
    const { data } = await api.get<Workflow>(`${ENDPOINTS.workflows}/${id}`);
    return data;
  },

  /** Send one of the workflow's availableActions (exact enum values). */
  action: async (id: ID, payload: ActionRequest): Promise<Workflow> => {
    const { data } = await api.post<Workflow>(`${ENDPOINTS.workflows}/${id}/action`, payload);
    return data;
  },

  /** Submit an incoming pitch email (optionally with a deck) for processing. */
  submitEmail: async (payload: SubmitEmailRequest): Promise<SubmitEmailResponse> => {
    const { data } = await api.post<SubmitEmailResponse>(`${ENDPOINTS.emails}/process`, payload);
    return data;
  },

  availability: async (workflowId: ID): Promise<AvailabilityResponse> => {
    const { data } = await api.get<AvailabilityResponse>(`${ENDPOINTS.calendar}/availability`, {
      params: { workflowId },
    });
    return data;
  },

  /** Approves and creates the meeting for the chosen slot. */
  scheduleMeeting: async (workflowId: ID, start: string, end: string): Promise<Workflow> => {
    const { data } = await api.post<Workflow>(`${ENDPOINTS.calendar}/meeting`, { workflowId, start, end });
    return data;
  },

  updateDraft: async (draftId: ID, subject: string, body: string): Promise<EmailDraft> => {
    const { data } = await api.put<EmailDraft>(`${ENDPOINTS.drafts}/${draftId}`, { subject, body });
    return data;
  },

  /** Approves and sends the (possibly edited) draft. */
  sendDraft: async (draftId: ID, subject: string, body: string): Promise<Workflow> => {
    const { data } = await api.post<Workflow>(`${ENDPOINTS.drafts}/${draftId}/send`, { subject, body });
    return data;
  },

  cancelDraft: async (draftId: ID): Promise<Workflow> => {
    const { data } = await api.post<Workflow>(`${ENDPOINTS.drafts}/${draftId}/cancel`);
    return data;
  },

  briefMarkdown: async (id: ID): Promise<string> => {
    const { data } = await api.get<string>(`${ENDPOINTS.workflows}/${id}/brief.md`, { responseType: 'text' });
    return data;
  },
};
