import { BrowserRouter, Navigate, Route, Routes } from 'react-router-dom';
import { AppLayout } from './components/layout/AppLayout';
import { ProtectedRoute } from './components/layout/ProtectedRoute';
import Login from './pages/auth/Login';
import Dashboard from './pages/dashboard/Dashboard';
import Tasks from './pages/tasks/Tasks';
import Calendar from './pages/calendar/Calendar';
import Settings from './pages/settings/Settings';
import Pitches from './pages/pitches/Pitches';
import WorkflowDetail from './pages/pitches/WorkflowDetail';
import './App.css';

export default function App() {
  return (
    <BrowserRouter>
      <Routes>
        <Route path="/login" element={<Login />} />

        <Route element={<ProtectedRoute />}>
          <Route element={<AppLayout />}>
            <Route path="/dashboard" element={<Dashboard />} />
            <Route path="/tasks" element={<Tasks />} />
            <Route path="/calendar" element={<Calendar />} />
            <Route path="/pitches" element={<Pitches />} />
            <Route path="/workflows/:id" element={<WorkflowDetail />} />
            <Route path="/ai" element={<Navigate to="/pitches" replace />} />
            <Route path="/settings" element={<Settings />} />
          </Route>
        </Route>

        <Route path="/" element={<Navigate to="/dashboard" replace />} />
        <Route path="*" element={<Navigate to="/dashboard" replace />} />
      </Routes>
    </BrowserRouter>
  );
}