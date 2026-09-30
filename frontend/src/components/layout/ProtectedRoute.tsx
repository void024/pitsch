import { Navigate, Outlet } from 'react-router-dom';
import { authService } from '../../services/authService';

export function ProtectedRoute() {
  if (!authService.isAuthenticated()) {
    return <Navigate to="/login" replace />;
  }
  return <Outlet />;
}