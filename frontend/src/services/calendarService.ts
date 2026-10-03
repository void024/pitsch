import api, { ENDPOINTS } from './api';
import type { CalendarEvent, CalendarEventInput, ID } from '../types';

export const calendarService = {
  list: async (): Promise<CalendarEvent[]> => {
    const { data } = await api.get<CalendarEvent[]>(ENDPOINTS.events);
    return data;
  },

  create: async (payload: CalendarEventInput): Promise<CalendarEvent> => {
    const { data } = await api.post<CalendarEvent>(ENDPOINTS.events, payload);
    return data;
  },

  update: async (id: ID, payload: CalendarEventInput): Promise<CalendarEvent> => {
    const { data } = await api.put<CalendarEvent>(`${ENDPOINTS.events}/${id}`, payload);
    return data;
  },

  remove: async (id: ID): Promise<void> => {
    await api.delete(`${ENDPOINTS.events}/${id}`);
  },
};