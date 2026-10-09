import { useState } from 'react';
import { Link } from 'react-router-dom';
import { notifications as notificationsApi } from '../lib/api/endpoints';
import { useAsync } from '../lib/hooks';
import { cx, timeAgo } from '../lib/format';
import { Button, Card, EmptyState, ErrorState, PageHeader, Pagination, Skeleton, Tabs } from '../components/ui';

export default function Notifications() {
  const [filter, setFilter] = useState<'all' | 'unread'>('unread');
  const [page, setPage] = useState(0);
  const list = useAsync(() => notificationsApi.list(page, filter === 'unread' || undefined), [filter, page]);

  const markAll = async () => {
    await notificationsApi.markAllRead();
    list.reload();
  };
  const open = async (id: number, read: boolean) => {
    if (!read) await notificationsApi.markRead(id).catch(() => undefined);
  };

  return (
    <div className="stack-lg">
      <PageHeader title="Notifications" actions={<>
        <Link className="btn btn-ghost" to="/settings/notifications">Preferences</Link>
        <Button onClick={markAll}>Mark all read</Button>
      </>} />
      <Tabs value={filter} onChange={(v) => { setFilter(v); setPage(0); }} tabs={[{ value: 'unread', label: 'Unread' }, { value: 'all', label: 'All' }]} />
      <Card padded={false}>
        {list.error ? <ErrorState error={list.error} onRetry={list.reload} /> : !list.data ? <div className="pad"><Skeleton /></div> : list.data.items.length === 0 ? (
          <EmptyState title={filter === 'unread' ? 'No unread notifications' : 'No notifications'} />
        ) : (
          <>
            <ul className="list">
              {list.data.items.map((n) => (
                <li key={n.id} className={cx('notification', !n.read && 'notification-unread')}>
                  {n.workflowId ? (
                    <Link to={`/workflows/${n.workflowId}`} className="list-row" onClick={() => open(n.id, n.read)}>
                      <div><strong>{n.title}</strong><div className="muted small">{n.message}</div></div>
                      <span className="muted small">{timeAgo(n.createdAt)}</span>
                    </Link>
                  ) : (
                    <button type="button" className="list-row text-left" onClick={async () => { await open(n.id, n.read); list.reload(); }}>
                      <div><strong>{n.title}</strong><div className="muted small">{n.message}</div></div>
                      <span className="muted small">{timeAgo(n.createdAt)}</span>
                    </button>
                  )}
                </li>
              ))}
            </ul>
            <div className="pad"><Pagination page={list.data.page} totalPages={list.data.totalPages} totalItems={list.data.totalItems} onPage={setPage} /></div>
          </>
        )}
      </Card>
    </div>
  );
}
