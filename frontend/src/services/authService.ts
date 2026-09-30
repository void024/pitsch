import api, { ENDPOINTS, TOKEN_KEY } from './api';
import type { AuthResponse, LoginRequest, User } from '../types';

const DEMO_EMAIL = 'demo@pitsch.com';
const DEMO_PASSWORD = 'pitsch123';

const DEMO_MODE = true;

export const authService = {
  login: async (payload: LoginRequest): Promise<AuthResponse> => {
    if (DEMO_MODE) {
      if (
        payload.email !== DEMO_EMAIL ||
        payload.password !== DEMO_PASSWORD
      ) {
        throw new Error('Invalid email or password.');
      }

      const demoToken = 'pitsch-demo-token';

      localStorage.setItem(TOKEN_KEY, demoToken);

      return {
        token: demoToken,
      } as AuthResponse;
    }

    const { data } = await api.post<AuthResponse>(
      ENDPOINTS.login,
      payload
    );

    localStorage.setItem(TOKEN_KEY, data.token);

    return data;
  },

  me: async (): Promise<User> => {
    const { data } = await api.get<User>(ENDPOINTS.me);
    return data;
  },

  logout: async (): Promise<void> => {
    if (DEMO_MODE) {
      localStorage.removeItem(TOKEN_KEY);
      return;
    }

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