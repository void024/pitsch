import api, { ENDPOINTS, TOKEN_KEY } from './api';
import type { AuthResponse, LoginRequest, User } from '../types';

/** Real authentication against the Spring Boot backend (JWT). Demo account: demo@pitsch.com / pitsch123. */
export const authService = {
  login: async (payload: LoginRequest): Promise<AuthResponse> => {
    const { data } = await api.post<AuthResponse>(ENDPOINTS.login, payload);
    localStorage.setItem(TOKEN_KEY, data.token);
    return data;
  },

  me: async (): Promise<User> => {
    const { data } = await api.get<User>(ENDPOINTS.me);
    return data;
  },

  logout: async (): Promise<void> => {
    try {
      await api.post(ENDPOINTS.logout);
    } catch {
      // Local session is cleared regardless of server response.
    } finally {
      localStorage.removeItem(TOKEN_KEY);
    }
  },

  isAuthenticated: (): boolean => {
    return Boolean(localStorage.getItem(TOKEN_KEY));
  },
};
