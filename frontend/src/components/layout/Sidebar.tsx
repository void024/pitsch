import { NavLink } from 'react-router-dom';
import { Icon, type IconName } from '../ui/Icon';
import { cx } from '../../utils/format';

interface NavItem {
  to: string;
  label: string;
  icon: IconName;
}

const NAV_ITEMS: NavItem[] = [
  { to: '/dashboard', label: 'Dashboard', icon: 'dashboard' },
  { to: '/tasks', label: 'Tasks', icon: 'tasks' },
  { to: '/calendar', label: 'Calendar', icon: 'calendar' },
  { to: '/ai', label: 'AI / Workflows', icon: 'ai' },
  { to: '/settings', label: 'Settings', icon: 'settings' },
];

interface SidebarProps {
  open: boolean;
  onClose: () => void;
}

export function Sidebar({ open, onClose }: SidebarProps) {
  return (
    <>
      <div className={cx('sidebar-overlay', open && 'visible')} onClick={onClose} aria-hidden="true" />
      <aside className={cx('sidebar', open && 'open')} aria-label="Main navigation">
        <div className="brand">
          <span className="brand-mark">P</span>
          <span className="brand-name">Pitsch</span>
          <button type="button" className="icon-btn sidebar-close" aria-label="Close menu" onClick={onClose}>
            <Icon name="close" />
          </button>
        </div>
        <nav>
          <ul className="nav-list">
            {NAV_ITEMS.map((item) => (
              <li key={item.to}>
                <NavLink
                  to={item.to}
                  onClick={onClose}
                  className={({ isActive }) => cx('nav-link', isActive && 'active')}
                >
                  <Icon name={item.icon} />
                  <span>{item.label}</span>
                </NavLink>
              </li>
            ))}
          </ul>
        </nav>
      </aside>
    </>
  );
}