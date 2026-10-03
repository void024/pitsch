import api, { ENDPOINTS } from './api';
import type { UpdateProfileRequest, User, UserSettings } from '../types';

export const userService = {
  updateProfile: async (payload: UpdateProfileRequest): Promise<User> => {
    const { data } = await api.put<User>(ENDPOINTS.profile, payload);
    return data;
  },

  getSettings: async (): Promise<UserSettings> => {
    const { data } = await api.get<UserSettings>(ENDPOINTS.settings);
    return data;
  },

  updateSettings: async (payload: UserSettings): Promise<UserSettings> => {
    const { data } = await api.put<UserSettings>(ENDPOINTS.settings, payload);
    return data;
  },
};