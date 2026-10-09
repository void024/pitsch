import type { ReactNode } from 'react';
import type { Brief, Finding } from '../lib/api/types';
import { assessmentOf, formatDate, formatDateTime } from '../lib/format';
import { safeHttpUrl } from '../lib/safeUrl';
import { AssessmentBadge, ProvenanceTag } from './badges';
import { Badge, Card, EmptyState } from './ui';

function ExternalLink({ href, children }: { href: string; children: ReactNode }) {
  const safe = safeHttpUrl(href);
  if (!safe) return <span>{children}</span>;
  return <a href={safe} target="_blank" rel="noopener noreferrer nofollow">{children}</a>;
}

function Findings({ title, items }: { title: string; items?: Finding[] }) {
  if (!items || items.length === 0) return null;
  return (
    <Card title={title}>
      <ul className="findings">
        {items.map((f, i) => (
          <li key={i}>
            <span>{f.statement}</span>
            <span className="finding-meta">
              <ProvenanceTag provenance={f.provenance} />
              {f.citations.length > 0 && <span className="mono small muted">{f.citations.join(', ')}</span>}
            </span>
          </li>
        ))}
      </ul>
    </Card>
  );
}

/**
 * The investment brief. Every statement shows its provenance (pitch, company source, external source, AI
 * inference); claims show their evidence assessment. The brief never contains an invest / don't-invest verdict.
 */
export function BriefView({ brief, updatedAt }: { brief: Brief | null | undefined; updatedAt?: string | null }) {
  if (!brief) return <EmptyState title="No brief yet">The brief appears here once the agents have finished.</EmptyState>;
  const conf = brief.aiConfidence;
  return (
    <div className="brief stack">
      <div className="brief-notice">
        <strong>Research brief — not investment advice.</strong> Pitsch organises the pitch and public evidence; the
        decision is yours. Items marked “AI inference” are model synthesis and should be checked.
        {(brief.generatedAt || updatedAt) && <span className="muted small"> Last updated {formatDateTime(brief.generatedAt ?? updatedAt)}.</span>}
      </div>

      {conf && (
        <Card title="Evidence support">
          <div className="row gap-sm wrap">
            <Badge tone={conf.level === 'HIGH' ? 'green' : conf.level === 'MEDIUM' ? 'orange' : 'red'}>
              {conf.level.toLowerCase()} support · {Math.round(conf.score * 100)}%
            </Badge>
          </div>
          <ul className="bullets">{conf.reasons.map((r, i) => <li key={i}>{r}</li>)}</ul>
          <p className="muted small">{conf.note}</p>
        </Card>
      )}

      <Findings title="Summary" items={brief.executiveSummary} />

      {brief.companyOverview.length > 0 && (
        <Card title="Company overview">
          <dl className="overview">
            {brief.companyOverview.map((row) => (
              <div key={row.field}><dt>{row.field}</dt><dd>{row.value}</dd></div>
            ))}
          </dl>
        </Card>
      )}

      <Findings title="Problem" items={brief.problem} />
      <Findings title="Solution" items={brief.solution} />
      <Findings title="Product" items={brief.product} />

      {brief.claimsMatrix.length > 0 && (
        <Card title="Claims and evidence" padded={false}>
          <div className="table-wrap">
            <table className="table">
              <thead><tr><th>#</th><th>Claim from the pitch</th><th>Assessment</th><th>What the evidence shows</th><th>Sources</th></tr></thead>
              <tbody>
                {brief.claimsMatrix.map((c) => (
                  <tr key={c.claimId}>
                    <td className="mono small">{c.claimId}</td>
                    <td>{c.claim}</td>
                    <td>
                      <AssessmentBadge assessment={assessmentOf(c)} />
                      {c.evidenceOutdated && <div className="muted small">evidence may be outdated</div>}
                    </td>
                    <td className="small">{c.finding ?? '—'}</td>
                    <td className="small">
                      {[...c.supporting, ...c.contradicting].map((e) => (
                        <div key={e.evidenceId}><ExternalLink href={e.sourceUrl}>{e.sourceTitle || e.evidenceId}</ExternalLink></div>
                      ))}
                      {c.supporting.length + c.contradicting.length === 0 && '—'}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </Card>
      )}

      {brief.tractionMetrics.length > 0 && (
        <Card title="Traction (as stated in the pitch)">
          <dl className="overview">
            {brief.tractionMetrics.map((m, i) => <div key={i}><dt>{m.metric}</dt><dd>{m.value}{m.period ? ` (${m.period})` : ''}</dd></div>)}
          </dl>
        </Card>
      )}

      <Findings title="Business model" items={brief.businessModel} />
      <Findings title="Market" items={brief.market} />
      <Findings title="Competition" items={brief.competition} />
      {brief.competitors.length > 0 && (
        <p className="muted small">Competitors identified: {brief.competitors.map((c) => c.name).join(', ')}</p>
      )}
      <Findings title="Founders" items={brief.founders} />
      <Findings title="Funding history" items={brief.fundingHistory} />

      {brief.fundraising && (
        <Card title="Fundraising (as stated in the pitch)">
          <dl className="overview">
            {brief.fundraising.amountRequested && <div><dt>Raising</dt><dd>{brief.fundraising.amountRequested}{brief.fundraising.currency ? ` ${brief.fundraising.currency}` : ''}</dd></div>}
            {brief.fundraising.instrument && <div><dt>Instrument</dt><dd>{brief.fundraising.instrument}</dd></div>}
            {brief.fundraising.valuation && <div><dt>Valuation</dt><dd>{brief.fundraising.valuation}</dd></div>}
            {brief.fundraising.useOfFunds.length > 0 && <div><dt>Use of funds</dt><dd>{brief.fundraising.useOfFunds.join('; ')}</dd></div>}
          </dl>
        </Card>
      )}

      <Findings title="Opportunities (conditional, AI synthesis)" items={brief.opportunities} />

      {brief.risks.length > 0 && (
        <Card title="Risks and diligence considerations">
          <ul className="findings">
            {brief.risks.map((r, i) => (
              <li key={i}><span><strong>{r.category.toLowerCase()}:</strong> {r.risk}</span>
                <span className="finding-meta"><ProvenanceTag provenance="AI_INFERENCE" /></span></li>
            ))}
          </ul>
        </Card>
      )}

      {brief.missingInformation && brief.missingInformation.length > 0 && (
        <Card title="Missing information"><ul className="bullets">{brief.missingInformation.map((m, i) => <li key={i}>{m}</li>)}</ul></Card>
      )}

      {brief.openQuestions.length > 0 && (
        <Card title="Questions to ask the founder">
          <ol className="numbered">{brief.openQuestions.map((q, i) => <li key={i}>{q.question}{q.reason && <div className="muted small">{q.reason}</div>}</li>)}</ol>
        </Card>
      )}

      {brief.sources.length > 0 && (
        <Card title="Sources">
          <ul className="sources">
            {brief.sources.map((s) => (
              <li key={s.sourceId}>
                <span className="mono small muted">{s.sourceId}</span>{' '}
                <ExternalLink href={s.url}>{s.title}</ExternalLink>{' '}
                <span className="muted small">· {s.sourceType.toLowerCase()} · {s.publishedAt ? formatDate(s.publishedAt) : 'date unknown'}</span>
                {s.possiblyOutdated && <Badge tone="orange">may be outdated</Badge>}
              </li>
            ))}
          </ul>
        </Card>
      )}
    </div>
  );
}
