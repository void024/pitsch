import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from 'react';
import { onSessionEnded, refreshAccessToken, setAccessToken } from '../api/client';
import { auth as authApi } from '../api/endpoints';
import type { AuthResponse, Me, Permission } from '../api/types';

type Status = 'loading' | 'authenticated' | 'anonymous';
type SignupInput = Parameters<typeof authApi.signup>[0];

interface AuthContextValue {
  status: Status;
  me: Me | null;
  login: (email: string, password: string) => Promise<Me>;
  signup: (input: SignupInput) => Promise<Me>;
  logout: () => Promise<void>;
  /** Re-reads /me (after switching workspace, verifying email, changing role...). */
  refresh: () => Promise<Me | null>;
  /** Adopt a session issued outside login (Google sign-in callback). */
  adoptSession: () => Promise<Me | null>;
  switchWorkspace: (organizationId: number) => Promise<void>;
  can: (permission: Permission) => boolean;
}

const AuthContext = createContext<AuthContextValue | null>(null);

export function AuthProvider({ children }: { children: ReactNode }) {
  const [status, setStatus] = useState<Status>('loading');
  const [me, setMe] = useState<Me | null>(null);

  const applyAuth = useCallback((res: AuthResponse): Me => {
    setAccessToken(res.accessToken);
    setMe(res.me);
    setStatus('authenticated');
    return res.me;
  }, []);

  const refresh = useCallback(async (): Promise<Me | null> => {
    try {
      const next = await authApi.me();
      setMe(next);
      setStatus('authenticated');
      return next;
    } catch {
      return null;
    }
  }, []);

  const adoptSession = useCallback(async (): Promise<Me | null> => {
    const token = await refreshAccessToken();
    if (!token) {
      setMe(null);
      setStatus('anonymous');
      return null;
    }
    return refresh();
  }, [refresh]);

  // On load: the access token is only in memory, so a page reload restores the session from the refresh cookie.
  useEffect(() => {
    let cancelled = false;
    (async () => {
      const token = await refreshAccessToken();
      if (cancelled) return;
      if (!token) {
        setStatus('anonymous');
        return;
      }
      const next = await refresh();
      if (!cancelled && !next) setStatus('anonymous');
    })();
    return () => {
      cancelled = true;
    };
  }, [refresh]);

  useEffect(() => onSessionEnded(() => {
    setAccessToken(null);
    setMe(null);
    setStatus('anonymous');
  }), []);

  const login = useCallback(async (email: string, password: string): Promise<Me> => {
    const res = await authApi.login(email, password);
    return applyAuth(res);
  }, [applyAuth]);
  const signup = useCallback(async (input: SignupInput): Promise<Me> => {
    const res = await authApi.signup(input);
    return applyAuth(res);
  }, [applyAuth]);

  const logout = useCallback(async () => {
    try {
      await authApi.logout();
    } finally {
      setAccessToken(null);
      setMe(null);
      setStatus('anonymous');
    }
  }, []);

  const switchWorkspace = useCallback(async (organizationId: number) => {
    const next = await authApi.switchWorkspace(organizationId);
    setMe(next);
  }, []);

  const can = useCallback((permission: Permission) => !!me?.permissions.includes(permission), [me]);

  const value = useMemo<AuthContextValue>(() => ({
    status, me, login, signup, logout, refresh, adoptSession, switchWorkspace, can,
  }), [status, me, login, signup, logout, refresh, adoptSession, switchWorkspace, can]);

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

// eslint-disable-next-line react-refresh/only-export-components
export function useAuth(): AuthContextValue {
  const ctx = useContext(AuthContext);
  if (!ctx) throw new Error('useAuth must be used inside <AuthProvider>');
  return ctx;
}
