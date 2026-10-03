import { useState } from 'react';
import { Badge } from '../../components/ui/Badge';
import { Button } from '../../components/ui/Button';
import { Card } from '../../components/ui/Card';
import { getErrorMessage } from '../../services/api';
import { workflowService } from '../../services/workflowService';
import type { Brief, Finding, ID, Provenance } from '../../types';
import {
  CLAIM_STATUS_LABEL,
  CLAIM_STATUS_TONE,
  PROVENANCE_LABEL,
  PROVENANCE_TONE,
  formatDate,
} from '../../utils/format';

function Prov({ p }: { p: Provenance }) {
  return <Badge tone={PROVENANCE_TONE[p]}>{PROVENANCE_LABEL[p]}</Badge>;
}

function Findings({ title, items }: { title: string; items: Finding[] }) {
  if (items.length === 0) return null;
  return (
    <section className="brief-section">
      <h3 className="section-title">{title}</h3>
      <ul className="finding-list">
        {items.map((f, i) => (
          <li key={i}>
            <span>{f.statement}</span>
            {f.citations.length > 0 && <small className="muted"> [{f.citations.join(', ')}]</small>} <Prov p={f.provenance} />
          </li>
        ))}
      </ul>
    </section>
  );
}

interface Props {
  workflowId: ID;
  brief: Brief;
  companyName?: string | null;
}

/** The decision-ready research brief. Facts, claims and AI synthesis are visibly labelled; there is no verdict. */
export function BriefView({ workflowId, brief, companyName }: Props) {
  const [downloading, setDownloading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const download = async () => {
    setDownloading(true);
    setError(null);
    try {
      const md = await workflowService.briefMarkdown(workflowId);
      const url = URL.createObjectURL(new Blob([md], { type: 'text/markdown' }));
      const a = document.createElement('a');
      a.href = url;
      a.download = `${(companyName ?? 'pitch').replace(/[^\w-]+/g, '-').toLowerCase()}-brief.md`;
      a.click();
      URL.revokeObjectURL(url);
    } catch (err: unknown) {
      setError(getErrorMessage(err));
    } finally {
      setDownloading(false);
    }
  };

  return (
    <Card
      title="Research brief"
      className="brief"
      action={
        <Button variant="ghost" size="sm" onClick={() => void download()} loading={downloading}>
          Download .md
        </Button>
      }
    >
      {error && <p className="form-error" role="alert">{error}</p>}
      <div className="legend" aria-label="Legend">
        {(Object.keys(PROVENANCE_LABEL) as Provenance[]).map((p) => (
          <Prov key={p} p={p} />
        ))}
      </div>

      <Findings title="Summary" items={brief.executiveSummary} />

      {brief.companyOverview.length > 0 && (
        <section className="brief-section">
          <h3 className="section-title">Company overview</h3>
          <div className="table-wrap">
            <table className="brief-table">
              <tbody>
                {brief.companyOverview.map((row) => (
                  <tr key={row.field}>
                    <th scope="row">{row.field}</th>
                    <td>{row.value}</td>
                    <td className="cell-tag"><Prov p={row.provenance} /></td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </section>
      )}

      {brief.claimsMatrix.length > 0 && (
        <section className="brief-section">
          <h3 className="section-title">Claims from the pitch</h3>
          <div className="table-wrap">
            <table className="brief-table">
              <thead>
                <tr>
                  <th>Pitch claim</th>
                  <th>Status</th>
                  <th>What the evidence shows</th>
                  <th>Sources</th>
                </tr>
              </thead>
              <tbody>
                {brief.claimsMatrix.map((c) => (
                  <tr key={c.claimId}>
                    <td>{c.claim}</td>
                    <td>
                      <Badge tone={CLAIM_STATUS_TONE[c.status] ?? 'neutral'}>{CLAIM_STATUS_LABEL[c.status] ?? c.status}</Badge>
                      {c.evidenceOutdated && <small className="muted block">may be outdated</small>}
                    </td>
                    <td>{c.finding ?? '—'}</td>
                    <td>
                      {[...c.supporting, ...c.contradicting].length === 0
                        ? '—'
                        : [...c.supporting, ...c.contradicting].map((e) => (
                            <a key={e.evidenceId} href={e.sourceUrl} target="_blank" rel="noreferrer" className="source-link">
                              {e.sourceTitle}
                            </a>
                          ))}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </section>
      )}

      {brief.tractionMetrics.length > 0 && (
        <section className="brief-section">
          <h3 className="section-title">Metrics stated in the pitch</h3>
          <ul className="finding-list">
            {brief.tractionMetrics.map((m) => (
              <li key={m.metric}>
                <strong>{m.metric}:</strong> {m.value}
                {m.period ? ` (${m.period})` : ''} <Prov p="PITCH" />
              </li>
            ))}
          </ul>
        </section>
      )}

      <Findings title="Market" items={brief.market} />
      <Findings title="Competition" items={brief.competition} />
      {brief.competitors.length > 0 && (
        <p className="muted small">Competitors identified: {brief.competitors.map((c) => c.name).join(', ')}</p>
      )}
      <Findings title="Founders" items={brief.founders} />
      <Findings title="Funding history" items={brief.fundingHistory} />

      {brief.risks.length > 0 && (
        <section className="brief-section">
          <h3 className="section-title">Risks and considerations <Prov p="AI_INFERENCE" /></h3>
          <ul className="finding-list">
            {brief.risks.map((r, i) => (
              <li key={i}>
                <strong>{r.category.charAt(0) + r.category.slice(1).toLowerCase()}:</strong> {r.risk}
              </li>
            ))}
          </ul>
        </section>
      )}

      {brief.openQuestions.length > 0 && (
        <section className="brief-section">
          <h3 className="section-title">Open questions for you</h3>
          <ol className="question-list">
            {brief.openQuestions.map((q, i) => (
              <li key={i}>{q.question}</li>
            ))}
          </ol>
        </section>
      )}

      {brief.sources.length > 0 && (
        <section className="brief-section">
          <h3 className="section-title">Sources</h3>
          <ul className="source-list">
            {brief.sources.map((s) => (
              <li key={s.sourceId}>
                <a href={s.url} target="_blank" rel="noreferrer">{s.title}</a>
                <small className="muted">
                  {' '}· {s.sourceType.toLowerCase()} · {s.publishedAt ? formatDate(s.publishedAt) : 'date unknown'}
                  {s.possiblyOutdated ? ' · may be outdated' : ''}
                </small>
              </li>
            ))}
          </ul>
        </section>
      )}

      <p className="muted small disclaimer">
        This brief organises information from the pitch and public sources. It contains no investment recommendation —
        the decision is yours. Items marked “AI inference” are the model’s synthesis and should be checked.
      </p>
    </Card>
  );
}
