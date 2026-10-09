/**
 * HTTP client for the Pitsch API.
 *
 * Security model:
 *  - The access token (15 min JWT) lives only in memory, never in localStorage (XSS cannot read it after a reload).
 *  - The refresh token is an httpOnly cookie scoped to /api/v1/auth; the browser sends it to /auth/refresh only.
 *  - Cookie-authenticated calls carry X-Requested-With (CSRF guard on the backend) and credentials: 'include'.
 *  - On 401 the client refreshes once (single-flight, shared by parallel requests) and retries the request.
 *  - The workspace is never sent by the client: the backend takes it from the session.
 */

const RAW_BASE = (import.meta.env.VITE_API_BASE_URL as string | undefined) ?? '/api';
/** Base URL of the API (".../api"). Same-origin "/api" by default (Vite proxies it in development). */
export const API_BASE_URL = RAW_BASE.replace(/\/+$/, '');

export interface ApiErrorBody {
  code?: string;
  message?: string;
  requestId?: string;
  details?: unknown;
  status?: number;
}

export class ApiError extends Error {
  readonly status: number;
  readonly code: string;
  readonly requestId: string | null;
  readonly details: unknown;

  constructor(status: number, body: ApiErrorBody | null, fallback: string) {
    super(body?.message?.trim() || fallback);
    this.name = 'ApiError';
    this.status = status;
    this.code = body?.code ?? (status === 0 ? 'NETWORK_ERROR' : `HTTP_${status}`);
    this.requestId = body?.requestId ?? null;
    this.details = body?.details ?? null;
  }

  /** Field-level validation problems ({field, message}) if the backend returned them. */
  get fieldErrors(): Record<string, string> {
    const out: Record<string, string> = {};
    if (Array.isArray(this.details)) {
      for (const d of this.details as Array<{ field?: string; message?: string }>) {
        if (d && typeof d.field === 'string' && typeof d.message === 'string') out[d.field] = d.message;
      }
    }
    return out;
  }
}

type Listener = () => void;

let accessToken: string | null = null;
let refreshInFlight: Promise<string | null> | null = null;
const sessionEndedListeners = new Set<Listener>();

export function setAccessToken(token: string | null): void {
  accessToken = token;
}

export function getAccessToken(): string | null {
  return accessToken;
}

/** Called when the session can no longer be refreshed (signed out elsewhere, expired, revoked). */
export function onSessionEnded(listener: Listener): () => void {
  sessionEndedListeners.add(listener);
  return () => sessionEndedListeners.delete(listener);
}

export interface RequestOptions {
  method?: 'GET' | 'POST' | 'PUT' | 'PATCH' | 'DELETE';
  body?: unknown;
  query?: Record<string, string | number | boolean | null | undefined>;
  signal?: AbortSignal;
  /** Set for side-effecting POSTs that may be retried by the user (the backend replays the first result). */
  idempotencyKey?: string;
  /** Do not try to refresh on 401 (used by the auth endpoints themselves). */
  skipRefresh?: boolean;
  /** Return the raw Response (downloads). */
  raw?: boolean;
}

export function buildUrl(path: string, query?: RequestOptions['query']): string {
  const url = `${API_BASE_URL}${path.startsWith('/') ? path : `/${path}`}`;
  if (!query) return url;
  const params = new URLSearchParams();
  for (const [k, v] of Object.entries(query)) {
    if (v !== undefined && v !== null && v !== '') params.set(k, String(v));
  }
  const qs = params.toString();
  return qs ? `${url}?${qs}` : url;
}

async function parseError(res: Response): Promise<ApiError> {
  let body: ApiErrorBody | null;
  try {
    const text = await res.text();
    body = text ? (JSON.parse(text) as ApiErrorBody) : null;
  } catch {
    body = null;
  }
  const fallback =
    res.status === 403 ? 'You do not have permission to do that.'
      : res.status === 404 ? 'Not found.'
        : res.status === 429 ? 'Too many requests. Please wait a moment.'
          : res.status >= 500 ? 'The server had a problem. Please try again.'
            : `Request failed (${res.status}).`;
  return new ApiError(res.status, body, fallback);
}

/** Exchanges the refresh cookie for a new access token. Single-flight: parallel callers share one request. */
export function refreshAccessToken(): Promise<string | null> {
  if (!refreshInFlight) {
    refreshInFlight = (async () => {
      try {
        const res = await fetch(buildUrl('/v1/auth/refresh'), {
          method: 'POST',
          credentials: 'include',
          headers: { 'X-Requested-With': 'pitsch-web' },
        });
        if (!res.ok) {
          accessToken = null;
          return null;
        }
        const data = (await res.json()) as { accessToken?: string };
        accessToken = data.accessToken ?? null;
        return accessToken;
      } catch {
        return null;   // network error: keep the session state, the caller reports the failure
      } finally {
        refreshInFlight = null;
      }
    })();
  }
  return refreshInFlight;
}

export async function request<T>(path: string, options: RequestOptions = {}): Promise<T> {
  const res = await send(path, options, false);
  if (options.raw) return res as unknown as T;
  if (res.status === 204) return undefined as T;
  const text = await res.text();
  return (text ? JSON.parse(text) : undefined) as T;
}

async function send(path: string, options: RequestOptions, isRetry: boolean): Promise<Response> {
  const headers: Record<string, string> = { 'X-Requested-With': 'pitsch-web' };
  let body: BodyInit | undefined;
  if (options.body instanceof FormData) {
    body = options.body;
  } else if (options.body !== undefined) {
    headers['Content-Type'] = 'application/json';
    body = JSON.stringify(options.body);
  }
  if (accessToken) headers.Authorization = `Bearer ${accessToken}`;
  if (options.idempotencyKey) headers['Idempotency-Key'] = options.idempotencyKey;

  let res: Response;
  try {
    res = await fetch(buildUrl(path, options.query), {
      method: options.method ?? (body ? 'POST' : 'GET'),
      headers,
      body,
      credentials: 'include',
      signal: options.signal,
    });
  } catch (e) {
    if (e instanceof DOMException && e.name === 'AbortError') throw e;
    throw new ApiError(0, null, 'Unable to reach Pitsch. Check your connection and try again.');
  }

  if (res.status === 401 && !options.skipRefresh && !isRetry) {
    const err = await parseError(res.clone());
    // Wrong password etc. are 401s too: only session problems trigger a refresh.
    if (err.code === 'UNAUTHENTICATED' || err.code === 'SESSION_EXPIRED') {
      const token = await refreshAccessToken();
      if (token) return send(path, options, true);
      sessionEndedListeners.forEach((l) => l());
    }
    throw err;
  }
  if (!res.ok) throw await parseError(res);
  return res;
}

/** A fresh key per user intent (e.g. one click of "Send"); reuse it when retrying the same intent. */
export function newIdempotencyKey(prefix = 'web'): string {
  const rnd = typeof crypto !== 'undefined' && 'randomUUID' in crypto
    ? crypto.randomUUID()
    : `${Date.now().toString(36)}-${Math.random().toString(36).slice(2)}`;
  return `${prefix}-${rnd}`;
}

export function errorMessage(error: unknown): string {
  if (error instanceof ApiError) return error.message;
  if (error instanceof Error) return error.message;
  return 'Something went wrong.';
}
