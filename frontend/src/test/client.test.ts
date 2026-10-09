import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiError, buildUrl, getAccessToken, newIdempotencyKey, onSessionEnded, request, setAccessToken } from '../lib/api/client';

function json(status: number, body: unknown): Response {
  return new Response(body === undefined ? null : JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  });
}

describe('api client', () => {
  const fetchMock = vi.fn<typeof fetch>();

  beforeEach(() => {
    fetchMock.mockReset();
    vi.stubGlobal('fetch', fetchMock);
    setAccessToken(null);
  });
  afterEach(() => vi.unstubAllGlobals());

  it('builds URLs and drops empty query values', () => {
    expect(buildUrl('/v1/pitches', { q: 'acme', page: 0, sector: '', owner: null, x: undefined }))
      .toBe('/api/v1/pitches?q=acme&page=0');
    expect(buildUrl('v1/health')).toBe('/api/v1/health');
  });

  it('sends the CSRF header, bearer token, JSON body and idempotency key', async () => {
    setAccessToken('tok-1');
    fetchMock.mockResolvedValueOnce(json(200, { ok: true }));
    await request('/v1/tasks', { method: 'POST', body: { title: 'x' }, idempotencyKey: 'web-abc12345' });
    const [, init] = fetchMock.mock.calls[0];
    const headers = init?.headers as Record<string, string>;
    expect(headers['X-Requested-With']).toBe('pitsch-web');
    expect(headers.Authorization).toBe('Bearer tok-1');
    expect(headers['Idempotency-Key']).toBe('web-abc12345');
    expect(headers['Content-Type']).toBe('application/json');
    expect(init?.body).toBe('{"title":"x"}');
    expect(init?.credentials).toBe('include');
  });

  it('never sends an organization id header', async () => {
    fetchMock.mockResolvedValueOnce(json(200, []));
    await request('/v1/pitches');
    const headers = fetchMock.mock.calls[0][1]?.headers as Record<string, string>;
    expect(Object.keys(headers).some((h) => /org|workspace|tenant/i.test(h))).toBe(false);
  });

  it('maps the backend error envelope to ApiError with requestId and field errors', async () => {
    fetchMock.mockResolvedValueOnce(json(400, {
      code: 'VALIDATION_FAILED', message: 'Check the form.', requestId: 'req-9',
      details: [{ field: 'email', message: 'must be a valid email' }],
    }));
    const err = await request('/v1/pitches').catch((e: unknown) => e);
    expect(err).toBeInstanceOf(ApiError);
    const apiErr = err as ApiError;
    expect(apiErr.status).toBe(400);
    expect(apiErr.code).toBe('VALIDATION_FAILED');
    expect(apiErr.requestId).toBe('req-9');
    expect(apiErr.message).toBe('Check the form.');
    expect(apiErr.fieldErrors).toEqual({ email: 'must be a valid email' });
  });

  it('uses a friendly fallback message when the body is not JSON', async () => {
    fetchMock.mockResolvedValueOnce(new Response('<html>bad gateway</html>', { status: 502 }));
    const err = (await request('/v1/pitches').catch((e: unknown) => e)) as ApiError;
    expect(err.message).toMatch(/server had a problem/i);
    expect(err.code).toBe('HTTP_502');
  });

  it('reports network failures as NETWORK_ERROR', async () => {
    fetchMock.mockRejectedValueOnce(new TypeError('Failed to fetch'));
    const err = (await request('/v1/pitches').catch((e: unknown) => e)) as ApiError;
    expect(err.code).toBe('NETWORK_ERROR');
    expect(err.status).toBe(0);
  });

  it('refreshes once on an expired session and retries the request', async () => {
    setAccessToken('old');
    fetchMock
      .mockResolvedValueOnce(json(401, { code: 'SESSION_EXPIRED', message: 'expired' }))
      .mockResolvedValueOnce(json(200, { accessToken: 'new' }))
      .mockResolvedValueOnce(json(200, { id: 1 }));
    const res = await request<{ id: number }>('/v1/me');
    expect(res).toEqual({ id: 1 });
    expect(getAccessToken()).toBe('new');
    expect(fetchMock.mock.calls[1][0]).toBe('/api/v1/auth/refresh');
    const retryHeaders = fetchMock.mock.calls[2][1]?.headers as Record<string, string>;
    expect(retryHeaders.Authorization).toBe('Bearer new');
  });

  it('shares one refresh between parallel requests', async () => {
    setAccessToken('old');
    let refreshCalls = 0;
    fetchMock.mockImplementation(async (input, init) => {
      const url = String(input);
      if (url.endsWith('/v1/auth/refresh')) {
        refreshCalls += 1;
        return json(200, { accessToken: 'fresh' });
      }
      const auth = (init?.headers as Record<string, string>).Authorization;
      return auth === 'Bearer fresh' ? json(200, { url }) : json(401, { code: 'UNAUTHENTICATED' });
    });
    await Promise.all([request('/v1/a'), request('/v1/b'), request('/v1/c')]);
    expect(refreshCalls).toBe(1);
  });

  it('does not refresh on wrong-password 401s and ends the session when refresh fails', async () => {
    fetchMock.mockResolvedValueOnce(json(401, { code: 'INVALID_CREDENTIALS', message: 'Wrong email or password.' }));
    const err = (await request('/v1/auth/login').catch((e: unknown) => e)) as ApiError;
    expect(err.code).toBe('INVALID_CREDENTIALS');
    expect(fetchMock).toHaveBeenCalledTimes(1);

    const ended = vi.fn();
    const unsubscribe = onSessionEnded(ended);
    fetchMock
      .mockResolvedValueOnce(json(401, { code: 'UNAUTHENTICATED' }))
      .mockResolvedValueOnce(json(401, { code: 'UNAUTHENTICATED' }));
    await request('/v1/me').catch(() => undefined);
    expect(ended).toHaveBeenCalledTimes(1);
    expect(getAccessToken()).toBeNull();
    unsubscribe();
  });

  it('returns undefined for 204 responses', async () => {
    fetchMock.mockResolvedValueOnce(new Response(null, { status: 204 }));
    await expect(request('/v1/tasks/1', { method: 'DELETE' })).resolves.toBeUndefined();
  });

  it('creates distinct idempotency keys within the backend length limits', () => {
    const a = newIdempotencyKey('send');
    const b = newIdempotencyKey('send');
    expect(a).not.toBe(b);
    expect(a.startsWith('send-')).toBe(true);
    expect(a.length).toBeGreaterThanOrEqual(8);
    expect(a.length).toBeLessThanOrEqual(100);
  });
});
