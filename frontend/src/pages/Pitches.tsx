import { useState, type FormEvent } from 'react';
import { Link, useNavigate, useSearchParams } from 'react-router-dom';
import { newIdempotencyKey } from '../lib/api/client';
import { pitches as pitchesApi } from '../lib/api/endpoints';
import { DEAL_STAGES, type DealStage } from '../lib/api/types';
import { useAuth } from '../lib/auth/AuthContext';
import { useAsync, useDebounced } from '../lib/hooks';
import { STAGE_LABEL, formatPercent, timeAgo } from '../lib/format';
import { RiskBadge, StageBadge } from '../components/badges';
import {
  Button, Card, EmptyState, ErrorState, InlineError, Modal, PageHeader, Pagination, SelectInput, Skeleton, TextArea, TextInput,
} from '../components/ui';

const SORTS = [
  { value: 'lastActivityAt,desc', label: 'Recent activity' },
  { value: 'createdAt,desc', label: 'Newest' },
  { value: 'companyName,asc', label: 'Company A–Z' },
  { value: 'aiConfidence,desc', label: 'Classifier confidence' },
];

export default function Pitches() {
  const { can } = useAuth();
  const [params, setParams] = useSearchParams();
  const [createOpen, setCreateOpen] = useState(false);
  const q = params.get('q') ?? '';
  const [search, setSearch] = useState(q);
  const debounced = useDebounced(search, 300);
  const dealStage = params.get('dealStage') ?? '';
  const riskLevel = params.get('riskLevel') ?? '';
  const sector = params.get('sector') ?? '';
  const hasFollowUp = params.get('hasFollowUp') ?? '';
  const sort = params.get('sort') ?? 'lastActivityAt,desc';
  const page = Number(params.get('page') ?? 0);

  const set = (key: string, value: string) => {
    const next = new URLSearchParams(params);
    if (value) next.set(key, value); else next.delete(key);
    if (key !== 'page') next.delete('page');
    setParams(next, { replace: true });
  };

  const list = useAsync((signal) => pitchesApi.list({
    q: debounced || undefined, dealStage: dealStage || undefined, riskLevel: riskLevel || undefined,
    sector: sector || undefined, hasFollowUp: hasFollowUp ? hasFollowUp === 'true' : undefined, sort, page, size: 25,
  }, signal), [debounced, dealStage, riskLevel, sector, hasFollowUp, sort, page]);

  return (
    <div className="stack-lg">
      <PageHeader title="Pitches" subtitle="Search and filter every company in your pipeline."
        actions={can('PITCH_WRITE') && <Button variant="primary" onClick={() => setCreateOpen(true)}>Add pitch</Button>} />

      <div className="filters">
        <input className="input search" type="search" placeholder="Search company, founder, domain…" aria-label="Search pitches"
          value={search} onChange={(e) => { setSearch(e.target.value); if (page) set('page', ''); }} />
        <select className="input" aria-label="Deal stage" value={dealStage} onChange={(e) => set('dealStage', e.target.value)}>
          <option value="">All stages</option>
          {DEAL_STAGES.map((s) => <option key={s} value={s}>{STAGE_LABEL[s]}</option>)}
        </select>
        <select className="input" aria-label="Evidence risk" value={riskLevel} onChange={(e) => set('riskLevel', e.target.value)}>
          <option value="">Any evidence risk</option>
          <option value="LOW">Low</option><option value="MEDIUM">Medium</option><option value="HIGH">High</option>
        </select>
        <select className="input" aria-label="Follow-ups" value={hasFollowUp} onChange={(e) => set('hasFollowUp', e.target.value)}>
          <option value="">Any</option><option value="true">Has follow-up</option><option value="false">No follow-up</option>
        </select>
        <input className="input" placeholder="Sector" aria-label="Sector" value={sector} onChange={(e) => set('sector', e.target.value)} />
        <select className="input" aria-label="Sort" value={sort} onChange={(e) => set('sort', e.target.value)}>
          {SORTS.map((s) => <option key={s.value} value={s.value}>{s.label}</option>)}
        </select>
      </div>

      <Card padded={false}>
        {list.error ? <ErrorState error={list.error} onRetry={list.reload} /> : !list.data ? <div className="pad"><Skeleton lines={5} /></div> : list.data.items.length === 0 ? (
          <EmptyState title="No pitches match">Try clearing filters, or import a pitch email.</EmptyState>
        ) : (
          <>
            <div className="table-wrap">
              <table className="table table-hover">
                <thead><tr><th>Company</th><th>Stage</th><th>Sector</th><th>Raising</th><th>Evidence</th><th>Confidence</th><th>Activity</th></tr></thead>
                <tbody>
                  {list.data.items.map((p) => (
                    <tr key={p.id}>
                      <td>
                        <Link to={`/pitches/${p.id}`}><strong>{p.companyName ?? 'Unknown company'}</strong></Link>
                        <div className="muted small">{p.founderName ?? p.founderEmail ?? ''}{p.hasFollowUp ? ' · follow-up' : ''}</div>
                      </td>
                      <td><StageBadge stage={p.dealStage} /></td>
                      <td className="small">{p.sector ?? '—'}</td>
                      <td className="small">{p.amountRequested ?? '—'}</td>
                      <td><RiskBadge level={p.riskLevel} /></td>
                      <td className="small" title="Classifier confidence that this is a pitch">{formatPercent(p.aiConfidence)}</td>
                      <td className="small">{timeAgo(p.lastActivityAt)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
            <div className="pad"><Pagination page={list.data.page} totalPages={list.data.totalPages} totalItems={list.data.totalItems} onPage={(p) => set('page', String(p))} /></div>
          </>
        )}
      </Card>
      <CreatePitchModal open={createOpen} onClose={() => setCreateOpen(false)} />
    </div>
  );
}

function CreatePitchModal({ open, onClose }: { open: boolean; onClose: () => void }) {
  const navigate = useNavigate();
  const [form, setForm] = useState({ companyName: '', founderName: '', founderEmail: '', website: '', sector: '', stage: '', amountRequested: '', oneLiner: '', dealStage: 'NEW' as DealStage });
  const [error, setError] = useState<unknown>(null);
  const [busy, setBusy] = useState(false);
  const [key] = useState(() => newIdempotencyKey('pitch'));
  const upd = (k: keyof typeof form) => (e: { target: { value: string } }) => setForm({ ...form, [k]: e.target.value });

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      const p = await pitchesApi.create(Object.fromEntries(Object.entries(form).filter(([, v]) => v !== '')), key);
      onClose();
      navigate(`/pitches/${p.id}`);
    } catch (err) {
      setError(err);
    } finally {
      setBusy(false);
    }
  };
  return (
    <Modal open={open} onClose={onClose} title="Add a pitch manually" wide
      footer={<><Button variant="ghost" onClick={onClose}>Cancel</Button><Button variant="primary" type="submit" form="create-pitch" loading={busy} disabled={!form.companyName.trim()}>Add pitch</Button></>}>
      <form id="create-pitch" className="stack" onSubmit={submit}>
        <div className="grid-2">
          <TextInput label="Company" required value={form.companyName} onChange={upd('companyName')} />
          <TextInput label="Website" value={form.website} onChange={upd('website')} placeholder="https://" />
          <TextInput label="Founder" value={form.founderName} onChange={upd('founderName')} />
          <TextInput label="Founder email" type="email" value={form.founderEmail} onChange={upd('founderEmail')} />
          <TextInput label="Sector" value={form.sector} onChange={upd('sector')} />
          <TextInput label="Funding stage" value={form.stage} onChange={upd('stage')} placeholder="Seed" />
          <TextInput label="Raising" value={form.amountRequested} onChange={upd('amountRequested')} placeholder="$2M" />
          <SelectInput label="Deal stage" value={form.dealStage} onChange={upd('dealStage')} options={DEAL_STAGES.map((s) => ({ value: s, label: STAGE_LABEL[s] }))} />
        </div>
        <TextArea label="One-liner" rows={2} value={form.oneLiner} onChange={upd('oneLiner')} />
        <InlineError error={error} />
      </form>
    </Modal>
  );
}
