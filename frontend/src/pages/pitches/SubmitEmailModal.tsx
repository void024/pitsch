import { useState } from 'react';
import type { FormEvent } from 'react';
import { Button } from '../../components/ui/Button';
import { Input, Textarea } from '../../components/ui/Input';
import { Modal } from '../../components/ui/Modal';
import { getErrorMessage } from '../../services/api';
import { workflowService } from '../../services/workflowService';
import type { ID } from '../../types';
import { formatBytes } from '../../utils/format';

const MAX_BYTES = 10 * 1024 * 1024;
const ACCEPT = '.pdf,.pptx,.txt,.md';

const SAMPLE = {
  sender: 'ananya@krishiai.in',
  senderName: 'Ananya Rao',
  subject: 'Krishi AI - raising our $2M seed',
  body: `Hi,

I'm Ananya, co-founder & CEO of Krishi AI (www.krishiai.in). We give smallholder farmers AI crop advisory over WhatsApp in 6 Indian languages.

Traction: 12,000 farmers onboarded across Karnataka, Maharashtra and Telangana; Rs 1.2 Cr ARR, growing 40% quarter on quarter. We are the market leader in AI agri-advisory for smallholders.

We're raising a $2M seed on a SAFE to expand to 3 more states. Would love 30 minutes of your time.

Best,
Ananya Rao`,
};

function readAsDataUrl(file: File): Promise<string> {
  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = () => resolve(String(reader.result));
    reader.onerror = () => reject(new Error(`Could not read ${file.name}`));
    reader.readAsDataURL(file);
  });
}

interface Props {
  onClose: () => void;
  onCreated: (workflowId: ID) => void;
}

/** Simulates an incoming email (until Gmail ingestion is connected): the backend classifies it with the AI. */
export function SubmitEmailModal({ onClose, onCreated }: Props) {
  const [sender, setSender] = useState('');
  const [senderName, setSenderName] = useState('');
  const [subject, setSubject] = useState('');
  const [body, setBody] = useState('');
  const [file, setFile] = useState<File | null>(null);
  const [errors, setErrors] = useState<{ sender?: string; body?: string; file?: string; form?: string }>({});
  const [submitting, setSubmitting] = useState(false);

  const fillSample = () => {
    setSender(SAMPLE.sender);
    setSenderName(SAMPLE.senderName);
    setSubject(SAMPLE.subject);
    setBody(SAMPLE.body);
  };

  const handleSubmit = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    const next: typeof errors = {};
    if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(sender.trim())) next.sender = 'Enter the sender’s email address.';
    if (!subject.trim() && !body.trim() && !file) next.body = 'Add a subject, a message or a deck.';
    if (file && file.size > MAX_BYTES) next.file = `The deck is ${formatBytes(file.size)}; the limit is 10 MB.`;
    setErrors(next);
    if (Object.keys(next).length > 0) return;

    setSubmitting(true);
    try {
      const attachments = file
        ? [{ filename: file.name, mimeType: file.type || 'application/octet-stream', contentBase64: await readAsDataUrl(file) }]
        : [];
      const res = await workflowService.submitEmail({
        sender: sender.trim(),
        senderName: senderName.trim() || undefined,
        subject: subject.trim(),
        body,
        attachments,
      });
      onCreated(res.workflowId);
    } catch (err: unknown) {
      setErrors({ form: getErrorMessage(err) });
      setSubmitting(false);
    }
  };

  return (
    <Modal
      title="Submit a pitch email"
      onClose={onClose}
      footer={
        <>
          <Button variant="ghost" onClick={fillSample} disabled={submitting}>
            Use sample pitch
          </Button>
          <Button variant="ghost" onClick={onClose} disabled={submitting}>
            Cancel
          </Button>
          <Button type="submit" form="submit-email-form" icon="send" loading={submitting}>
            Process email
          </Button>
        </>
      }
    >
      <form id="submit-email-form" className="form" onSubmit={handleSubmit} noValidate>
        <p className="muted">
          Paste an email as if it had just arrived. Pitsch classifies it, and you decide whether to handle it.
        </p>
        <div className="form-row">
          <Input label="From (email)" type="email" value={sender} onChange={(e) => setSender(e.target.value)} error={errors.sender} placeholder="founder@startup.com" />
          <Input label="Sender name" value={senderName} onChange={(e) => setSenderName(e.target.value)} placeholder="Optional" />
        </div>
        <Input label="Subject" value={subject} onChange={(e) => setSubject(e.target.value)} />
        <Textarea label="Message" rows={9} value={body} onChange={(e) => setBody(e.target.value)} error={errors.body} />
        <div className="field">
          <label htmlFor="deck-file">Pitch deck (optional — PDF, PPTX or TXT, max 10 MB)</label>
          <input
            id="deck-file"
            type="file"
            accept={ACCEPT}
            className="control"
            onChange={(e) => setFile(e.target.files?.[0] ?? null)}
          />
          {file && !errors.file && <p className="field-hint">{file.name} · {formatBytes(file.size)}</p>}
          {errors.file && <p className="field-error" role="alert">{errors.file}</p>}
        </div>
        {errors.form && <p className="form-error" role="alert">{errors.form}</p>}
      </form>
    </Modal>
  );
}
