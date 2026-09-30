import { useState } from 'react';
import type { FormEvent } from 'react';
import { Navigate, useNavigate } from 'react-router-dom';
import { Button } from '../../components/ui/Button';
import { Input } from '../../components/ui/Input';
import { authService } from '../../services/authService';
import { getErrorMessage, getErrorStatus } from '../../services/api';

interface FormErrors {
  email?: string;
  password?: string;
  form?: string;
}

const EMAIL_PATTERN = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;

export default function Login() {
  const navigate = useNavigate();
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [errors, setErrors] = useState<FormErrors>({});
  const [loading, setLoading] = useState(false);

  if (authService.isAuthenticated()) {
    return <Navigate to="/dashboard" replace />;
  }

  const validate = (): FormErrors => {
    const next: FormErrors = {};
    if (!email.trim()) next.email = 'Email is required.';
    else if (!EMAIL_PATTERN.test(email.trim())) next.email = 'Enter a valid email address.';
    if (!password) next.password = 'Password is required.';
    return next;
  };

  const handleSubmit = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    const validation = validate();
    setErrors(validation);
    if (Object.keys(validation).length > 0) return;

    setLoading(true);
    try {
      await authService.login({ email: email.trim(), password });
      navigate('/dashboard', { replace: true });
    } catch (err: unknown) {
      const status = getErrorStatus(err);
      setErrors({
        form:
          status === 401 || status === 403
            ? 'Invalid email or password.'
            : getErrorMessage(err),
      });
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="auth-page">
      <div className="auth-card">
        <div className="brand auth-brand">
          <span className="brand-mark">P</span>
          <span className="brand-name">Pitsch</span>
        </div>
        <h1>Welcome back</h1>
        <p className="muted">Sign in to continue to your workspace.</p>

        <form onSubmit={handleSubmit} noValidate className="form">
          <Input
            label="Email"
            type="email"
            autoComplete="email"
            placeholder="you@company.com"
            value={email}
            onChange={(e) => setEmail(e.target.value)}
            error={errors.email}
          />
          <Input
            label="Password"
            type="password"
            autoComplete="current-password"
            placeholder="••••••••"
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            error={errors.password}
          />
          {errors.form && (
            <p className="form-error" role="alert">
              {errors.form}
            </p>
          )}
          <Button type="submit" loading={loading} className="btn-block">
            {loading ? 'Signing in…' : 'Log in'}
          </Button>
        </form>
      </div>
    </div>
  );
}