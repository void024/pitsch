import { useCallback, useEffect, useState } from 'react';
import { getErrorMessage } from '../services/api';

interface FetchState<T> {
  data: T | null;
  loading: boolean;
  error: string | null;
}

/**
 * Loads data once and exposes reload().
 * `fetcher` must be a stable reference (a module-level service function or a useCallback).
 */
export function useFetch<T>(fetcher: () => Promise<T>) {
  const [state, setState] = useState<FetchState<T>>({ data: null, loading: true, error: null });
  const [tick, setTick] = useState(0);

  useEffect(() => {
    let cancelled = false;
    fetcher()
      .then((data) => {
        if (!cancelled) setState({ data, loading: false, error: null });
      })
      .catch((err: unknown) => {
        if (!cancelled) setState((s) => ({ ...s, loading: false, error: getErrorMessage(err) }));
      });
    return () => {
      cancelled = true;
    };
  }, [fetcher, tick]);

  const reload = useCallback(() => {
    setState((s) => ({ ...s, loading: s.data === null, error: null }));
    setTick((t) => t + 1);
  }, []);

  return { ...state, reload };
}