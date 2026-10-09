import { describe, expect, it } from 'vitest';
import { safeAppPath, safeHttpUrl } from '../lib/safeUrl';

describe('safeHttpUrl', () => {
  it('keeps http(s) links', () => {
    expect(safeHttpUrl('https://example.com/a?b=1')).toBe('https://example.com/a?b=1');
    expect(safeHttpUrl(' http://example.com ')).toBe('http://example.com/');
  });
  it('drops script and data URLs and garbage', () => {
    expect(safeHttpUrl('javascript:alert(1)')).toBeNull();
    expect(safeHttpUrl('JaVaScRiPt:alert(1)')).toBeNull();
    expect(safeHttpUrl('data:text/html,<script>alert(1)</script>')).toBeNull();
    expect(safeHttpUrl('vbscript:msgbox')).toBeNull();
    expect(safeHttpUrl('/relative')).toBeNull();
    expect(safeHttpUrl('')).toBeNull();
    expect(safeHttpUrl(null)).toBeNull();
  });
});

describe('safeAppPath', () => {
  it('allows internal paths', () => {
    expect(safeAppPath('/pitches/4?tab=brief')).toBe('/pitches/4?tab=brief');
  });
  it('rejects open redirects', () => {
    expect(safeAppPath('//evil.example')).toBe('/dashboard');
    expect(safeAppPath('https://evil.example')).toBe('/dashboard');
    expect(safeAppPath('/\\evil.example')).toBe('/dashboard');
    expect(safeAppPath('javascript:alert(1)', '/x')).toBe('/x');
    expect(safeAppPath(undefined)).toBe('/dashboard');
  });
});
