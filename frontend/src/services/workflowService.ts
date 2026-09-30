import api, { ENDPOINTS } from './api';
import type { Workflow, WorkflowRequest } from '../types';

/** The React app only ever talks to Spring Boot. Spring Boot orchestrates the AI agents. */
export const workflowService = {
  list: async (): Promise<Workflow[]> => {
    const { data } = await api.get<Workflow[]>(ENDPOINTS.workflows);
    return data;
  },

  run: async (payload: WorkflowRequest): Promise<Workflow> => {
    const { data } = await api.post<Workflow>(ENDPOINTS.workflows, payload);
    return data;
  },
};