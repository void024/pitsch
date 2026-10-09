import type { DealStage, Provenance, WorkflowStatus } from '../lib/api/types';
import {
  ASSESSMENT_LABEL, ASSESSMENT_TONE, PROVENANCE_LABEL, PROVENANCE_TONE, RISK_TONE, STAGE_LABEL, STAGE_TONE,
  WORKFLOW_LABEL, WORKFLOW_TONE, isWorkflowActive,
} from '../lib/format';
import { Badge } from './ui';

export function WorkflowBadge({ status }: { status: WorkflowStatus }) {
  return (
    <Badge tone={WORKFLOW_TONE[status] ?? 'neutral'}>
      {isWorkflowActive(status) && <span className="pulse" aria-hidden />}
      {WORKFLOW_LABEL[status] ?? status}
    </Badge>
  );
}

export function StageBadge({ stage }: { stage: DealStage }) {
  return <Badge tone={STAGE_TONE[stage] ?? 'neutral'}>{STAGE_LABEL[stage] ?? stage}</Badge>;
}

export function AssessmentBadge({ assessment }: { assessment: string }) {
  return <Badge tone={ASSESSMENT_TONE[assessment] ?? 'neutral'}>{ASSESSMENT_LABEL[assessment] ?? assessment}</Badge>;
}

export function ProvenanceTag({ provenance }: { provenance: Provenance }) {
  return <Badge tone={PROVENANCE_TONE[provenance] ?? 'neutral'} title="Where this statement comes from">{PROVENANCE_LABEL[provenance] ?? provenance}</Badge>;
}

export function RiskBadge({ level }: { level: string | null }) {
  if (!level) return <span className="muted">—</span>;
  return (
    <Badge tone={RISK_TONE[level] ?? 'neutral'} title="Evidence risk: how well the pitch's claims are supported. Not a view on the company.">
      {level.toLowerCase()} evidence risk
    </Badge>
  );
}
