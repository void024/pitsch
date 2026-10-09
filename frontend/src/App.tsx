import { lazy, Suspense } from 'react';
import { BrowserRouter, Navigate, Route, Routes } from 'react-router-dom';
import { AuthProvider } from './lib/auth/AuthContext';
import { AppShell, RequireAuth } from './components/layout/AppShell';
import { Spinner, ToastProvider } from './components/ui';
import {
  AcceptInvitePage, AuthCallbackPage, ForgotPasswordPage, LoginPage, ResetPasswordPage, SignupPage, VerifyEmailPage,
} from './pages/auth/AuthPages';

// Route-level code splitting keeps the first load small.
const Dashboard = lazy(() => import('./pages/Dashboard'));
const Inbox = lazy(() => import('./pages/Inbox'));
const Pitches = lazy(() => import('./pages/Pitches'));
const PitchDetail = lazy(() => import('./pages/PitchDetail'));
const Pipeline = lazy(() => import('./pages/Pipeline'));
const Workflows = lazy(() => import('./pages/Workflows'));
const WorkflowDetail = lazy(() => import('./pages/WorkflowDetail'));
const Approvals = lazy(() => import('./pages/Approvals'));
const Calendar = lazy(() => import('./pages/Calendar'));
const Tasks = lazy(() => import('./pages/Tasks'));
const Notifications = lazy(() => import('./pages/Notifications'));
const Integrations = lazy(() => import('./pages/Integrations'));
const Settings = lazy(() => import('./pages/settings/Settings'));
const Onboarding = lazy(() => import('./pages/Onboarding'));

export default function App() {
  return (
    <BrowserRouter>
      <ToastProvider>
        <AuthProvider>
          <Suspense fallback={<div className="fullscreen-center"><Spinner /></div>}>
            <Routes>
              <Route path="/login" element={<LoginPage />} />
              <Route path="/signup" element={<SignupPage />} />
              <Route path="/forgot-password" element={<ForgotPasswordPage />} />
              <Route path="/reset-password" element={<ResetPasswordPage />} />
              <Route path="/verify-email" element={<VerifyEmailPage />} />
              <Route path="/invite/:token" element={<AcceptInvitePage />} />
              <Route path="/auth/callback" element={<AuthCallbackPage />} />

              <Route element={<RequireAuth />}>
                <Route path="/onboarding" element={<Onboarding />} />
                <Route element={<AppShell />}>
                  <Route path="/dashboard" element={<Dashboard />} />
                  <Route path="/inbox" element={<Inbox />} />
                  <Route path="/pitches" element={<Pitches />} />
                  <Route path="/pitches/:id" element={<PitchDetail />} />
                  <Route path="/pipeline" element={<Pipeline />} />
                  <Route path="/workflows" element={<Workflows />} />
                  <Route path="/workflows/:id" element={<WorkflowDetail />} />
                  <Route path="/approvals" element={<Approvals />} />
                  <Route path="/calendar" element={<Calendar />} />
                  <Route path="/tasks" element={<Tasks />} />
                  <Route path="/notifications" element={<Notifications />} />
                  <Route path="/integrations" element={<Integrations />} />
                  <Route path="/settings" element={<Navigate to="/settings/profile" replace />} />
                  <Route path="/settings/:section" element={<Settings />} />
                </Route>
              </Route>

              <Route path="/" element={<Navigate to="/dashboard" replace />} />
              <Route path="*" element={<Navigate to="/dashboard" replace />} />
            </Routes>
          </Suspense>
        </AuthProvider>
      </ToastProvider>
    </BrowserRouter>
  );
}
