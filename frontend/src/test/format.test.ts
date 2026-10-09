import { describe, expect, it } from 'vitest';
import { assessmentOf, formatBytes, formatPercent, formatUsd, humanize, initials, isWorkflowActive, timeAgo } from '../lib/format';

describe('format helpers', () => {
  it('formats bytes, money and percentages', () => {
    expect(formatBytes(512)).toBe('512 B');
    expect(formatBytes(2048)).toBe('2 KB');
    expect(formatBytes(5 * 1024 * 1024)).toBe('5.0 MB');
    expect(formatUsd(1.5)).toBe('$1.50');
    expect(formatUsd(0.0042)).toBe('$0.0042');
    expect(formatUsd(null)).toBe('—');
    expect(formatPercent(0.734)).toBe('73%');
  });

  it('humanizes enum values and computes initials', () => {
    expect(humanize('NEEDS_REVIEW')).toBe('Needs review');
    expect(humanize(null)).toBe('—');
    expect(initials('Ada Lovelace')).toBe('AL');
    expect(initials('')).toBe('?');
  });

  it('describes relative time', () => {
    const now = new Date('2026-01-10T12:00:00Z');
    expect(timeAgo('2026-01-10T11:59:50Z', now)).toBe('just now');
    expect(timeAgo('2026-01-10T11:30:00Z', now)).toBe('30 min ago');
    expect(timeAgo('2026-01-10T09:00:00Z', now)).toBe('3 h ago');
    expect(timeAgo('2026-01-08T12:00:00Z', now)).toBe('2 d ago');
    expect(timeAgo('not a date', now)).toBe('—');
  });

  it('knows which workflow states are still moving', () => {
    expect(isWorkflowActive('PROCESSING')).toBe(true);
    expect(isWorkflowActive('COMPLETED')).toBe(false);
    expect(isWorkflowActive(undefined)).toBe(false);
  });

  it('prefers the new claim assessment vocabulary', () => {
    expect(assessmentOf({ assessment: 'SUPPORTED', status: 'VERIFIED' })).toBe('SUPPORTED');
    expect(assessmentOf({ status: 'UNVERIFIED' })).toBe('UNSUPPORTED');
  });
});
