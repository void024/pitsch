import { useEffect, useState } from 'react';
import { Outlet } from 'react-router-dom';
import { Navbar } from './Navbar';
import { Sidebar } from './Sidebar';
import { useFetch } from '../../hooks/useFetch';
import { userService } from '../../services/userService';
import { applyPreferences } from '../../utils/format';

export function AppLayout() {
  const [menuOpen, setMenuOpen] = useState(false);
  const { data: settings } = useFetch(userService.getSettings);

  useEffect(() => {
    if (settings) applyPreferences(settings);
  }, [settings]);

  return (
    <div className="app-shell">
      <Sidebar open={menuOpen} onClose={() => setMenuOpen(false)} />
      <div className="app-main">
        <Navbar onMenuClick={() => setMenuOpen(true)} />
        <main className="app-content">
          <Outlet />
        </main>
      </div>
    </div>
  );
}