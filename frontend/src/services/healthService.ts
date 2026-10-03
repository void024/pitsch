import api, { ENDPOINTS } from './api';

export interface Health {
  status: string;
  aiService: 'up' | 'down';
}

export const healthService = {
  get: async (): Promise<Health> => {
    const { data } = await api.get<Health>(ENDPOINTS.health);
    return data;
  },
};
