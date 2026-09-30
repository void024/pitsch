import { useId } from 'react';
import type { InputHTMLAttributes, SelectHTMLAttributes, TextareaHTMLAttributes } from 'react';
import { cx } from '../../utils/format';

interface FieldProps {
  label: string;
  error?: string;
  hint?: string;
  hideLabel?: boolean;
}

function FieldShell({
  id,
  label,
  error,
  hint,
  hideLabel,
  children,
}: FieldProps & { id: string; children: React.ReactNode }) {
  return (
    <div className="field">
      <label htmlFor={id} className={cx(hideLabel && 'sr-only')}>
        {label}
      </label>
      {children}
      {hint && !error && <p className="field-hint">{hint}</p>}
      {error && (
        <p id={`${id}-error`} className="field-error" role="alert">
          {error}
        </p>
      )}
    </div>
  );
}

export function Input({
  label,
  error,
  hint,
  hideLabel,
  id,
  className,
  ...rest
}: FieldProps & InputHTMLAttributes<HTMLInputElement>) {
  const autoId = useId();
  const inputId = id ?? autoId;
  return (
    <FieldShell id={inputId} label={label} error={error} hint={hint} hideLabel={hideLabel}>
      <input
        id={inputId}
        className={cx('control', error && 'control-error', className)}
        aria-invalid={Boolean(error)}
        aria-describedby={error ? `${inputId}-error` : undefined}
        {...rest}
      />
    </FieldShell>
  );
}

export function Select({
  label,
  error,
  hint,
  hideLabel,
  id,
  className,
  children,
  ...rest
}: FieldProps & SelectHTMLAttributes<HTMLSelectElement>) {
  const autoId = useId();
  const selectId = id ?? autoId;
  return (
    <FieldShell id={selectId} label={label} error={error} hint={hint} hideLabel={hideLabel}>
      <select
        id={selectId}
        className={cx('control', error && 'control-error', className)}
        aria-invalid={Boolean(error)}
        {...rest}
      >
        {children}
      </select>
    </FieldShell>
  );
}

export function Textarea({
  label,
  error,
  hint,
  hideLabel,
  id,
  className,
  ...rest
}: FieldProps & TextareaHTMLAttributes<HTMLTextAreaElement>) {
  const autoId = useId();
  const areaId = id ?? autoId;
  return (
    <FieldShell id={areaId} label={label} error={error} hint={hint} hideLabel={hideLabel}>
      <textarea
        id={areaId}
        className={cx('control', 'control-area', error && 'control-error', className)}
        aria-invalid={Boolean(error)}
        aria-describedby={error ? `${areaId}-error` : undefined}
        {...rest}
      />
    </FieldShell>
  );
}

interface ToggleProps {
  label: string;
  description?: string;
  checked: boolean;
  onChange: (value: boolean) => void;
  disabled?: boolean;
}

export function Toggle({ label, description, checked, onChange, disabled }: ToggleProps) {
  return (
    <label className="toggle-row">
      <span className="toggle-text">
        <strong>{label}</strong>
        {description && <small>{description}</small>}
      </span>
      <input
        type="checkbox"
        role="switch"
        className="toggle"
        checked={checked}
        disabled={disabled}
        onChange={(e) => onChange(e.target.checked)}
      />
    </label>
  );
}