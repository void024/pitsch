import { useState } from 'react';
import type { FormEvent } from 'react';
import { PageContainer } from '../../components/layout/PageContainer';
import { Button } from '../../components/ui/Button';
import { Card } from '../../components/ui/Card';
import { ErrorState } from '../../components/ui/ErrorState';
import { Input, Select, Toggle } from '../../components/ui/Input';
import { Loading } from '../../components/ui/Loading';
import { userService } from '../../services/userService';
import { getErrorMessage } from '../../services/api';
import { useCurrentUser } from '../../hooks/useCurrentUser';
import { useFetch } from '../../hooks/useFetch';
import { useLogout } from '../../hooks/useLogout';
import type { NotificationSettings, TimeFormat, User, UserSettings } from '../../types';
import { applyPreferences } from '../../utils/format';

interface Feedback {
  type: 'success' | 'error';
  text: string;
}

function FeedbackMessage({ feedback }: { feedback: Feedback | null }) {
  if (!feedback) return null;
  return (
    <p className={feedback.type === 'success' ? 'form-success' : 'form-error'} role="status">
      {feedback.text}
    </p>
  );
}

interface ProfileFormProps {
  user: User;
  onSaved: () => void;
}

function ProfileForm({ user, onSaved }: ProfileFormProps) {
  const [name, setName] = useState(user.name);
  const [email, setEmail] = useState(user.email);
  const [errors, setErrors] = useState<{ name?: string; email?: string }>({});
  const [saving, setSaving] = useState(false);
  const [feedback, setFeedback] = useState<Feedback | null>(null);

  const handleSubmit = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    const next: { name?: string; email?: string } = {};
    if (!name.trim()) next.name = 'Name is required.';
    if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email.trim())) next.email = 'Enter a valid email address.';
    setErrors(next);
    setFeedback(null);
    if (Object.keys(next).length > 0) return;

    setSaving(true);
    try {
      await userService.updateProfile({ name: name.trim(), email: email.trim() });
      setFeedback({ type: 'success', text: 'Profile updated.' });
      onSaved();
    } catch (err: unknown) {
      setFeedback({ type: 'error', text: getErrorMessage(err) });
    } finally {
      setSaving(false);
    }
  };

  return (
    <form className="form" onSubmit={handleSubmit} noValidate>
      <div className="form-row">
        <Input label="Name" value={name} onChange={(e) => setName(e.target.value)} error={errors.name} />
        <Input
          label="Email"
          type="email"
          value={email}
          onChange={(e) => setEmail(e.target.value)}
          error={errors.email}
        />
      </div>
      <FeedbackMessage feedback={feedback} />
      <div className="form-actions">
        <Button type="submit" loading={saving}>
          Save profile
        </Button>
      </div>
    </form>
  );
}

interface SettingsFormProps {
  settings: UserSettings;
}

function SettingsForm({ settings }: SettingsFormProps) {
  const [timeFormat, setTimeFormat] = useState<TimeFormat>(settings.timeFormat);
  const [compactMode, setCompactMode] = useState(settings.compactMode);
  const [notifications, setNotifications] = useState<NotificationSettings>(settings.notifications);
  const [saving, setSaving] = useState(false);
  const [feedback, setFeedback] = useState<Feedback | null>(null);

  const setNotification = (key: keyof NotificationSettings, value: boolean) =>
    setNotifications((prev) => ({ ...prev, [key]: value }));

  const handleSubmit = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    setSaving(true);
    setFeedback(null);
    try {
      const saved = await userService.updateSettings({ timeFormat, compactMode, notifications });
      applyPreferences(saved);
      setFeedback({ type: 'success', text: 'Preferences saved.' });
    } catch (err: unknown) {
      setFeedback({ type: 'error', text: getErrorMessage(err) });
    } finally {
      setSaving(false);
    }
  };

  return (
    <form className="form" onSubmit={handleSubmit}>
      <h3 className="section-title">Application preferences</h3>
      <Select label="Time format" value={timeFormat} onChange={(e) => setTimeFormat(e.target.value as TimeFormat)}>
        <option value="12h">12-hour (3:30 PM)</option>
        <option value="24h">24-hour (15:30)</option>
      </Select>
      <Toggle
        label="Compact mode"
        description="Use tighter spacing throughout the app."
        checked={compactMode}
        onChange={setCompactMode}
      />

      <h3 className="section-title">Notification preferences</h3>
      <Toggle
        label="Email notifications"
        description="Receive important updates by email."
        checked={notifications.email}
        onChange={(v) => setNotification('email', v)}
      />
      <Toggle
        label="Task reminders"
        description="Remind me before tasks are due."
        checked={notifications.taskReminders}
        onChange={(v) => setNotification('taskReminders', v)}
      />
      <Toggle
        label="Event reminders"
        description="Remind me before events start."
        checked={notifications.eventReminders}
        onChange={(v) => setNotification('eventReminders', v)}
      />
      <Toggle
        label="Workflow updates"
        description="Notify me when an AI workflow finishes or fails."
        checked={notifications.workflowUpdates}
        onChange={(v) => setNotification('workflowUpdates', v)}
      />

      <FeedbackMessage feedback={feedback} />
      <div className="form-actions">
        <Button type="submit" loading={saving}>
          Save preferences
        </Button>
      </div>
    </form>
  );
}

export default function Settings() {
  const currentUser = useCurrentUser();
  const settings = useFetch(userService.getSettings);
  const logout = useLogout();
  const [loggingOut, setLoggingOut] = useState(false);

  const handleLogout = async () => {
    setLoggingOut(true);
    await logout();
  };

  return (
    <PageContainer title="Settings" description="Manage your profile and how Pitsch works for you.">
      <div className="settings-grid">
        <Card title="Profile">
          {currentUser.loading ? (
            <Loading />
          ) : currentUser.error || !currentUser.user ? (
            <ErrorState message={currentUser.error ?? 'Unable to load your profile.'} onRetry={currentUser.reload} />
          ) : (
            <ProfileForm user={currentUser.user} onSaved={currentUser.reload} />
          )}
        </Card>

        <Card title="Preferences">
          {settings.loading ? (
            <Loading />
          ) : settings.error || !settings.data ? (
            <ErrorState message={settings.error ?? 'Unable to load your settings.'} onRetry={settings.reload} />
          ) : (
            <SettingsForm settings={settings.data} />
          )}
        </Card>

        <Card title="Account">
          <p className="muted">Sign out of Pitsch on this device.</p>
          <div className="form-actions">
            <Button variant="danger" icon="logout" loading={loggingOut} onClick={() => void handleLogout()}>
              Log out
            </Button>
          </div>
        </Card>
      </div>
    </PageContainer>
  );
}