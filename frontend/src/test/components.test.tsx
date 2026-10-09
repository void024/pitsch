import { cleanup, render, screen } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { BriefView } from '../components/BriefView';
import type { Brief, Me } from '../lib/api/types';

const authState: { status: 'loading' | 'authenticated' | 'anonymous'; me: Me | null } = { status: 'anonymous', me: null };

vi.mock('../lib/auth/AuthContext', () => ({
  useAuth: () => ({ ...authState, can: () => true }),
}));

// Imported after the mock is registered.
const { RequireAuth } = await import('../components/layout/AppShell');

afterEach(cleanup);

function brief(overrides: Partial<Brief> = {}): Brief {
  return {
    companyOverview: [{ field: 'Company', value: 'Acme Robotics', provenance: 'PITCH', source: 'email' }],
    executiveSummary: [{ statement: 'Acme builds warehouse robots.', citations: ['P1'], provenance: 'PITCH' }],
    claimsMatrix: [{
      claimId: 'C1', claim: '$2M ARR', category: 'traction', status: 'VERIFIED', assessment: 'PARTIALLY_SUPPORTED',
      independentlyVerified: false, finding: 'Press coverage mentions $1.8M.', evidenceOutdated: true,
      supporting: [{ evidenceId: 'E1', sourceTitle: 'Evil link', sourceUrl: 'javascript:alert(1)', publishedAt: null, sourceType: 'news' }],
      contradicting: [],
    }],
    tractionMetrics: [], market: [], competition: [], competitors: [], founders: [], fundingHistory: [], risks: [],
    openQuestions: [{ question: 'What is net revenue retention?', reason: null, citations: [], origin: 'analysis' }],
    sources: [{ sourceId: 'S1', title: 'TechNews', url: 'https://news.example.com/acme', sourceType: 'NEWS', publishedAt: null, possiblyOutdated: false }],
    missingInformation: ['Cap table'],
    aiConfidence: { level: 'MEDIUM', score: 0.62, reasons: ['3 of 5 claims have evidence'], note: 'Evidence support, not a recommendation.' },
    ...overrides,
  };
}

describe('BriefView', () => {
  it('renders an empty state without a brief', () => {
    render(<BriefView brief={null} />);
    expect(screen.getByText('No brief yet')).toBeTruthy();
  });

  it('shows assessments, provenance and a not-investment-advice notice', () => {
    render(<BriefView brief={brief()} />);
    expect(screen.getByText(/not investment advice/i)).toBeTruthy();
    expect(screen.getByText('Partially supported')).toBeTruthy();
    expect(screen.getByText(/evidence may be outdated/i)).toBeTruthy();
    expect(screen.getByText('What is net revenue retention?')).toBeTruthy();
    expect(screen.getByText('Cap table')).toBeTruthy();
    expect(screen.getByText(/medium support · 62%/i)).toBeTruthy();
  });

  it('never renders a hard invest / pass verdict', () => {
    const { container } = render(<BriefView brief={brief()} />);
    expect(container.textContent ?? '').not.toMatch(/\b(we recommend investing|do not invest|don't invest)\b/i);
  });

  it('renders untrusted source URLs as links only when they are http(s)', () => {
    const { container } = render(<BriefView brief={brief()} />);
    const hrefs = Array.from(container.querySelectorAll('a')).map((a) => a.getAttribute('href'));
    expect(hrefs).toContain('https://news.example.com/acme');
    expect(hrefs.some((h) => h?.startsWith('javascript:'))).toBe(false);
    expect(screen.getByText('Evil link').tagName).toBe('SPAN');
    for (const a of Array.from(container.querySelectorAll('a'))) {
      expect(a.getAttribute('rel')).toContain('noopener');
    }
  });
});

describe('RequireAuth', () => {
  function renderAt(path: string) {
    return render(
      <MemoryRouter initialEntries={[path]}>
        <Routes>
          <Route path="/login" element={<div>login page</div>} />
          <Route path="/onboarding" element={<div>onboarding page</div>} />
          <Route element={<RequireAuth />}>
            <Route path="/pitches" element={<div>pitches page</div>} />
          </Route>
        </Routes>
      </MemoryRouter>,
    );
  }

  it('redirects anonymous users to login', () => {
    authState.status = 'anonymous';
    authState.me = null;
    renderAt('/pitches');
    expect(screen.getByText('login page')).toBeTruthy();
  });

  it('sends users without a workspace to onboarding', () => {
    authState.status = 'authenticated';
    authState.me = { workspace: null, permissions: [] } as unknown as Me;
    renderAt('/pitches');
    expect(screen.getByText('onboarding page')).toBeTruthy();
  });

  it('renders the page for signed-in members', () => {
    authState.status = 'authenticated';
    authState.me = { workspace: { id: 1, name: 'Acme Capital' }, permissions: [] } as unknown as Me;
    renderAt('/pitches');
    expect(screen.getByText('pitches page')).toBeTruthy();
  });
});
