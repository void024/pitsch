import { useCallback, useEffect, useLayoutEffect, useRef, useState } from 'react';

export interface AsyncState<T> {
  data: T | undefined;
  error: unknown;
  loading: boolean;
  reload: () => void;
  setData: (updater: T | ((prev: T | undefined) => T)) => void;
}

/**
 * Runs `load` whenever `deps` change; aborts stale requests. `loading` is true only while no data is shown, so
 * background reloads (polling) do not flash spinners.
 */
export function useAsync<T>(load: (signal: AbortSignal) => Promise<T>, deps: unknown[]): AsyncState<T> {
  const [data, setDataState] = useState<T | undefined>(undefined);
  const [error, setError] = useState<unknown>(null);
  const [settled, setSettled] = useState(false);
  const [tick, setTick] = useState(0);
  const loadRef = useRef(load);
  useLayoutEffect(() => {
    loadRef.current = load;
  });

  useEffect(() => {
    const controller = new AbortController();
    loadRef.current(controller.signal)
      .then((d) => {
        if (!controller.signal.aborted) {
          setDataState(d);
          setError(null);
        }
      })
      .catch((e: unknown) => {
        if (!controller.signal.aborted && !(e instanceof DOMException && e.name === 'AbortError')) setError(e);
      })
      .finally(() => {
        if (!controller.signal.aborted) setSettled(true);
      });
    return () => controller.abort();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [...deps, tick]);

  const reload = useCallback(() => setTick((t) => t + 1), []);
  const setData = useCallback((updater: T | ((prev: T | undefined) => T)) => {
    setDataState((prev) => (typeof updater === 'function' ? (updater as (p: T | undefined) => T)(prev) : updater));
  }, []);
  // Spinner only before the first result; later reloads keep showing the previous data.
  const loading = !settled && data === undefined;
  return { data, error, loading, reload, setData };
}

/** Calls `fn` every `ms` while `active` and the tab is visible. */
export function useInterval(fn: () => void, ms: number, active = true): void {
  const fnRef = useRef(fn);
  useLayoutEffect(() => {
    fnRef.current = fn;
  });
  useEffect(() => {
    if (!active) return undefined;
    const id = window.setInterval(() => {
      if (document.visibilityState === 'visible') fnRef.current();
    }, ms);
    return () => window.clearInterval(id);
  }, [ms, active]);
}

export function useDebounced<T>(value: T, ms = 300): T {
  const [v, setV] = useState(value);
  useEffect(() => {
    const id = window.setTimeout(() => setV(value), ms);
    return () => window.clearTimeout(id);
  }, [value, ms]);
  return v;
}

/** Download a fetch Response as a file (exports, markdown). */
export async function downloadResponse(res: Response, fallbackName: string): Promise<void> {
  const blob = await res.blob();
  const cd = res.headers.get('Content-Disposition') ?? '';
  const match = /filename="?([^";]+)"?/.exec(cd);
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = match?.[1] ?? fallbackName;
  document.body.appendChild(a);
  a.click();
  a.remove();
  URL.revokeObjectURL(url);
}
