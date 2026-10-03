import api, { ENDPOINTS } from './api';
import type { AppNotification, ID } from '../types';

export const notificationService = {
  list: async (): Promise<AppNotification[]> => {
    const { data } = await api.get<AppNotification[]>(ENDPOINTS.notifications);
    return data;
  },

  markRead: async (id: ID): Promise<AppNotification> => {
    const { data } = await api.patch<AppNotification>(`${ENDPOINTS.notifications}/${id}/read`);
    return data;
  },

  markAllRead: async (): Promise<void> => {
    await api.patch(`${ENDPOINTS.notifications}/read-all`);
  },
};
