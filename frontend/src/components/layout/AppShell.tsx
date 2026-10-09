import { useState } from 'react';
import { Link, NavLink, Navigate, Outlet, useLocation, useNavigate } from 'react-router-dom';
import { notifications as notificationsApi, auth as authApi } from '../../lib/api/endpoints';
import { useAuth } from '../../lib/auth/AuthContext';
import { useAsync, useInterval } from '../../lib/hooks';
import { ROLE_LABEL, cx } from '../../lib/format';
import type { Permission } from '../../lib/api/types';
import { Avatar, Button, Spinner, useToast } from '../ui';

interface NavItem { to: string; label: string; icon: string; permission?: Permission }

const NAV: { section: string; items: NavItem[] }[] = [
  { section: 'Work', items: [
    { to: '/dashboard', label: 'Dashboard', icon: '◧' },
    { to: '/inbox', label: 'Inbox', icon: '✉', permission: 'PITCH_READ' },
    { to: '/workflows', label: 'Workflows', icon: '⟳', permission: 'PITCH_READ' },
    { to: '/approvals', label: 'Approvals', icon: '✓', permission: 'PITCH_READ' },
  ] },
  { section: 'Deals', items: [
    { to: '/pitches', label: 'Pitches', icon: '◆', permission: 'PITCH_READ' },
    { to: '/pipeline', label: 'Pipeline', icon: '▤', permission: 'PITCH_READ' },
  ] },
  { section: 'Schedule', items: [
    { to: '/calendar', label: 'Calendar', icon: '▦', permission: 'CALENDAR_READ' },
    { to: '/tasks', label: 'Tasks', icon: '☰', permission: 'TASK_READ' },
  ] },
  { section: 'Workspace', items: [
    { to: '/notifications', label: 'Notifications', icon: '◔' },
    { to: '/integrations', label: 'Integrations', icon: '⇄' },
    { to: '/settings', label: 'Settings', icon: '⚙' },
  ] },
];

export function RequireAuth() {
  const { status, me } = useAuth();
  const location = useLocation();
  if (status === 'loading') {
    return <div className="fullscreen-center"><Spinner /></div>;
  }
  if (status === 'anonymous' || !me) {
    const returnTo = encodeURIComponent(location.pathname + location.search);
    return <Navigate to={`/login?returnTo=${returnTo}`} replace />;
  }
  if (!me.workspace && location.pathname !== '/onboarding') {
    return <Navigate to="/onboarding" replace />;
  }
  return <Outlet />;
}

export function AppShell() {
  const { me, can, logout, switchWorkspace, refresh } = useAuth();
  const navigate = useNavigate();
  const toast = useToast();
  const [menuOpen, setMenuOpen] = useState(false);
  const [navOpen, setNavOpen] = useState(false);
  const unread = useAsync(() => notificationsApi.unreadCount(), []);
  useInterval(unread.reload, 60_000);

  if (!me) return null;
  const ws = me.workspace;

  const onSwitch = async (orgId: number) => {
    try {
      await switchWorkspace(orgId);
      setMenuOpen(false);
      navigate('/dashboard');
    } catch (e) {
      toast(e instanceof Error ? e.message : 'Could not switch workspace', 'error');
    }
  };

  const resend = async () => {
    try {
      await authApi.resendVerification();
      toast('Verification email sent.', 'success');
    } catch (e) {
      toast(e instanceof Error ? e.message : 'Could not send the email', 'error');
    }
  };

  return (
    <div className={cx('shell', navOpen && 'nav-open')}>
      <aside className="sidebar" aria-label="Main navigation">
        <Link to="/dashboard" className="brand" onClick={() => setNavOpen(false)}>
          <span className="brand-mark">P</span>
          <span className="brand-name">Pitsch</span>
        </Link>
        <nav>
          {NAV.map((group) => {
            const items = group.items.filter((i) => !i.permission || can(i.permission));
            if (!items.length) return null;
            return (
              <div key={group.section} className="nav-group">
                <span className="nav-section">{group.section}</span>
                {items.map((item) => (
                  <NavLink key={item.to} to={item.to} onClick={() => setNavOpen(false)}
                    className={({ isActive }) => cx('nav-link', isActive && 'nav-link-active')}>
                    <span className="nav-icon" aria-hidden>{item.icon}</span>
                    <span>{item.label}</span>
                    {item.to === '/notifications' && (unread.data?.count ?? 0) > 0 && (
                      <span className="nav-count">{unread.data!.count > 99 ? '99+' : unread.data!.count}</span>
                    )}
                  </NavLink>
                ))}
              </div>
            );
          })}
        </nav>
        <div className="sidebar-footer muted small">
          {ws ? <>Plan: <strong>{ws.plan}</strong></> : null}
        </div>
      </aside>

      <div className="main">
        <header className="topbar">
          <button type="button" className="icon-btn nav-toggle" aria-label="Open navigation" onClick={() => setNavOpen((v) => !v)}>☰</button>
          <div className="workspace-name">
            {ws ? <><strong>{ws.name}</strong><span className="muted small">{ROLE_LABEL[ws.role]}</span></> : 'No workspace'}
          </div>
          <div className="topbar-right">
            <Link to="/notifications" className="icon-btn bell" aria-label={`Notifications (${unread.data?.count ?? 0} unread)`}>
              ◔{(unread.data?.count ?? 0) > 0 && <span className="dot" />}
            </Link>
            <div className="user-menu">
              <button type="button" className="user-button" onClick={() => setMenuOpen((v) => !v)} aria-expanded={menuOpen}>
                <Avatar name={me.user.name} size={30} />
                <span className="user-name">{me.user.name}</span>
              </button>
              {menuOpen && (
                <div className="menu" role="menu" onMouseLeave={() => setMenuOpen(false)}>
                  <div className="menu-header">
                    <strong>{me.user.name}</strong>
                    <span className="muted small">{me.user.email}</span>
                  </div>
                  {me.memberships.length > 1 && (
                    <>
                      <span className="menu-section">Workspaces</span>
                      {me.memberships.map((m) => (
                        <button key={m.organizationId} type="button" role="menuitem"
                          className={cx('menu-item', m.organizationId === ws?.id && 'menu-item-active')}
                          onClick={() => onSwitch(m.organizationId)}>
                          {m.organizationName} <span className="muted small">{ROLE_LABEL[m.role]}</span>
                        </button>
                      ))}
                    </>
                  )}
                  <Link className="menu-item" role="menuitem" to="/settings" onClick={() => setMenuOpen(false)}>Settings</Link>
                  <button type="button" className="menu-item" role="menuitem" onClick={async () => {
                    await logout();
                    navigate('/login');
                  }}>Sign out</button>
                </div>
              )}
            </div>
          </div>
        </header>

        {me.features.demo && (
          <div className="banner banner-demo" role="status">
            <strong>Demo mode.</strong> Gmail, Calendar and Sheets are simulated: nothing is sent to Google and no
            invitations go out. Actions are labelled “Demo” in the audit log.
          </div>
        )}
        {!me.user.emailVerified && (
          <div className="banner banner-warn" role="status">
            Verify your email address to unlock every feature. <Button size="sm" variant="ghost" onClick={resend}>Resend email</Button>
            <Button size="sm" variant="ghost" onClick={() => void refresh()}>I’ve verified</Button>
          </div>
        )}

        <main className="content" id="main">
          <Outlet />
        </main>
      </div>
    </div>
  );
}
