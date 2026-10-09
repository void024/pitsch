import { useState, type FormEvent } from 'react';
import { useNavigate } from 'react-router-dom';
import { newIdempotencyKey } from '../lib/api/client';
import { emails } from '../lib/api/endpoints';
import { formatBytes } from '../lib/format';
import { Button, InlineError, Modal, TextArea, TextInput, useToast } from './ui';

const MAX_FILES = 10;
const MAX_MB = 15;
const ACCEPT = '.pdf,.pptx,.txt,.md,.csv,application/pdf,application/vnd.openxmlformats-officedocument.presentationml.presentation,text/plain';

/**
 * Manual import of a pitch email (for inboxes not connected to Gmail). The backend validates every file by its
 * content, scans it, de-duplicates by Message-ID and starts the workflow; the AI only reads it.
 */
export function ImportEmailModal({ open, onClose }: { open: boolean; onClose: () => void }) {
  const navigate = useNavigate();
  const toast = useToast();
  const [sender, setSender] = useState('');
  const [senderName, setSenderName] = useState('');
  const [subject, setSubject] = useState('');
  const [body, setBody] = useState('');
  const [files, setFiles] = useState<File[]>([]);
  const [error, setError] = useState<unknown>(null);
  const [busy, setBusy] = useState(false);
  const [key, setKey] = useState(() => newIdempotencyKey('import'));

  const reset = () => {
    setSender(''); setSenderName(''); setSubject(''); setBody(''); setFiles([]); setError(null);
    setKey(newIdempotencyKey('import'));
  };

  const addFiles = (list: FileList | null) => {
    if (!list) return;
    const next = [...files, ...Array.from(list)].slice(0, MAX_FILES);
    const tooBig = next.find((f) => f.size > MAX_MB * 1024 * 1024);
    setError(tooBig ? new Error(`${tooBig.name} is larger than ${MAX_MB} MB.`) : null);
    setFiles(next.filter((f) => f.size <= MAX_MB * 1024 * 1024));
  };

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    setBusy(true);
    setError(null);
    const form = new FormData();
    form.set('sender', sender.trim());
    if (senderName.trim()) form.set('senderName', senderName.trim());
    if (subject.trim()) form.set('subject', subject.trim());
    if (body.trim()) form.set('body', body);
    files.forEach((f) => form.append('files', f, f.name));
    try {
      const res = await emails.import(form, key);
      toast(res.duplicate ? 'This email was already imported.' : 'Email imported — the agents are reading it.', 'success');
      if (res.skippedFiles.length) toast(`Skipped unsupported files: ${res.skippedFiles.join(', ')}`, 'info');
      reset();
      onClose();
      if (res.workflowId) navigate(`/workflows/${res.workflowId}`);
    } catch (err) {
      setError(err);
    } finally {
      setBusy(false);
    }
  };

  return (
    <Modal open={open} onClose={onClose} title="Import a pitch email" wide
      footer={<>
        <Button variant="ghost" onClick={onClose}>Cancel</Button>
        <Button variant="primary" type="submit" form="import-email" loading={busy} disabled={!sender.trim()}>Import and analyse</Button>
      </>}>
      <form id="import-email" className="stack" onSubmit={submit}>
        <div className="grid-2">
          <TextInput label="From (email)" type="email" required value={sender} onChange={(e) => setSender(e.target.value)} placeholder="founder@startup.com" autoComplete="off" />
          <TextInput label="Sender name" value={senderName} onChange={(e) => setSenderName(e.target.value)} placeholder="Optional" />
        </div>
        <TextInput label="Subject" value={subject} onChange={(e) => setSubject(e.target.value)} maxLength={1000} />
        <TextArea label="Email body" rows={8} value={body} onChange={(e) => setBody(e.target.value)} placeholder="Paste the email text" />
        <div className="field">
          <label htmlFor="import-files">Attachments</label>
          <input id="import-files" type="file" multiple accept={ACCEPT} onChange={(e) => addFiles(e.target.files)} />
          <span className="field-hint">PDF, PowerPoint (.pptx) or text · up to {MAX_FILES} files · {MAX_MB} MB each. Files are checked by content and scanned before analysis.</span>
          {files.length > 0 && (
            <ul className="file-list">
              {files.map((f, i) => (
                <li key={`${f.name}-${i}`}>
                  <span>{f.name}</span><span className="muted small">{formatBytes(f.size)}</span>
                  <button type="button" className="link-btn" onClick={() => setFiles(files.filter((_, j) => j !== i))}>Remove</button>
                </li>
              ))}
            </ul>
          )}
        </div>
        <InlineError error={error} />
      </form>
    </Modal>
  );
}
