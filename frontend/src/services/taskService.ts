import api, { ENDPOINTS } from './api';
import type { ID, Task, TaskInput, TaskStatus } from '../types';

export const taskService = {
  list: async (): Promise<Task[]> => {
    const { data } = await api.get<Task[]>(ENDPOINTS.tasks);
    return data;
  },

  create: async (payload: TaskInput): Promise<Task> => {
    const { data } = await api.post<Task>(ENDPOINTS.tasks, payload);
    return data;
  },

  update: async (id: ID, payload: TaskInput): Promise<Task> => {
    const { data } = await api.put<Task>(`${ENDPOINTS.tasks}/${id}`, payload);
    return data;
  },

  setStatus: async (id: ID, status: TaskStatus): Promise<Task> => {
    const { data } = await api.patch<Task>(`${ENDPOINTS.tasks}/${id}/status`, { status });
    return data;
  },

  remove: async (id: ID): Promise<void> => {
    await api.delete(`${ENDPOINTS.tasks}/${id}`);
  },
};