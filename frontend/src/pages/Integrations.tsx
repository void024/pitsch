import { useEffect, useState } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { integrations as integrationsApi } from '../lib/api/endpoints';
import type { IntegrationName, IntegrationStatus, SheetPreview } from '../lib/api/types';
import { useAuth } from '../lib/auth/AuthContext';
import { useAsync } from '../lib/hooks';
import { formatDateTime, humanize } from '../lib/format';
import { safeHttpUrl } from '../lib/safeUrl';
import {
  Badge, Button, Card, ConfirmDialog, ErrorState, InlineError, PageHeader, Skeleton, TextInput, useToast,
} from '../components/ui';

const INFO: Record<IntegrationName, { title: string; what: string; access: string }> = {
  GMAIL: {
    title: 'Gmail',
    what: 'Brings pitch emails into Pitsch automatically, applies Pitsch labels, and sends replies you approve.',
    access: 'Read and label messages, send email (gmail.modify). Pitsch never deletes email.',
  },
  CALENDAR: {
    title: 'Google Calendar',
    what: 'Checks your free/busy when suggesting meeting times, and creates or cancels meetings you approve.',
    access: 'Free/busy, your calendar list, and events Pitsch creates.',
  },
  SHEETS: {
    title: 'Google Sheets',
    what: 'Keeps a pipeline spreadsheet in sync with your deals using the column mapping you choose.',
    access: 'Spreadsheets you select.',
  },
};

const ERRORS: Record<string, string> = {
  cancelled: 'Connection was cancelled.', denied: 'Google denied access.', expired: 'The connection link expired, please retry.',
  oauth_failed: 'Connecting failed. Please try again.', no_refresh_token: 'Google did not grant offline access. Remove Pitsch from your Google account permissions and connect again.',
};

export default function Integrations() {
  const { can } = useAuth();
  const toast = useToast();
  const navigate = useNavigate();
  const [params, setParams] = useSearchParams();
  const list = useAsync(() => integrationsApi.list(), []);
  const [disconnecting, setDisconnecting] = useState<IntegrationName | null>(null);
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    const connected = params.get('connected');
    const error = params.get('error');
    if (connected) toast(`Connected: ${connected.replace(/,/g, ', ')}${params.get('partial') ? ' (some permissions were not granted)' : ''}`, 'success');
    if (error) toast(ERRORS[error] ?? 'Connection failed.', 'error');
    if (connected || error) setParams({}, { replace: true });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const connect = async (names: IntegrationName[]) => {
    try {
      const { authorizationUrl } = await integrationsApi.connect(names, '/integrations');
      const safe = safeHttpUrl(authorizationUrl);
      if (!safe) throw new Error('Unexpected authorization address.');
      const url = new URL(safe);
      if (url.origin === window.location.origin) {
        navigate(url.pathname + url.search);
        list.reload();
      } else if (url.hostname === 'accounts.google.com') {
        window.location.assign(safe);
      } else {
        throw new Error('Unexpected authorization address.');
      }
    } catch (e) {
      toast(e instanceof Error ? e.message : 'Could not start the connection', 'error');
    }
  };

  const disconnect = async () => {
    if (!disconnecting) return;
    setBusy(true);
    try {
      await integrationsApi.disconnect(disconnecting);
      toast(`${INFO[disconnecting].title} disconnected`, 'info');
      list.reload();
    } catch (e) {
      toast(e instanceof Error ? e.message : 'Could not disconnect', 'error');
    } finally {
      setBusy(false);
      setDisconnecting(null);
    }
  };

  return (
    <div className="stack-lg">
      <PageHeader title="Integrations" subtitle="Each Google product is connected separately, with only the access it needs. Tokens are encrypted at rest." />
      {list.error ? <ErrorState error={list.error} onRetry={list.reload} /> : !list.data ? <Skeleton lines={6} /> : (
        <>
          {list.data.every((s) => !s.configured && !s.demo) && (
            <div className="banner banner-warn">Google OAuth is not configured on this deployment (GOOGLE_CLIENT_ID / SECRET / REDIRECT_URI). See docs/INTEGRATIONS.md.</div>
          )}
          <div className="integration-cards">
            {list.data.map((s) => (
              <IntegrationCard key={s.integration} status={s} canConnect={can(s.integration === 'SHEETS' ? 'INTEGRATION_MANAGE' : 'INTEGRATION_CONNECT')}
                onConnect={() => connect([s.integration])} onDisconnect={() => setDisconnecting(s.integration)} />
            ))}
          </div>
          {list.data.find((s) => s.integration === 'SHEETS')?.connected && <SheetSetup />}
        </>
      )}
      <ConfirmDialog open={!!disconnecting} title={`Disconnect ${disconnecting ? INFO[disconnecting].title : ''}?`} danger confirmLabel="Disconnect" busy={busy}
        onCancel={() => setDisconnecting(null)} onConfirm={disconnect}>
        <p>Pitsch stops using this product. When the last Google product is disconnected, the grant is revoked at Google and the stored tokens are deleted.</p>
      </ConfirmDialog>
    </div>
  );
}

function IntegrationCard({ status: s, canConnect, onConnect, onDisconnect }: {
  status: IntegrationStatus; canConnect: boolean; onConnect: () => void; onDisconnect: () => void;
}) {
  const info = INFO[s.integration];
  return (
    <Card title={info.title} actions={s.connected
      ? <Badge tone="green">{s.demo ? 'Connected (demo)' : 'Connected'}</Badge>
      : s.status === 'ERROR' || s.status === 'NEEDS_RECONNECT' ? <Badge tone="red">Needs attention</Badge> : <Badge>Not connected</Badge>}>
      <p>{info.what}</p>
      <p className="muted small">Access: {info.access}</p>
      {s.accountEmail && <p className="small">Account: <strong>{s.accountEmail}</strong></p>}
      {s.lastSyncAt && <p className="muted small">Last sync {formatDateTime(s.lastSyncAt)} · {humanize(s.lastSyncStatus)}</p>}
      {s.integration === 'GMAIL' && s.connected && <p className="muted small">{s.pushEnabled ? 'Real-time push is on.' : 'Checked every few minutes.'}</p>}
      {s.error && <div className="banner banner-danger small">{s.error}</div>}
      <div className="row gap-sm">
        {canConnect && !s.connected && <Button variant="primary" onClick={onConnect} disabled={!s.configured && !s.demo}>Connect</Button>}
        {canConnect && s.connected && <Button variant="ghost" onClick={onDisconnect}>Disconnect</Button>}
        {canConnect && (s.status === 'ERROR' || s.status === 'NEEDS_RECONNECT') && <Button onClick={onConnect}>Reconnect</Button>}
      </div>
    </Card>
  );
}

function SheetSetup() {
  const { can } = useAuth();
  const toast = useToast();
  const config = useAsync(() => integrationsApi.sheetConfig(), []);
  const fields = useAsync(() => integrationsApi.sheetFields(), []);
  const [spreadsheet, setSpreadsheet] = useState('');
  const [preview, setPreview] = useState<SheetPreview | null>(null);
  const [worksheet, setWorksheet] = useState('');
  const [headerRow, setHeaderRow] = useState(1);
  const [mapping, setMapping] = useState<Record<string, string>>({});
  const [error, setError] = useState<unknown>(null);
  const [busy, setBusy] = useState(false);
  const manage = can('INTEGRATION_MANAGE');

  // Seed the form from the saved configuration whenever a new one arrives (adjusting state during render,
  // see https://react.dev/learn/you-might-not-need-an-effect#adjusting-some-state-when-a-prop-changes).
  const [seededFrom, setSeededFrom] = useState<typeof config.data>(undefined);
  if (config.data && config.data !== seededFrom) {
    setSeededFrom(config.data);
    setSpreadsheet(config.data.spreadsheetId);
    setWorksheet(config.data.worksheetTitle);
    setHeaderRow(config.data.headerRow);
    setMapping(Object.fromEntries(config.data.mapping.map((m) => [m.field, m.column])));
  }

  const load = async (ws?: string) => {
    setError(null);
    try {
      const p = await integrationsApi.sheetPreview(spreadsheet.trim(), ws ?? (worksheet || undefined), headerRow);
      setPreview(p);
      setWorksheet(p.worksheet);
    } catch (e) {
      setError(e);
    }
  };

  const save = async () => {
    setBusy(true);
    setError(null);
    try {
      const saved = await integrationsApi.saveSheetConfig({
        spreadsheet: spreadsheet.trim(), worksheetTitle: worksheet, headerRow,
        mapping: Object.entries(mapping).filter(([, c]) => c).map(([field, column]) => ({ field, column })),
      });
      config.setData(saved);
      toast('Pipeline sheet saved', 'success');
    } catch (e) {
      setError(e);
    } finally {
      setBusy(false);
    }
  };

  const syncAll = async () => {
    try {
      const r = await integrationsApi.syncAllPitches();
      toast(`Queued ${r.queued} pitches for the sheet`, 'success');
    } catch (e) {
      toast(e instanceof Error ? e.message : 'Sync failed', 'error');
    }
  };

  const columns = preview?.header ?? (config.data ? config.data.mapping.map((m) => m.column) : []);
  return (
    <Card title="Pipeline spreadsheet" actions={config.data && can('ACTION_APPROVE') && <Button size="sm" onClick={syncAll}>Sync all pitches now</Button>}>
      <p className="muted">Map Pitsch fields to the columns of your existing sheet. Rows are matched by the Pitch ID column, so re-syncs update rows instead of duplicating them. Formulas in other columns are left untouched.</p>
      <div className="grid-2">
        <TextInput label="Spreadsheet URL or ID" value={spreadsheet} onChange={(e) => setSpreadsheet(e.target.value)} disabled={!manage} />
        <TextInput label="Header row" type="number" min={1} max={50} value={headerRow} onChange={(e) => setHeaderRow(Number(e.target.value) || 1)} disabled={!manage} />
      </div>
      {manage && <Button onClick={() => load()} disabled={!spreadsheet.trim()}>Load columns</Button>}
      {preview && (
        <div className="stack">
          <p className="small">Spreadsheet: <strong>{preview.title}</strong></p>
          <label className="field"><span>Worksheet</span>
            <select className="input" value={worksheet} onChange={(e) => load(e.target.value)}>
              {preview.worksheets.map((w) => <option key={w} value={w}>{w}</option>)}
            </select>
          </label>
        </div>
      )}
      {columns.length > 0 && fields.data && (
        <div className="mapping">
          {fields.data.map((f) => (
            <label key={f} className="mapping-row">
              <span>{f.replace(/([A-Z])/g, ' $1').replace(/^./, (c) => c.toUpperCase())}{f === 'pitchId' && <span className="muted small"> (required, used to match rows)</span>}</span>
              <select className="input" value={mapping[f] ?? ''} disabled={!manage} onChange={(e) => setMapping({ ...mapping, [f]: e.target.value })}>
                <option value="">— not synced —</option>
                {columns.map((c) => <option key={c} value={c}>{c}</option>)}
              </select>
            </label>
          ))}
        </div>
      )}
      <InlineError error={error} />
      {manage && <div className="row gap-sm">
        <Button variant="primary" onClick={save} loading={busy} disabled={!spreadsheet.trim() || !worksheet}>Save mapping</Button>
        {config.data && <Button variant="ghost" onClick={async () => { await integrationsApi.removeSheetConfig(); config.setData(undefined as never); setPreview(null); }}>Remove</Button>}
      </div>}
    </Card>
  );
}
