import { useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { integrations as integrationsApi, workspace as workspaceApi } from '../lib/api/endpoints';
import type { IntegrationName, IntegrationStatus } from '../lib/api/types';
import { useAuth } from '../lib/auth/AuthContext';
import { safeHttpUrl } from '../lib/safeUrl';
import { ImportEmailModal } from '../components/ImportEmailModal';
import { Badge, Button, Card, InlineError, SelectInput, TextInput, useToast } from '../components/ui';

const DAYS = ['MON', 'TUE', 'WED', 'THU', 'FRI', 'SAT', 'SUN'];

function timezones(): string[] {
  const intl = Intl as unknown as { supportedValuesOf?: (k: string) => string[] };
  return intl.supportedValuesOf ? intl.supportedValuesOf('timeZone') : ['UTC', 'Europe/London', 'America/New_York', 'Asia/Kolkata'];
}

export default function Onboarding() {
  const { me, refresh, can } = useAuth();
  const navigate = useNavigate();
  const toast = useToast();
  const [step, setStep] = useState(me?.workspace ? 1 : 0);
  const [name, setName] = useState(me?.workspace?.name ?? '');
  const [tz, setTz] = useState(me?.workspace?.timezone ?? Intl.DateTimeFormat().resolvedOptions().timeZone);
  const [start, setStart] = useState('09:30');
  const [end, setEnd] = useState('18:30');
  const [days, setDays] = useState<string[]>(['MON', 'TUE', 'WED', 'THU', 'FRI']);
  const [status, setStatus] = useState<IntegrationStatus[] | null>(null);
  const [importOpen, setImportOpen] = useState(false);
  const [error, setError] = useState<unknown>(null);
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    if (step === 2) integrationsApi.list().then(setStatus).catch(setError);
  }, [step]);

  const saveWorkspace = async () => {
    setBusy(true);
    setError(null);
    try {
      if (!me?.workspace) {
        await workspaceApi.create(name.trim() || `${me?.user.name}'s workspace`, tz);
        await refresh();
      }
      if (can('ORG_SETTINGS') || !me?.workspace) {
        await workspaceApi.update({ name: name.trim() || undefined, timezone: tz, workingHoursStart: start, workingHoursEnd: end, workingDays: days });
      }
      setStep(2);
    } catch (e) {
      setError(e);
    } finally {
      setBusy(false);
    }
  };

  const connect = async (names: IntegrationName[]) => {
    try {
      const { authorizationUrl } = await integrationsApi.connect(names, '/onboarding');
      const safe = safeHttpUrl(authorizationUrl);
      if (!safe) throw new Error('Unexpected authorization address.');
      if (new URL(safe).origin === window.location.origin) {
        // Demo mode connects immediately and redirects back to the app.
        navigate(new URL(safe).pathname + new URL(safe).search);
        setStatus(await integrationsApi.list());
      } else {
        window.location.assign(safe);
      }
    } catch (e) {
      setError(e);
    }
  };

  const finish = async () => {
    setBusy(true);
    try {
      if (can('ORG_SETTINGS')) await workspaceApi.completeOnboarding();
      await refresh();
      toast('Your workspace is ready.', 'success');
      navigate('/dashboard', { replace: true });
    } catch (e) {
      setError(e);
    } finally {
      setBusy(false);
    }
  };

  const googleConfigured = status?.some((s) => s.configured || s.demo);
  return (
    <div className="onboarding">
      <div className="onboarding-steps">
        {['Workspace', 'Schedule', 'Connect Google', 'First pitch'].map((label, i) => (
          <div key={label} className={`ob-step ${i === step ? 'active' : i < step ? 'done' : ''}`}>
            <span>{i < step ? '✓' : i + 1}</span>{label}
          </div>
        ))}
      </div>

      {step === 0 && (
        <Card title="Name your workspace">
          <p className="muted">A workspace holds your fund's pitches, members and integrations. Data never crosses workspaces.</p>
          <div className="stack">
            <TextInput label="Workspace name" value={name} onChange={(e) => setName(e.target.value)} placeholder="Northwind Ventures" />
            <Button variant="primary" onClick={() => setStep(1)} disabled={!name.trim()}>Continue</Button>
          </div>
        </Card>
      )}

      {step === 1 && (
        <Card title="When can you take meetings?">
          <p className="muted">The Calendar Agent only suggests slots inside these hours. It never books anything without your approval.</p>
          <div className="stack">
            {!me?.workspace && <TextInput label="Workspace name" value={name} onChange={(e) => setName(e.target.value)} />}
            <SelectInput label="Time zone" value={tz} onChange={(e) => setTz(e.target.value)} options={timezones().map((t) => ({ value: t, label: t }))} />
            <div className="grid-2">
              <TextInput label="Day starts" type="time" value={start} onChange={(e) => setStart(e.target.value)} />
              <TextInput label="Day ends" type="time" value={end} onChange={(e) => setEnd(e.target.value)} />
            </div>
            <div className="chips" role="group" aria-label="Working days">
              {DAYS.map((d) => (
                <button key={d} type="button" className={`chip ${days.includes(d) ? 'chip-on' : ''}`}
                  onClick={() => setDays(days.includes(d) ? days.filter((x) => x !== d) : [...days, d])}>{d.slice(0, 1) + d.slice(1).toLowerCase()}</button>
              ))}
            </div>
            <InlineError error={error} />
            <Button variant="primary" onClick={saveWorkspace} loading={busy} disabled={days.length === 0}>Save and continue</Button>
          </div>
        </Card>
      )}

      {step === 2 && (
        <Card title="Connect Google (optional)">
          <p className="muted">
            Gmail brings pitch emails in automatically, Calendar checks your availability and creates meetings you approve,
            Sheets mirrors your pipeline. You can connect each one separately and disconnect at any time.
          </p>
          {status && !googleConfigured && (
            <div className="banner banner-warn">Google integration is not configured on this deployment. You can still import emails manually.</div>
          )}
          <div className="integration-grid">
            {(status ?? []).map((s) => (
              <div key={s.integration} className="integration-tile">
                <strong>{s.integration === 'GMAIL' ? 'Gmail' : s.integration === 'CALENDAR' ? 'Google Calendar' : 'Google Sheets'}</strong>
                {s.connected ? <Badge tone="green">Connected{s.demo ? ' (demo)' : ''}</Badge>
                  : <Button size="sm" onClick={() => connect([s.integration])} disabled={!(s.configured || s.demo) || !can('INTEGRATION_CONNECT')}>Connect</Button>}
              </div>
            ))}
          </div>
          <InlineError error={error} />
          <div className="row gap-sm">
            <Button variant="primary" onClick={() => setStep(3)}>Continue</Button>
          </div>
        </Card>
      )}

      {step === 3 && (
        <Card title="Analyse your first pitch">
          <p className="muted">
            Forward or paste a pitch email. The agents read it, ask whether you want it handled, and then build a sourced
            research brief. Nothing is sent or scheduled without your approval.
          </p>
          <div className="row gap-sm">
            <Button variant="primary" disabled={!can('EMAIL_IMPORT')} onClick={async () => {
              if (can('ORG_SETTINGS')) await workspaceApi.completeOnboarding().catch(() => undefined);
              setImportOpen(true);
            }}>Import an email</Button>
            <Button onClick={finish} loading={busy}>Go to dashboard</Button>
          </div>
          <InlineError error={error} />
          <ImportEmailModal open={importOpen} onClose={() => setImportOpen(false)} />
        </Card>
      )}
    </div>
  );
}
