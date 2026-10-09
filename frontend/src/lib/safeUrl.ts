/**
 * Only http(s) links from untrusted content (pitch decks, web research, AI output) are rendered as links.
 * `javascript:`, `data:` and other schemes are dropped, which prevents script injection through hrefs.
 */
export function safeHttpUrl(value: string | null | undefined): string | null {
  if (!value) return null;
  try {
    const url = new URL(value.trim());
    return url.protocol === 'https:' || url.protocol === 'http:' ? url.toString() : null;
  } catch {
    return null;
  }
}

/** Internal app paths only (used for `returnTo` style parameters): no protocol-relative or absolute URLs. */
export function safeAppPath(value: string | null | undefined, fallback = '/dashboard'): string {
  if (!value || !value.startsWith('/') || value.startsWith('//') || value.includes('\\')) return fallback;
  return value;
}
