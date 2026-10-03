import { Button } from './Button';
import { Icon } from './Icon';

interface ErrorStateProps {
  message: string;
  onRetry?: () => void;
}

export function ErrorState({ message, onRetry }: ErrorStateProps) {
  return (
    <div className="state-box" role="alert">
      <span className="state-icon state-icon-error">
        <Icon name="alert" size={22} />
      </span>
      <h3>Something went wrong</h3>
      <p className="muted">{message}</p>
      {onRetry && (
        <Button variant="secondary" size="sm" icon="refresh" onClick={onRetry}>
          Try again
        </Button>
      )}
    </div>
  );
}