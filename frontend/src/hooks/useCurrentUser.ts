import { authService } from '../services/authService';
import { useFetch } from './useFetch';

export function useCurrentUser() {
  const { data, loading, error, reload } = useFetch(authService.me);
  return { user: data, loading, error, reload };
}