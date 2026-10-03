import { useCallback } from 'react';
import { useNavigate } from 'react-router-dom';
import { authService } from '../services/authService';

export function useLogout() {
  const navigate = useNavigate();
  return useCallback(async () => {
    await authService.logout();
    navigate('/login', { replace: true });
  }, [navigate]);
}