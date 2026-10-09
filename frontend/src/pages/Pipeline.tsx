import { useState } from 'react';
import { Link } from 'react-router-dom';
import { pitches as pitchesApi } from '../lib/api/endpoints';
import { DEAL_STAGES, type DealStage, type Pitch } from '../lib/api/types';
import { useAuth } from '../lib/auth/AuthContext';
import { useAsync } from '../lib/hooks';
import { STAGE_LABEL, timeAgo } from '../lib/format';
import { RiskBadge } from '../components/badges';
import { ErrorState, PageHeader, Skeleton, useToast } from '../components/ui';

const BOARD: DealStage[] = DEAL_STAGES.filter((s) => s !== 'ARCHIVED');

/** Kanban board by deal stage. Moving a card is a human decision; Pitsch never moves deals on its own. */
export default function Pipeline() {
  const { can } = useAuth();
  const toast = useToast();
  const [dragging, setDragging] = useState<Pitch | null>(null);
  const columns = useAsync(async (signal) => {
    const pages = await Promise.all(BOARD.map((s) => pitchesApi.list({ dealStage: s, size: 50, sort: 'lastActivityAt,desc' }, signal)));
    return Object.fromEntries(BOARD.map((s, i) => [s, pages[i]])) as Record<DealStage, Awaited<ReturnType<typeof pitchesApi.list>>>;
  }, []);

  const move = async (pitch: Pitch, stage: DealStage) => {
    if (pitch.dealStage === stage) return;
    columns.setData((prev) => {
      const next = { ...prev! };
      next[pitch.dealStage] = { ...next[pitch.dealStage], items: next[pitch.dealStage].items.filter((p) => p.id !== pitch.id) };
      next[stage] = { ...next[stage], items: [{ ...pitch, dealStage: stage }, ...next[stage].items] };
      return next;
    });
    try {
      await pitchesApi.update(pitch.id, { dealStage: stage, version: pitch.version });
      columns.reload();
    } catch (e) {
      toast(e instanceof Error ? e.message : 'Could not move the pitch', 'error');
      columns.reload();
    }
  };

  if (columns.error) return <ErrorState error={columns.error} onRetry={columns.reload} />;
  return (
    <div className="stack-lg">
      <PageHeader title="Pipeline" subtitle={can('PITCH_WRITE') ? 'Drag a card to move it to another stage.' : 'Your fund’s deals by stage.'} />
      {!columns.data ? <Skeleton lines={6} /> : (
        <div className="board">
          {BOARD.map((stage) => (
            <section key={stage} className="board-col"
              onDragOver={(e) => { if (dragging && can('PITCH_WRITE')) e.preventDefault(); }}
              onDrop={() => { if (dragging) void move(dragging, stage); setDragging(null); }}>
              <header><strong>{STAGE_LABEL[stage]}</strong><span className="muted small">{columns.data![stage].totalItems}</span></header>
              <div className="board-cards">
                {columns.data![stage].items.map((p) => (
                  <article key={p.id} className="board-card" draggable={can('PITCH_WRITE')}
                    onDragStart={() => setDragging(p)} onDragEnd={() => setDragging(null)}>
                    <Link to={`/pitches/${p.id}`}><strong>{p.companyName ?? 'Unknown'}</strong></Link>
                    <div className="muted small">{[p.sector, p.amountRequested].filter(Boolean).join(' · ') || '—'}</div>
                    <div className="row space-between">
                      <RiskBadge level={p.riskLevel} />
                      <span className="muted small">{timeAgo(p.lastActivityAt)}</span>
                    </div>
                    {can('PITCH_WRITE') && (
                      <select className="input input-sm" aria-label={`Move ${p.companyName ?? 'pitch'}`} value={p.dealStage}
                        onChange={(e) => move(p, e.target.value as DealStage)}>
                        {DEAL_STAGES.map((s) => <option key={s} value={s}>{STAGE_LABEL[s]}</option>)}
                      </select>
                    )}
                  </article>
                ))}
              </div>
            </section>
          ))}
        </div>
      )}
    </div>
  );
}
