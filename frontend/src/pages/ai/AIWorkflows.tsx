import { useEffect, useState } from 'react';
import type { FormEvent } from 'react';
import { PageContainer } from '../../components/layout/PageContainer';
import { Badge } from '../../components/ui/Badge';
import { Button } from '../../components/ui/Button';
import { Card } from '../../components/ui/Card';
import { EmptyState } from '../../components/ui/EmptyState';
import { ErrorState } from '../../components/ui/ErrorState';
import { Loading } from '../../components/ui/Loading';
import { Textarea } from '../../components/ui/Input';
import { workflowService } from '../../services/workflowService';
import { getErrorMessage } from '../../services/api';
import { useFetch } from '../../hooks/useFetch';
import { WORKFLOW_LABEL, WORKFLOW_TONE, formatDateTime, sortNewest } from '../../utils/format';

const SUGGESTIONS = [
  'Summarize my unread emails and create tasks from them',
  'Schedule a meeting with my team for next Tuesday',
  'Generate a weekly status report from my completed tasks',
];

export default function AIWorkflows() {
  const { data, loading, error, reload } = useFetch(workflowService.list);
  const [prompt, setPrompt] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [submitError, setSubmitError] = useState<string | null>(null);

  const workflows = sortNewest(data ?? []);
  const hasActive = workflows.some((w) => w.status === 'PENDING' || w.status === 'RUNNING');

  // Poll the backend while any workflow is still in progress.
  useEffect(() => {
    if (!hasActive) return;
    const id = window.setInterval(reload, 3000);
    return () => window.clearInterval(id);
  }, [hasActive, reload]);

  const handleSubmit = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    const value = prompt.trim();
    if (!value) {
      setSubmitError('Describe what you want the AI to do.');
      return;
    }
    setSubmitError(null);
    setSubmitting(true);
    try {
      await workflowService.run({ prompt: value });
      setPrompt('');
      reload();
    } catch (err: unknown) {
      setSubmitError(getErrorMessage(err));
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <PageContainer title="AI / Workflows" description="Ask for an action and follow its progress. Requests are handled by the backend.">
      <Card title="Ask the AI">
        <form className="form" onSubmit={handleSubmit} noValidate>
          <Textarea
            label="What should Pitsch do?"
            rows={4}
            placeholder="e.g. Draft a follow-up email and add a reminder task for Friday"
            value={prompt}
            onChange={(e) => setPrompt(e.target.value)}
            error={submitError ?? undefined}
          />
          <div className="chips" aria-label="Suggestions">
            {SUGGESTIONS.map((s) => (
              <button type="button" key={s} className="chip" onClick={() => setPrompt(s)}>
                {s}
              </button>
            ))}
          </div>
          <div className="form-actions">
            <Button type="submit" icon="send" loading={submitting}>
              {submitting ? 'Sending…' : 'Run workflow'}
            </Button>
          </div>
        </form>
      </Card>

      <Card
        title="Workflow runs"
        action={
          <Button variant="ghost" size="sm" icon="refresh" onClick={reload}>
            Refresh
          </Button>
        }
      >
        {loading ? (
          <Loading label="Loading workflows…" />
        ) : error ? (
          <ErrorState message={error} onRetry={reload} />
        ) : workflows.length === 0 ? (
          <EmptyState
            icon="ai"
            title="No workflows yet"
            description="Submit a request above and its status and result will appear here."
          />
        ) : (
          <ul className="workflow-list">
            {workflows.map((workflow) => (
              <li key={workflow.id} className="workflow-card">
                <div className="workflow-head">
                  <p className="workflow-prompt">{workflow.prompt}</p>
                  <Badge tone={WORKFLOW_TONE[workflow.status]}>
                    {workflow.status === 'RUNNING' && <span className="spinner spinner-xs" aria-hidden="true" />}
                    {WORKFLOW_LABEL[workflow.status]}
                  </Badge>
                </div>
                <small className="muted">
                  Started {formatDateTime(workflow.createdAt)}
                  {workflow.completedAt ? ` · Finished ${formatDateTime(workflow.completedAt)}` : ''}
                </small>
                {workflow.status === 'COMPLETED' && workflow.result && (
                  <pre className="workflow-result">{workflow.result}</pre>
                )}
                {workflow.status === 'FAILED' && (
                  <p className="form-error" role="alert">
                    {workflow.error ?? 'This workflow failed.'}
                  </p>
                )}
              </li>
            ))}
          </ul>
        )}
      </Card>
    </PageContainer>
  );
}