import { useCallback, useRef, useState } from 'react';
import type { FormEvent, ReactNode } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { Icon } from '../ui/Icon';
import { Loading } from '../ui/Loading';
import { ErrorState } from '../ui/ErrorState';
import { EmptyState } from '../ui/EmptyState';
import { activityService } from '../../services/activityService';
import { useFetch } from '../../hooks/useFetch';
import { useClickOutside } from '../../hooks/useClickOutside';
import { useCurrentUser } from '../../hooks/useCurrentUser';
import { useLogout } from '../../hooks/useLogout';
import { initials, sortActivity, timeAgo } from '../../utils/format';

interface MenuProps {
  label: string;
  trigger: ReactNode;
  children: (close: () => void) => ReactNode;
}

function Menu({ label, trigger, children }: MenuProps) {
  const [open, setOpen] = useState(false);
  const ref = useRef<HTMLDivElement>(null);
  const close = useCallback(() => setOpen(false), []);
  useClickOutside(ref, close, open);

  return (
    <div className="menu" ref={ref}>
      <button
        type="button"
        className="menu-trigger"
        aria-label={label}
        aria-haspopup="true"
        aria-expanded={open}
        onClick={() => setOpen((v) => !v)}
      >
        {trigger}
      </button>
      {open && <div className="menu-panel">{children(close)}</div>}
    </div>
  );
}

function NotificationList() {
  const { data, loading, error, reload } = useFetch(activityService.list);
  if (loading) return <Loading />;
  if (error) return <ErrorState message={error} onRetry={reload} />;
  const items = sortActivity(data ?? []).slice(0, 6);
  if (items.length === 0) {
    return <EmptyState icon="bell" title="You're all caught up" description="New activity will show up here." />;
  }
  return (
    <ul className="list">
      {items.map((item) => (
        <li key={item.id} className="list-item">
          <div className="list-main">
            <p>{item.message}</p>
            <small className="muted">{timeAgo(item.createdAt)}</small>
          </div>
        </li>
      ))}
    </ul>
  );
}

interface NavbarProps {
  onMenuClick: () => void;
}

export function Navbar({ onMenuClick }: NavbarProps) {
  const navigate = useNavigate();
  const { user } = useCurrentUser();
  const logout = useLogout();
  const [term, setTerm] = useState('');

  const name = user?.name ?? 'Account';

  const handleSearch = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    const value = term.trim();
    navigate(value ? `/tasks?q=${encodeURIComponent(value)}` : '/tasks');
  };

  return (
    <header className="navbar">
      <button type="button" className="icon-btn menu-btn" aria-label="Open menu" onClick={onMenuClick}>
        <Icon name="menu" />
      </button>

      <form className="search" role="search" onSubmit={handleSearch}>
        <Icon name="search" size={16} />
        <input
          type="search"
          aria-label="Search tasks"
          placeholder="Search tasks…"
          value={term}
          onChange={(e) => setTerm(e.target.value)}
        />
      </form>

      <div className="navbar-right">
        <Menu label="Notifications" trigger={<Icon name="bell" />}>
          {() => (
            <div className="menu-content">
              <h3 className="menu-title">Recent activity</h3>
              <NotificationList />
            </div>
          )}
        </Menu>

        <Menu
          label="Account menu"
          trigger={
            <span className="user-btn">
              <span className="avatar">{initials(name)}</span>
              <span className="user-name">{name}</span>
            </span>
          }
        >
          {(close) => (
            <div className="menu-content menu-compact">
              <div className="menu-user">
                <strong>{user?.name ?? 'Signed in'}</strong>
                {user?.email && <small className="muted">{user.email}</small>}
              </div>
              <Link to="/settings" className="menu-item" onClick={close}>
                <Icon name="settings" size={16} /> Settings
              </Link>
              <button
                type="button"
                className="menu-item"
                onClick={() => {
                  close();
                  void logout();
                }}
              >
                <Icon name="logout" size={16} /> Log out
              </button>
            </div>
          )}
        </Menu>
      </div>
    </header>
  );
}