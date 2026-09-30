import axios from 'axios';

/** Change this single constant (or move it to an env variable later). */
export const API_BASE_URL = 'http://localhost:8080/api';

export const TOKEN_KEY = 'pitsch_token';

/** Every backend path in one place so they are easy to change later. */
export const ENDPOINTS = {
  login: '/auth/login',
  logout: '/auth/logout',
  me: '/auth/me',
  profile: '/users/me',
  settings: '/users/me/settings',
  tasks: '/tasks',
  events: '/events',
  workflows: '/workflows',
  activity: '/activity',
} as const;

const api = axios.create({
  baseURL: API_BASE_URL,
  headers: {
    'Content-Type': 'application/json',
  },
});

api.interceptors.request.use((config) => {
  const token = localStorage.getItem(TOKEN_KEY);
  if (token) {
    config.headers.set('Authorization', `Bearer ${token}`);
  }
  return config;
});

api.interceptors.response.use(
  (response) => response,
  (error: unknown) => {
    if (axios.isAxiosError(error) && error.response?.status === 401) {
      localStorage.removeItem(TOKEN_KEY);
      if (window.location.pathname !== '/login') {
        window.location.assign('/login');
      }
    }
    return Promise.reject(error);
  },
);

export function getErrorStatus(error: unknown): number | null {
  if (axios.isAxiosError(error) && error.response) {
    return error.response.status;
  }
  return null;
}

export function getErrorMessage(error: unknown): string {
  if (axios.isAxiosError(error)) {
    if (!error.response) {
      return 'Unable to reach the server. Check that the backend is running.';
    }
    const data: unknown = error.response.data;
    if (typeof data === 'object' && data !== null && 'message' in data) {
      const message = (data as { message: unknown }).message;
      if (typeof message === 'string' && message.trim()) {
        return message;
      }
    }
    return `Request failed (${error.response.status}).`;
  }
  if (error instanceof Error) {
    return error.message;
  }
  return 'Something went wrong.';
}

export default api;