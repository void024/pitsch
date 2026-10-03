import { useEffect } from 'react';

/** Calls `tick` every `intervalMs` while `active` is true (e.g. while agents are working). */
export function usePolling(tick: () => void, active: boolean, intervalMs = 3000) {
  useEffect(() => {
    if (!active) return;
    const id = window.setInterval(tick, intervalMs);
    return () => window.clearInterval(id);
  }, [tick, active, intervalMs]);
}
