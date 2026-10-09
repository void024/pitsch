import { useEffect, useState, type FormEvent } from 'react';
import { NavLink, useNavigate, useParams, useSearchParams } from 'react-router-dom';
import { ApiError } from '../../lib/api/client';
import {
  audit as auditApi, auth as authApi, billing as billingApi, notifications as notificationsApi, privacy as privacyApi,
  profile as profileApi, workspace as workspaceApi,
} from '../../lib/api/endpoints';
import type { ActionPolicy, NotificationPreference, Permission, Role } from '../../lib/api/types';
import { useAuth } from '../../lib/auth/AuthContext';
import { downloadResponse, useAsync } from '../../lib/hooks';
import { ROLE_LABEL, cx, formatDate, formatDateTime, humanize, timeAgo } from '../../lib/format';
import { safeHttpUrl } from '../../lib/safeUrl';
import {
  Badge, Button, Card, Checkbox, ConfirmDialog, ErrorState, InlineError, PageHeader, Pagination, SelectInput, Skeleton,
  TextInput, useToast,
} from '../../components/ui';

const SECTIONS: { key: string; label: string; permission?: Permission }[] = [
  { key: 'profile', label: 'Profile' },
  { key: 'security', label: 'Security' },
  { key: 'workspace', label: 'Workspace', permission: 'ORG_SETTINGS' },
  { key: 'members', label: 'Members', permission: 'MEMBER_READ' },
  { key: 'notifications', label: 'Notifications' },
  { key: 'billing', label: 'Plan & usage', permission: 'USAGE_READ' },
  { key: 'privacy', label: 'Data & privacy' },
  { key: 'audit', label: 'Audit log', permission: 'AUDIT_READ' },
];

export default function Settings() {
  const { section = 'profile' } = useParams();
  const { can } = useAuth();
  const visible = SECTIONS.filter((s) => !s.permission || can(s.permission));
  return (
    <div className="stack-lg">
      <PageHeader title="Settings" />
      <div className="settings">
        <nav className="settings-nav" aria-label="Settings sections">
          {visible.map((s) => (
            <NavLink key={s.key} to={`/settings/${s.key}`} className={({ isActive }) => cx('settings-link', (isActive || (section === s.key)) && 'settings-link-active')}>{s.label}</NavLink>
          ))}
        </nav>
        <div className="settings-body">
          {section === 'profile' && <ProfileSection />}
          {section === 'security' && <SecuritySection />}
          {section === 'workspace' && can('ORG_SETTINGS') && <WorkspaceSection />}
          {section === 'members' && can('MEMBER_READ') && <MembersSection />}
          {section === 'notifications' && <NotificationSection />}
          {section === 'billing' && can('USAGE_READ') && <BillingSection />}
          {section === 'privacy' && <PrivacySection />}
          {section === 'audit' && can('AUDIT_READ') && <AuditSection />}
        </div>
      </div>
    </div>
  );
}

/* ---------------- profile ---------------- */
function ProfileSection() {
  const { me, refresh } = useAuth();
  const toast = useToast();
  const settings = useAsync(() => profileApi.settings(), []);
  const [name, setName] = useState(me?.user.name ?? '');
  const [email, setEmail] = useState(me?.user.email ?? '');
  const [currentPassword, setCurrentPassword] = useState('');
  const [error, setError] = useState<unknown>(null);
  const [busy, setBusy] = useState(false);
  const emailChanged = email.trim().toLowerCase() !== me?.user.email.toLowerCase();

  const save = async (e: FormEvent) => {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      await profileApi.update({ name: name.trim(), email: emailChanged ? email.trim() : undefined, currentPassword: emailChanged ? currentPassword : undefined });
      if (settings.data) await profileApi.saveSettings(settings.data);
      await refresh();
      toast(emailChanged ? 'Saved. Confirm the new address from the email we sent.' : 'Profile saved', 'success');
      setCurrentPassword('');
    } catch (err) {
      setError(err);
    } finally {
      setBusy(false);
    }
  };

  const s = settings.data;
  return (
    <Card title="Profile">
      <form className="stack" onSubmit={save}>
        <div className="grid-2">
          <TextInput label="Name" value={name} onChange={(e) => setName(e.target.value)} required />
          <TextInput label="Email" type="email" value={email} onChange={(e) => setEmail(e.target.value)} required
            hint={me?.user.emailVerified ? 'Verified' : 'Not verified yet'} />
        </div>
        {emailChanged && <TextInput label="Current password (required to change email)" type="password" value={currentPassword} onChange={(e) => setCurrentPassword(e.target.value)} />}
        {s && (
          <div className="grid-2">
            <TextInput label="Firm name (used in email signatures)" value={s.firmName ?? ''} onChange={(e) => settings.setData({ ...s, firmName: e.target.value })} />
            <TextInput label="Title" value={s.investorTitle ?? ''} onChange={(e) => settings.setData({ ...s, investorTitle: e.target.value })} />
            <SelectInput label="Time format" value={s.timeFormat} onChange={(e) => settings.setData({ ...s, timeFormat: e.target.value as '12h' | '24h' })}
              options={[{ value: '24h', label: '24-hour' }, { value: '12h', label: '12-hour' }]} />
            <TextInput label="Personal time zone (meeting slots)" value={s.timezone ?? ''} placeholder="Workspace default" onChange={(e) => settings.setData({ ...s, timezone: e.target.value || null })} />
          </div>
        )}
        <InlineError error={error} />
        <div><Button variant="primary" type="submit" loading={busy}>Save</Button></div>
      </form>
    </Card>
  );
}

/* ---------------- security ---------------- */
function SecuritySection() {
  const toast = useToast();
  const sessions = useAsync(() => authApi.sessions(), []);
  const [current, setCurrent] = useState('');
  const [next, setNext] = useState('');
  const [error, setError] = useState<unknown>(null);
  const [busy, setBusy] = useState(false);
  const change = async (e: FormEvent) => {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      await authApi.changePassword(current, next);
      setCurrent(''); setNext('');
      toast('Password changed. Other sessions were signed out.', 'success');
      sessions.reload();
    } catch (err) {
      setError(err);
    } finally {
      setBusy(false);
    }
  };
  return (
    <div className="stack-lg">
      <Card title="Change password">
        <form className="stack" onSubmit={change}>
          <TextInput label="Current password" type="password" autoComplete="current-password" value={current} onChange={(e) => setCurrent(e.target.value)} hint="Leave empty if you signed up with Google and have no password yet." />
          <TextInput label="New password" type="password" autoComplete="new-password" required value={next} onChange={(e) => setNext(e.target.value)} hint="At least 10 characters, mixing letters with numbers or symbols." />
          <InlineError error={error} />
          <div><Button variant="primary" type="submit" loading={busy}>Change password</Button></div>
        </form>
      </Card>
      <Card title="Active sessions">
        {sessions.error ? <ErrorState error={sessions.error} /> : !sessions.data ? <Skeleton /> : (
          <ul className="list">
            {sessions.data.map((s) => (
              <li key={s.id} className="list-row">
                <div>
                  <strong>{s.userAgent ? s.userAgent.slice(0, 80) : 'Unknown device'}</strong>
                  <div className="muted small">{s.ipAddress || 'unknown IP'} · last active {timeAgo(s.lastSeenAt)} · since {formatDate(s.createdAt)}</div>
                </div>
                {s.current ? <Badge tone="green">This device</Badge> : (
                  <Button size="sm" variant="ghost" onClick={async () => { await authApi.revokeSession(s.id); sessions.reload(); }}>Sign out</Button>
                )}
              </li>
            ))}
          </ul>
        )}
      </Card>
    </div>
  );
}

/* ---------------- workspace ---------------- */
const POLICY_OPTIONS = [
  { value: 'AUTO', label: 'Automatically' }, { value: 'APPROVAL', label: 'Ask me first' }, { value: 'OFF', label: 'Never' },
];

function WorkspaceSection() {
  const toast = useToast();
  const { refresh } = useAuth();
  const ws = useAsync(() => workspaceApi.get(), []);
  const [error, setError] = useState<unknown>(null);
  const [busy, setBusy] = useState(false);
  if (ws.error) return <ErrorState error={ws.error} onRetry={ws.reload} />;
  if (!ws.data) return <Skeleton />;
  const w = ws.data;
  const set = (patch: Partial<typeof w>) => ws.setData({ ...w, ...patch });
  const save = async () => {
    setBusy(true);
    setError(null);
    try {
      ws.setData(await workspaceApi.update({
        name: w.name, timezone: w.timezone, workingHoursStart: w.workingHoursStart, workingHoursEnd: w.workingHoursEnd,
        workingDays: w.workingDays, meetingDurationMinutes: w.meetingDurationMinutes,
        dataRetentionDays: w.dataRetentionDays ?? undefined, clearDataRetention: w.dataRetentionDays == null,
        gmailLabelPolicy: w.gmailLabelPolicy, sheetsSyncPolicy: w.sheetsSyncPolicy,
      }));
      await refresh();
      toast('Workspace saved', 'success');
    } catch (e) {
      setError(e);
    } finally {
      setBusy(false);
    }
  };
  return (
    <div className="stack-lg">
      <Card title="Workspace">
        <div className="grid-2">
          <TextInput label="Name" value={w.name} onChange={(e) => set({ name: e.target.value })} />
          <TextInput label="Time zone" value={w.timezone} onChange={(e) => set({ timezone: e.target.value })} hint="IANA name, e.g. Europe/Berlin" />
          <TextInput label="Working day starts" type="time" value={w.workingHoursStart} onChange={(e) => set({ workingHoursStart: e.target.value })} />
          <TextInput label="Working day ends" type="time" value={w.workingHoursEnd} onChange={(e) => set({ workingHoursEnd: e.target.value })} />
          <TextInput label="Default meeting length (minutes)" type="number" min={15} max={240} value={w.meetingDurationMinutes} onChange={(e) => set({ meetingDurationMinutes: Number(e.target.value) })} />
          <TextInput label="Data retention (days, empty = keep)" type="number" min={30} value={w.dataRetentionDays ?? ''}
            onChange={(e) => set({ dataRetentionDays: e.target.value ? Number(e.target.value) : null })}
            hint="Email bodies and raw agent outputs older than this are removed; briefs and pitch records stay." />
        </div>
        <div className="chips" role="group" aria-label="Working days">
          {['MON', 'TUE', 'WED', 'THU', 'FRI', 'SAT', 'SUN'].map((d) => (
            <button key={d} type="button" className={cx('chip', w.workingDays.includes(d) && 'chip-on')}
              onClick={() => set({ workingDays: w.workingDays.includes(d) ? w.workingDays.filter((x) => x !== d) : [...w.workingDays, d] })}>{d}</button>
          ))}
        </div>
      </Card>
      <Card title="What may Pitsch do without asking?">
        <p className="muted">Sending email and creating or cancelling meetings always require your approval. These lower-risk actions are configurable.</p>
        <div className="grid-2">
          <SelectInput label="Apply Pitsch labels in Gmail" value={w.gmailLabelPolicy} onChange={(e) => set({ gmailLabelPolicy: e.target.value as ActionPolicy })} options={POLICY_OPTIONS} />
          <SelectInput label="Update the pipeline spreadsheet" value={w.sheetsSyncPolicy} onChange={(e) => set({ sheetsSyncPolicy: e.target.value as ActionPolicy })} options={POLICY_OPTIONS} />
        </div>
      </Card>
      <InlineError error={error} />
      <div><Button variant="primary" onClick={save} loading={busy}>Save workspace</Button></div>
    </div>
  );
}

/* ---------------- members ---------------- */
const ROLES: Role[] = ['OWNER', 'ADMIN', 'INVESTOR', 'ANALYST', 'MEMBER'];
const ROLE_HELP: Record<Role, string> = {
  OWNER: 'Everything, including billing and deleting the workspace.',
  ADMIN: 'Everything except billing and deleting the workspace.',
  INVESTOR: 'Work deals end to end, approve emails and meetings.',
  ANALYST: 'Import emails, run agents and edit pitches; cannot approve outbound actions.',
  MEMBER: 'Read pitches and work on tasks.',
};

function MembersSection() {
  const { can, me } = useAuth();
  const toast = useToast();
  const members = useAsync(() => workspaceApi.members(), []);
  const invites = useAsync(() => (can('MEMBER_MANAGE') ? workspaceApi.invitations() : Promise.resolve([])), []);
  const [email, setEmail] = useState('');
  const [role, setRole] = useState<Role>('ANALYST');
  const [error, setError] = useState<unknown>(null);
  const [removing, setRemoving] = useState<number | null>(null);

  const invite = async (e: FormEvent) => {
    e.preventDefault();
    setError(null);
    try {
      await workspaceApi.invite(email.trim(), role);
      setEmail('');
      invites.reload();
      toast('Invitation sent', 'success');
    } catch (err) {
      setError(err);
    }
  };
  const changeRole = async (membershipId: number, r: Role) => {
    try {
      await workspaceApi.changeRole(membershipId, r);
      members.reload();
    } catch (err) {
      toast(err instanceof Error ? err.message : 'Could not change role', 'error');
    }
  };
  const remove = async () => {
    if (removing == null) return;
    try {
      await workspaceApi.removeMember(removing);
      members.reload();
    } catch (err) {
      toast(err instanceof Error ? err.message : 'Could not remove', 'error');
    } finally {
      setRemoving(null);
    }
  };
  return (
    <div className="stack-lg">
      {can('MEMBER_MANAGE') && (
        <Card title="Invite a teammate">
          <form className="row gap-sm wrap align-end" onSubmit={invite}>
            <div className="grow"><TextInput label="Email" type="email" required value={email} onChange={(e) => setEmail(e.target.value)} /></div>
            <SelectInput label="Role" value={role} onChange={(e) => setRole(e.target.value as Role)}
              options={ROLES.filter((r) => r !== 'OWNER' || me?.workspace?.role === 'OWNER').map((r) => ({ value: r, label: ROLE_LABEL[r] }))} />
            <Button variant="primary" type="submit">Send invitation</Button>
          </form>
          <p className="muted small">{ROLE_HELP[role]}</p>
          <InlineError error={error} />
        </Card>
      )}
      <Card title="Members" padded={false}>
        {members.error ? <ErrorState error={members.error} /> : !members.data ? <div className="pad"><Skeleton /></div> : (
          <ul className="list">
            {members.data.map((m) => (
              <li key={m.membershipId} className="list-row">
                <div><strong>{m.name}</strong>{m.userId === me?.user.id && <span className="muted small"> (you)</span>}<div className="muted small">{m.email} · joined {formatDate(m.joinedAt)}</div></div>
                <div className="row gap-sm">
                  {can('MEMBER_MANAGE') ? (
                    <select className="input input-sm" aria-label={`Role of ${m.name}`} value={m.role} onChange={(e) => changeRole(m.membershipId, e.target.value as Role)}>
                      {ROLES.map((r) => <option key={r} value={r}>{ROLE_LABEL[r]}</option>)}
                    </select>
                  ) : <Badge>{ROLE_LABEL[m.role]}</Badge>}
                  {(can('MEMBER_MANAGE') || m.userId === me?.user.id) && (
                    <Button size="sm" variant="ghost" onClick={() => setRemoving(m.membershipId)}>{m.userId === me?.user.id ? 'Leave' : 'Remove'}</Button>
                  )}
                </div>
              </li>
            ))}
          </ul>
        )}
      </Card>
      {(invites.data?.length ?? 0) > 0 && (
        <Card title="Pending invitations">
          <ul className="list">
            {invites.data!.map((i) => (
              <li key={i.id} className="list-row">
                <div><strong>{i.email}</strong><div className="muted small">{ROLE_LABEL[i.role]} · expires {formatDate(i.expiresAt)}</div></div>
                <Button size="sm" variant="ghost" onClick={async () => { await workspaceApi.revokeInvitation(i.id); invites.reload(); }}>Revoke</Button>
              </li>
            ))}
          </ul>
        </Card>
      )}
      <ConfirmDialog open={removing != null} title="Remove this member?" danger confirmLabel="Remove" onCancel={() => setRemoving(null)} onConfirm={remove}>
        <p>They lose access to this workspace immediately. Their past actions stay in the audit log.</p>
      </ConfirmDialog>
    </div>
  );
}

/* ---------------- notifications ---------------- */
function NotificationSection() {
  const toast = useToast();
  const prefs = useAsync(() => notificationsApi.preferences(), []);
  const [busy, setBusy] = useState(false);
  if (prefs.error) return <ErrorState error={prefs.error} />;
  if (!prefs.data) return <Skeleton />;
  const update = (type: string, patch: Partial<NotificationPreference>) =>
    prefs.setData(prefs.data!.map((p) => (p.type === type ? { ...p, ...patch } : p)));
  const save = async () => {
    setBusy(true);
    try {
      prefs.setData(await notificationsApi.savePreferences(prefs.data!));
      toast('Preferences saved', 'success');
    } catch (e) {
      toast(e instanceof Error ? e.message : 'Could not save', 'error');
    } finally {
      setBusy(false);
    }
  };
  return (
    <Card title="Notification preferences" actions={<Button size="sm" onClick={async () => { await notificationsApi.test(); toast('Test notification sent', 'info'); }}>Send test</Button>}>
      <table className="table">
        <thead><tr><th>Event</th><th>In app</th><th>Email</th></tr></thead>
        <tbody>
          {prefs.data.map((p) => (
            <tr key={p.type}>
              <td>{humanize(p.type)}</td>
              <td><Checkbox label="" checked={p.inApp} onChange={(v) => update(p.type, { inApp: v })} /></td>
              <td><Checkbox label="" checked={p.email} onChange={(v) => update(p.type, { email: v })} /></td>
            </tr>
          ))}
        </tbody>
      </table>
      <div><Button variant="primary" onClick={save} loading={busy}>Save</Button></div>
    </Card>
  );
}

/* ---------------- billing ---------------- */
function BillingSection() {
  const { can } = useAuth();
  const toast = useToast();
  const [params] = useSearchParams();
  const view = useAsync(() => billingApi.get(), []);
  useEffect(() => {
    const checkout = params.get('checkout');
    if (checkout === 'success') toast('Thanks! Your plan updates as soon as the payment is confirmed.', 'success');
    if (checkout === 'cancelled') toast('Checkout cancelled.', 'info');
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);
  const go = async (fn: () => Promise<{ url: string }>) => {
    try {
      const { url } = await fn();
      const safe = safeHttpUrl(url);
      if (safe && new URL(safe).hostname.endsWith('stripe.com')) window.location.assign(safe);
      else throw new Error('Unexpected billing address.');
    } catch (e) {
      toast(e instanceof Error ? e.message : 'Billing is unavailable', 'error');
    }
  };
  if (view.error) return <ErrorState error={view.error} onRetry={view.reload} />;
  if (!view.data) return <Skeleton />;
  const b = view.data;
  return (
    <div className="stack-lg">
      <Card title="Current plan" actions={b.hasBillingAccount && can('BILLING_MANAGE') && <Button onClick={() => go(billingApi.portal)}>Manage billing</Button>}>
        <p><strong>{b.effectivePlanCode}</strong> {b.planCode !== b.effectivePlanCode && <span className="muted small">(subscribed: {b.planCode}, status {humanize(b.status)})</span>}</p>
        {b.currentPeriodEnd && <p className="muted small">{b.cancelAtPeriodEnd ? 'Ends' : 'Renews'} {formatDate(b.currentPeriodEnd)}</p>}
        <div className="usage">
          {Object.values(b.usage).map((q) => (
            <div key={q.metric} className="usage-row">
              <span>{humanize(q.metric)}</span>
              <span className="usage-track"><span style={{ width: q.unlimited ? '0%' : `${Math.min(100, (q.used / Math.max(1, q.limit)) * 100)}%` }} /></span>
              <span className="muted small">{q.used}{q.unlimited ? ' (unlimited)' : ` / ${q.limit}`}</span>
            </div>
          ))}
        </div>
        <p className="muted small">Usage resets monthly. Members and storage are current totals.</p>
      </Card>
      <div className="plans">
        {b.plans.map((p) => (
          <Card key={p.code} title={p.name} className={p.code === b.effectivePlanCode ? 'plan-current' : undefined}>
            <p className="plan-price">{p.monthlyPriceCents ? `$${(p.monthlyPriceCents / 100).toFixed(0)}/mo` : p.code === 'ENTERPRISE' ? 'Custom' : 'Free'}</p>
            <ul className="bullets small">
              {Object.entries(p.limits).map(([k, v]) => <li key={k}>{humanize(k)}: {v < 0 ? 'unlimited' : v}</li>)}
            </ul>
            {p.code !== b.effectivePlanCode && can('BILLING_MANAGE') && (p.purchasable
              ? <Button variant="primary" onClick={() => go(() => billingApi.checkout(p.code))}>Upgrade</Button>
              : p.code !== 'FREE' && <span className="muted small">{b.onlineBillingEnabled ? 'Contact sales' : 'Online billing is not enabled on this deployment.'}</span>)}
          </Card>
        ))}
      </div>
    </div>
  );
}

/* ---------------- privacy ---------------- */
function PrivacySection() {
  const { can, me, logout } = useAuth();
  const toast = useToast();
  const navigate = useNavigate();
  const [busy, setBusy] = useState<string | null>(null);
  const [deleteWs, setDeleteWs] = useState(false);
  const [deleteMe, setDeleteMe] = useState(false);
  const [confirmName, setConfirmName] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState<unknown>(null);

  const exportData = async (kind: 'workspace' | 'account') => {
    setBusy(kind);
    try {
      const res = kind === 'workspace' ? await privacyApi.exportWorkspace() : await privacyApi.exportAccount();
      await downloadResponse(res, kind === 'workspace' ? 'pitsch-workspace.zip' : 'pitsch-account.json');
    } catch (e) {
      toast(e instanceof ApiError ? e.message : 'Export failed', 'error');
    } finally {
      setBusy(null);
    }
  };
  const removeWorkspace = async () => {
    setBusy('delete-ws');
    setError(null);
    try {
      await privacyApi.deleteWorkspace(confirmName, password);
      toast('Workspace scheduled for deletion.', 'info');
      setDeleteWs(false);
      navigate('/dashboard');
      window.location.reload();
    } catch (e) {
      setError(e);
    } finally {
      setBusy(null);
    }
  };
  const removeAccount = async () => {
    setBusy('delete-me');
    setError(null);
    try {
      await privacyApi.deleteAccount(password);
      await logout().catch(() => undefined);
      navigate('/login');
    } catch (e) {
      setError(e);
    } finally {
      setBusy(null);
    }
  };
  return (
    <div className="stack-lg">
      <Card title="Export your data">
        <p className="muted">Download everything Pitsch stores. Exports are logged in the audit trail.</p>
        <div className="row gap-sm wrap">
          {can('DATA_EXPORT') && <Button onClick={() => exportData('workspace')} loading={busy === 'workspace'}>Export workspace (ZIP with files)</Button>}
          <Button onClick={() => exportData('account')} loading={busy === 'account'}>Export my account (JSON)</Button>
        </div>
      </Card>
      <Card title="Delete">
        <p className="muted">Deleting is permanent. Connected Google accounts are revoked and stored files are removed.</p>
        <div className="row gap-sm wrap">
          {can('ORG_DELETE') && <Button variant="danger" onClick={() => { setError(null); setDeleteWs(true); }}>Delete workspace “{me?.workspace?.name}”</Button>}
          <Button variant="danger" onClick={() => { setError(null); setDeleteMe(true); }}>Delete my account</Button>
        </div>
      </Card>
      <ConfirmDialog open={deleteWs} title="Delete the workspace?" danger confirmLabel="Delete permanently" busy={busy === 'delete-ws'}
        onCancel={() => setDeleteWs(false)} onConfirm={removeWorkspace}>
        <div className="stack">
          <p>All pitches, emails, files, workflows and integrations of <strong>{me?.workspace?.name}</strong> will be deleted for every member.</p>
          <TextInput label="Type the workspace name to confirm" value={confirmName} onChange={(e) => setConfirmName(e.target.value)} />
          <TextInput label="Your password" type="password" value={password} onChange={(e) => setPassword(e.target.value)} />
          <InlineError error={error} />
        </div>
      </ConfirmDialog>
      <ConfirmDialog open={deleteMe} title="Delete your account?" danger confirmLabel="Delete my account" busy={busy === 'delete-me'}
        onCancel={() => setDeleteMe(false)} onConfirm={removeAccount}>
        <div className="stack">
          <p>Your login, sessions and Google connections are removed. Workspaces where you are the only member are deleted. Records you created in shared workspaces stay with that workspace.</p>
          <TextInput label="Your password" type="password" value={password} onChange={(e) => setPassword(e.target.value)} />
          <InlineError error={error} />
        </div>
      </ConfirmDialog>
    </div>
  );
}

/* ---------------- audit ---------------- */
function AuditSection() {
  const [page, setPage] = useState(0);
  const [action, setAction] = useState('');
  const list = useAsync(() => auditApi.list({ page, action: action || undefined }), [page, action]);
  return (
    <Card title="Audit log" padded={false} actions={<input className="input input-sm" placeholder="Filter by action, e.g. EMAIL_SENT" value={action}
      onChange={(e) => { setAction(e.target.value.toUpperCase().replace(/[^A-Z_]/g, '')); setPage(0); }} aria-label="Filter by action" />}>
      {list.error ? <ErrorState error={list.error} onRetry={list.reload} /> : !list.data ? <div className="pad"><Skeleton /></div> : (
        <>
          <div className="table-wrap">
            <table className="table">
              <thead><tr><th>When</th><th>Who</th><th>Action</th><th>Resource</th><th>Request</th></tr></thead>
              <tbody>
                {list.data.items.map((e) => (
                  <tr key={e.id}>
                    <td className="small">{formatDateTime(e.createdAt)}</td>
                    <td className="small">{e.actorType === 'AI' ? <Badge tone="orange">AI</Badge> : e.actorType === 'SYSTEM' ? <Badge>System</Badge> : (e.actorName ?? `user ${e.actorUserId}`)}</td>
                    <td className="small mono">{e.action}</td>
                    <td className="small">{e.resourceType ? `${humanize(e.resourceType)} ${e.resourceId ?? ''}` : '—'}</td>
                    <td className="small mono muted">{e.requestId?.slice(0, 8) ?? '—'}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          <div className="pad"><Pagination page={list.data.page} totalPages={list.data.totalPages} totalItems={list.data.totalItems} onPage={setPage} /></div>
        </>
      )}
    </Card>
  );
}
