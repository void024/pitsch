import type { Page, Request, Route } from '@playwright/test';

/**
 * A small stateful fake of the Pitsch API (/api/v1). Only what the tested journeys need; any other call fails
 * loudly with 404 so a missing mock is obvious in the test output.
 */
export interface MockState {
  loggedIn: boolean;
  draftStatus: 'DRAFT' | 'SENT';
  sendRequests: { idempotencyKey: string | null; body: unknown }[];
  unmatched: string[];
}

const now = new Date().toISOString();

const me = {
  user: { id: 1, name: 'Ada Lovelace', email: 'ada@northwind.vc', avatarUrl: null, role: 'OWNER', emailVerified: true },
  workspace: {
    id: 10, name: 'Northwind Ventures', slug: 'northwind', timezone: 'Europe/London', role: 'OWNER', plan: 'TEAM',
    onboardingCompleted: true, createdAt: now,
  },
  permissions: ['PITCH_READ', 'PITCH_WRITE', 'EMAIL_IMPORT', 'WORKFLOW_RUN', 'ACTION_APPROVE', 'TASK_READ', 'TASK_WRITE',
    'CALENDAR_READ', 'MEMBER_READ', 'ORG_SETTINGS', 'AUDIT_READ', 'USAGE_READ'],
  memberships: [{ organizationId: 10, organizationName: 'Northwind Ventures', role: 'OWNER' }],
  features: { mode: 'test', googleConfigured: false, googleLoginEnabled: false, billingEnabled: false, demo: false },
};

const brief = {
  companyOverview: [{ field: 'Company', value: 'Acme Robotics', provenance: 'PITCH', source: 'email' }],
  executiveSummary: [{ statement: 'Acme builds autonomous warehouse robots.', citations: ['P1'], provenance: 'PITCH' }],
  claimsMatrix: [{
    claimId: 'C1', claim: '$2M ARR', category: 'traction', status: 'PARTIALLY_VERIFIED', assessment: 'PARTIALLY_SUPPORTED',
    independentlyVerified: false, finding: 'A trade article reports about $1.8M.', evidenceOutdated: false,
    supporting: [{ evidenceId: 'E1', sourceTitle: 'Trade article', sourceUrl: 'https://news.example.com/acme', publishedAt: null, sourceType: 'news' }],
    contradicting: [],
  }],
  tractionMetrics: [], market: [], competition: [], competitors: [], founders: [], fundingHistory: [], risks: [],
  openQuestions: [{ question: 'What is net revenue retention?', reason: null, citations: [], origin: 'analysis' }],
  sources: [], aiConfidence: { level: 'MEDIUM', score: 0.6, reasons: ['1 of 1 claims has evidence'], note: 'Evidence support only.' },
};

function workflow(state: MockState) {
  return {
    id: 42, type: 'NEW_PITCH', status: state.draftStatus === 'SENT' ? 'COMPLETED' : 'AWAITING_USER',
    currentStep: state.draftStatus === 'SENT' ? 'EMAIL_SENT' : 'EMAIL_DRAFT_READY',
    recommendedAction: null, availableActions: [], prompt: 'Review the reply', createdAt: now, updatedAt: now,
    pitchId: 5, companyName: 'Acme Robotics', emailId: 3, sender: 'founder@acme.example', senderName: 'Grace Hopper',
    needsHumanReview: false, reviewReasons: [], warnings: [], agents: [], brief, briefMarkdown: null,
    email: {
      id: 3, sender: 'founder@acme.example', senderName: 'Grace Hopper', subject: 'Acme Robotics — seed round',
      body: 'Hi, we are raising a seed round.', receivedAt: now, threadId: null, attachments: [], labels: [],
      isPitch: true, isFollowUp: false, category: 'PITCH', source: 'MANUAL',
    },
    draft: {
      id: 7, recipient: 'founder@acme.example', recipientName: 'Grace Hopper', subject: 'Re: Acme Robotics — seed round',
      body: 'Thanks Grace, we would love to learn more.', purpose: 'REPLY', status: state.draftStatus,
      needsHumanReview: false, reviewReasons: [], createdAt: now, sentAt: state.draftStatus === 'SENT' ? now : null,
      failureReason: null,
    },
  };
}

const dashboard = {
  pitchesByStage: { NEW: 1, SCREENING: 0, DILIGENCE: 0, MEETING: 0, DECISION: 0, INVESTED: 0, PASSED: 0, ARCHIVED: 0 },
  totalPitches: 1, workflowsNeedingAction: 1, workflowsRunning: 0, pendingApprovals: 0, openTasks: 0, myOpenTasks: 0,
  unreadNotifications: 0, upcomingMeetings: [], recentActivity: [], aiUsageThisMonth: [], aiCostThisMonthUsd: 0,
  quotas: { AI_WORKFLOWS: { metric: 'AI_WORKFLOWS', used: 1, limit: 500, unlimited: false } }, plan: 'TEAM',
};

function json(route: Route, status: number, body?: unknown) {
  return route.fulfill({
    status,
    contentType: 'application/json',
    headers: { 'X-Request-Id': 'req-e2e' },
    body: body === undefined ? '' : JSON.stringify(body),
  });
}

export async function mockApi(page: Page): Promise<MockState> {
  const state: MockState = { loggedIn: false, draftStatus: 'DRAFT', sendRequests: [], unmatched: [] };

  await page.route('**/api/**', async (route: Route, request: Request) => {
    const url = new URL(request.url());
    const path = url.pathname.replace(/^\/api/, '');
    const method = request.method();
    const headers = request.headers();

    // The real backend rejects cookie-authenticated calls without the CSRF header; so does the fake.
    if (headers['x-requested-with'] !== 'pitsch-web') return json(route, 403, { code: 'CSRF', message: 'Missing header' });
    const authed = state.loggedIn && headers.authorization === 'Bearer e2e-token';

    if (method === 'POST' && path === '/v1/auth/refresh') {
      return state.loggedIn ? json(route, 200, { accessToken: 'e2e-token' }) : json(route, 401, { code: 'UNAUTHENTICATED', message: 'No session' });
    }
    if (method === 'POST' && path === '/v1/auth/login') {
      const body = request.postDataJSON() as { email: string; password: string };
      if (body.email === 'ada@northwind.vc' && body.password === 'correct-horse-42') {
        state.loggedIn = true;
        return json(route, 200, { accessToken: 'e2e-token', tokenType: 'Bearer', expiresIn: 900, user: me.user, me });
      }
      return json(route, 401, { code: 'INVALID_CREDENTIALS', message: 'Incorrect email or password.', requestId: 'req-e2e' });
    }
    if (method === 'POST' && path === '/v1/auth/logout') {
      state.loggedIn = false;
      return json(route, 204);
    }
    if (!authed) return json(route, 401, { code: 'UNAUTHENTICATED', message: 'Sign in required' });

    if (method === 'GET' && path === '/v1/me') return json(route, 200, me);
    if (method === 'GET' && path === '/v1/dashboard') return json(route, 200, dashboard);
    if (method === 'GET' && path === '/v1/notifications/unread-count') return json(route, 200, { count: 0 });
    if (method === 'GET' && path === '/v1/workflows') {
      const items = state.draftStatus === 'SENT' ? [] : [workflow(state)];
      return json(route, 200, { items, page: 0, size: 6, totalItems: items.length, totalPages: 1 });
    }
    if (method === 'GET' && path === '/v1/workflows/42') return json(route, 200, workflow(state));
    if (method === 'POST' && path === '/v1/drafts/7/send') {
      state.sendRequests.push({ idempotencyKey: headers['idempotency-key'] ?? null, body: request.postDataJSON() });
      state.draftStatus = 'SENT';
      return json(route, 200, workflow(state));
    }

    state.unmatched.push(`${method} ${path}`);
    return json(route, 404, { code: 'NOT_FOUND', message: `No mock for ${method} ${path}` });
  });
  return state;
}
