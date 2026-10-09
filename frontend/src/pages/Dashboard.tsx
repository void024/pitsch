import { useState } from 'react';
import { Link } from 'react-router-dom';
import { dashboard as dashboardApi, workflows as workflowsApi } from '../lib/api/endpoints';
import { DEAL_STAGES } from '../lib/api/types';
import { useAuth } from '../lib/auth/AuthContext';
import { useAsync, useInterval } from '../lib/hooks';
import { STAGE_LABEL, formatDateTime, formatUsd, humanize, timeAgo } from '../lib/format';
import { WorkflowBadge } from '../components/badges';
import { ImportEmailModal } from '../components/ImportEmailModal';
import { Button, Card, EmptyState, ErrorState, PageHeader, Skeleton, Stat } from '../components/ui';

export default function Dashboard() {
  const { me, can } = useAuth();
  const [importOpen, setImportOpen] = useState(false);
  const data = useAsync(() => dashboardApi.get(), []);
  const attention = useAsync(() => workflowsApi.list({ needsAction: true, size: 6, sort: 'updatedAt,desc' }), []);
  useInterval(() => { data.reload(); attention.reload(); }, 30_000);

  const d = data.data;
  return (
    <div className="stack-lg">
      <PageHeader
        title={`Good ${new Date().getHours() < 12 ? 'morning' : new Date().getHours() < 18 ? 'afternoon' : 'evening'}, ${me?.user.name.split(' ')[0] ?? ''}`}
        subtitle="What needs you today across your pipeline."
        actions={can('EMAIL_IMPORT') && <Button variant="primary" onClick={() => setImportOpen(true)}>Import email</Button>}
      />

      {me?.workspace && !me.workspace.onboardingCompleted && can('ORG_SETTINGS') && (
        <div className="banner banner-info">Finish setting up your workspace: working hours and integrations. <Link to="/onboarding">Continue setup</Link></div>
      )}

      {data.error ? <ErrorState error={data.error} onRetry={data.reload} /> : !d ? <Skeleton lines={4} /> : (
        <>
          <div className="stats">
            <Stat label="Need your decision" value={d.workflowsNeedingAction} tone={d.workflowsNeedingAction ? 'orange' : undefined} hint={<Link to="/workflows?needsAction=true">Review</Link>} />
            <Stat label="Pending approvals" value={d.pendingApprovals} tone={d.pendingApprovals ? 'purple' : undefined} hint={<Link to="/approvals">Open</Link>} />
            <Stat label="Agents working" value={d.workflowsRunning} tone={d.workflowsRunning ? 'blue' : undefined} />
            <Stat label="Pitches" value={d.totalPitches} hint={<Link to="/pipeline">Pipeline</Link>} />
            <Stat label="Your open tasks" value={d.myOpenTasks} hint={<Link to="/tasks">Tasks</Link>} />
          </div>

          <div className="grid-main">
            <Card title="Needs your attention" actions={<Link to="/workflows?needsAction=true" className="small">All</Link>}>
              {attention.data?.items.length ? (
                <ul className="list">
                  {attention.data.items.map((w) => (
                    <li key={w.id}>
                      <Link to={`/workflows/${w.id}`} className="list-row">
                        <div>
                          <strong>{w.companyName ?? w.prompt ?? 'Email'}</strong>
                          <div className="muted small">{w.sender ?? ''} · {timeAgo(w.updatedAt)}</div>
                        </div>
                        <WorkflowBadge status={w.status} />
                      </Link>
                    </li>
                  ))}
                </ul>
              ) : attention.loading ? <Skeleton /> : (
                <EmptyState title="You're all caught up">New pitches and agent results that need a decision will appear here.</EmptyState>
              )}
            </Card>

            <Card title="Upcoming meetings" actions={<Link to="/calendar" className="small">Calendar</Link>}>
              {d.upcomingMeetings.length ? (
                <ul className="list">
                  {d.upcomingMeetings.map((e) => (
                    <li key={e.id} className="list-row">
                      <div><strong>{e.title}</strong><div className="muted small">{formatDateTime(e.startTime)}</div></div>
                      {e.syncStatus === 'DEMO' && <span className="muted small">demo</span>}
                    </li>
                  ))}
                </ul>
              ) : <p className="muted">No meetings in the next 7 days.</p>}
            </Card>
          </div>

          <div className="grid-main">
            <Card title="Pipeline">
              <div className="stage-bars">
                {DEAL_STAGES.filter((s) => s !== 'ARCHIVED').map((s) => {
                  const n = d.pitchesByStage[s] ?? 0;
                  const max = Math.max(1, ...Object.values(d.pitchesByStage));
                  return (
                    <Link key={s} to={`/pitches?dealStage=${s}`} className="stage-bar">
                      <span className="stage-bar-label">{STAGE_LABEL[s]}</span>
                      <span className="stage-bar-track"><span style={{ width: `${(n / max) * 100}%` }} /></span>
                      <span className="stage-bar-value">{n}</span>
                    </Link>
                  );
                })}
              </div>
            </Card>
            <Card title="Recent activity">
              {d.recentActivity.length ? (
                <ul className="timeline-compact">
                  {d.recentActivity.slice(0, 8).map((a) => (
                    <li key={a.id}><span>{a.message}</span><span className="muted small">{timeAgo(a.createdAt)}</span></li>
                  ))}
                </ul>
              ) : <p className="muted">Nothing yet.</p>}
            </Card>
          </div>

          <Card title="AI usage this month" actions={<span className="muted small">Plan: {d.plan}</span>}>
            <div className="usage">
              {Object.values(d.quotas).filter((q) => ['AI_WORKFLOWS', 'PITCHES_PROCESSED', 'RESEARCH_QUERIES', 'STORAGE_MB'].includes(q.metric)).map((q) => (
                <div key={q.metric} className="usage-row">
                  <span>{humanize(q.metric)}</span>
                  <span className="usage-track"><span style={{ width: q.unlimited ? '0%' : `${Math.min(100, (q.used / Math.max(1, q.limit)) * 100)}%` }} /></span>
                  <span className="muted small">{q.used}{q.unlimited ? '' : ` / ${q.limit}`}</span>
                </div>
              ))}
            </div>
            {d.aiUsageThisMonth.length > 0 && (
              <p className="muted small">Agent calls: {d.aiUsageThisMonth.reduce((n, u) => n + u.calls, 0)} · estimated model cost {formatUsd(d.aiCostThisMonthUsd)}</p>
            )}
          </Card>
        </>
      )}
      <ImportEmailModal open={importOpen} onClose={() => setImportOpen(false)} />
    </div>
  );
}
