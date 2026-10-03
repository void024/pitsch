import api, { ENDPOINTS } from './api';
import type { ActivityItem } from '../types';

export const activityService = {
  list: async (): Promise<ActivityItem[]> => {
    const { data } = await api.get<ActivityItem[]>(ENDPOINTS.activity);
    return data;
  },
};