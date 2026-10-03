import { useMemo, useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { PageContainer } from '../../components/layout/PageContainer';
import { Badge } from '../../components/ui/Badge';
import { Card } from '../../components/ui/Card';
import { EmptyState } from '../../components/ui/EmptyState';
import { ErrorState } from '../../components/ui/ErrorState';
import { Icon, type IconName } from '../../components/ui/Icon';
import { Loading } from '../../components/ui/Loading';
import { activityService } from '../../services/activityService';
import { calendarService } from '../../services/calendarService';
import { taskService } from '../../services/taskService';
import { workflowService } from '../../services/workflowService';
import { useCurrentUser } from '../../hooks/useCurrentUser';
import { useFetch } from '../../hooks/useFetch';
import { MonthGrid } from '../calendar/MonthGrid';
import {
  PRIORITY_LABEL,
  PRIORITY_TONE,
  STATUS_LABEL,
  STATUS_TONE,
  WORKFLOW_LABEL,
  WORKFLOW_TONE,
  cx,
  formatDate,
  formatTime,
  greeting,
  sortActivity,
  sortNewest,
  timeAgo,
  upcomingEvents,
} from '../../utils/format';

interface StatCardProps {
  label: string;
  value: number | null;
  icon: IconName;
  tone: 'purple' | 'blue' | 'green' | 'orange';
}

function StatCard({ label, value, icon, tone }: StatCardProps) {
  return (
    <div className="card stat-card">
      <span className={cx('stat-icon', `stat-${tone}`)}>
        <Icon name={icon} size={20} />
      </span>
      <div>
        <p className="stat-label">{label}</p>
        <p className="stat-value">{value === null ? '—' : value}</p>
      </div>
    </div>
  );
}

export default function Dashboard() {
  const navigate = useNavigate();
  const { user } = useCurrentUser();
  const tasks = useFetch(taskService.list);
  const events = useFetch(calendarService.list);
  const workflows = useFetch(workflowService.list);
  const activity = useFetch(activityService.list);
  const [month, setMonth] = useState(() => new Date());

  const allTasks = useMemo(() => tasks.data ?? [], [tasks.data]);
  const allEvents = useMemo(() => events.data ?? [], [events.data]);
  const recentTasks = useMemo(() => sortNewest(allTasks).slice(0, 5), [allTasks]);
  const upcoming = useMemo(() => upcomingEvents(allEvents), [allEvents]);
  const recentWorkflows = useMemo(() => sortNewest(workflows.data ?? []).slice(0, 3), [workflows.data]);
  const recentActivity = useMemo(() => sortActivity(activity.data ?? []).slice(0, 6), [activity.data]);

  const firstName = user?.name.split(' ')[0];

  return (
    <PageContainer
      title={`${greeting()}${firstName ? `, ${firstName}` : ''}!`}
      description="Here's what's happening in your workspace."
    >
      <div className="stats-grid">
        <StatCard label="Total tasks" value={tasks.data ? allTasks.length : null} icon="tasks" tone="purple" />
        <StatCard
          label="Completed tasks"
          value={tasks.data ? allTasks.filter((t) => t.status === 'DONE').length : null}
          icon="check"
          tone="green"
        />
        <StatCard label="Upcoming events" value={events.data ? upcoming.length : null} icon="calendar" tone="blue" />
        <StatCard
          label="Pitches needing you"
          value={
            workflows.data
              ? workflows.data.filter((w) => ['AWAITING_USER', 'WAITING_FOR_APPROVAL', 'FAILED'].includes(w.status)).length
              : null
          }
          icon="ai"
          tone="orange"
        />
      </div>

      <div className="dash-grid">
        <Card
          title="Recent tasks"
          action={
            <Link to="/tasks" className="card-link">
              View all
            </Link>
          }
        >
          {tasks.loading ? (
            <Loading />
          ) : tasks.error ? (
            <ErrorState message={tasks.error} onRetry={tasks.reload} />
          ) : recentTasks.length === 0 ? (
            <EmptyState icon="tasks" title="No tasks yet" description="Create a task to see it here." />
          ) : (
            <ul className="list">
              {recentTasks.map((task) => (
                <li key={task.id} className="list-item">
                  <div className="list-main">
                    <p className={cx('list-title', task.status === 'DONE' && 'strike')}>{task.title}</p>
                    <small className="muted">{task.dueDate ? `Due ${formatDate(task.dueDate)}` : 'No due date'}</small>
                  </div>
                  <Badge tone={PRIORITY_TONE[task.priority]}>{PRIORITY_LABEL[task.priority]}</Badge>
                  <Badge tone={STATUS_TONE[task.status]}>{STATUS_LABEL[task.status]}</Badge>
                </li>
              ))}
            </ul>
          )}
        </Card>

        <Card
          title="Upcoming events"
          action={
            <Link to="/calendar" className="card-link">
              View all
            </Link>
          }
        >
          {events.loading ? (
            <Loading />
          ) : events.error ? (
            <ErrorState message={events.error} onRetry={events.reload} />
          ) : upcoming.length === 0 ? (
            <EmptyState icon="calendar" title="No upcoming events" description="Your schedule is clear." />
          ) : (
            <ul className="list">
              {upcoming.slice(0, 4).map((event) => (
                <li key={event.id} className="list-item">
                  <div className="date-chip" aria-hidden="true">
                    <strong>{new Date(event.startTime).getDate()}</strong>
                    <small>{new Date(event.startTime).toLocaleDateString(undefined, { month: 'short' })}</small>
                  </div>
                  <div className="list-main">
                    <p className="list-title">{event.title}</p>
                    <small className="muted">
                      {formatTime(event.startTime)} – {formatTime(event.endTime)}
                    </small>
                  </div>
                </li>
              ))}
            </ul>
          )}
        </Card>

        <Card title="Calendar">
          {events.loading ? (
            <Loading />
          ) : events.error ? (
            <ErrorState message={events.error} onRetry={events.reload} />
          ) : (
            <MonthGrid
              month={month}
              events={allEvents}
              onSelect={() => navigate('/calendar')}
              onMonthChange={setMonth}
            />
          )}
        </Card>

        <Card title="Quick actions">
          <div className="quick-actions">
            <Link to="/tasks?new=1" className="quick-action">
              <Icon name="plus" />
              <span>New task</span>
            </Link>
            <Link to="/calendar?new=1" className="quick-action">
              <Icon name="calendar" />
              <span>New event</span>
            </Link>
            <Link to="/pitches?new=1" className="quick-action">
              <Icon name="ai" />
              <span>Submit pitch</span>
            </Link>
            <Link to="/settings" className="quick-action">
              <Icon name="settings" />
              <span>Settings</span>
            </Link>
          </div>
        </Card>

        <Card
          title="Pitches"
          action={
            <Link to="/pitches" className="card-link">
              Open
            </Link>
          }
        >
          {workflows.loading ? (
            <Loading />
          ) : workflows.error ? (
            <ErrorState message={workflows.error} onRetry={workflows.reload} />
          ) : recentWorkflows.length === 0 ? (
            <EmptyState icon="ai" title="No pitches yet" description="Submit a pitch email and the agents will research it." />
          ) : (
            <ul className="list">
              {recentWorkflows.map((workflow) => (
                <li key={workflow.id} className="list-item">
                  <Link to={`/workflows/${workflow.id}`} className="list-main list-link">
                    <p className="list-title clamp">{workflow.companyName ?? workflow.prompt}</p>
                    <small className="muted">{timeAgo(workflow.createdAt)}</small>
                  </Link>
                  <Badge tone={WORKFLOW_TONE[workflow.status]}>{WORKFLOW_LABEL[workflow.status]}</Badge>
                </li>
              ))}
            </ul>
          )}
        </Card>

        <Card title="Recent activity">
          {activity.loading ? (
            <Loading />
          ) : activity.error ? (
            <ErrorState message={activity.error} onRetry={activity.reload} />
          ) : recentActivity.length === 0 ? (
            <EmptyState icon="activity" title="No activity yet" description="Actions you take will be listed here." />
          ) : (
            <ul className="list">
              {recentActivity.map((item) => (
                <li key={item.id} className="list-item">
                  <span className="timeline-dot" aria-hidden="true" />
                  <div className="list-main">
                    <p>{item.message}</p>
                    <small className="muted">{timeAgo(item.createdAt)}</small>
                  </div>
                </li>
              ))}
            </ul>
          )}
        </Card>
      </div>
    </PageContainer>
  );
}