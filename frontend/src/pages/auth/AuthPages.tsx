import { useEffect, useState, type FormEvent, type ReactNode } from 'react';
import { Link, useNavigate, useParams, useSearchParams } from 'react-router-dom';
import { ApiError } from '../../lib/api/client';
import { auth as authApi, workspace as workspaceApi } from '../../lib/api/endpoints';
import type { InvitationLookup } from '../../lib/api/types';
import { useAuth } from '../../lib/auth/AuthContext';
import { ROLE_LABEL } from '../../lib/format';
import { safeAppPath, safeHttpUrl } from '../../lib/safeUrl';
import { Button, InlineError, Spinner, TextInput } from '../../components/ui';

const GOOGLE_LOGIN = import.meta.env.VITE_GOOGLE_LOGIN === 'true';
const DEMO = import.meta.env.VITE_DEMO_MODE === 'true';

const OAUTH_ERRORS: Record<string, string> = {
  unverified: 'Your Google account email is not verified.',
  cancelled: 'Google sign-in was cancelled.',
  denied: 'Google sign-in was denied.',
  expired: 'The sign-in link expired. Please try again.',
  oauth_failed: 'Google sign-in failed. Please try again.',
};

function AuthLayout({ title, subtitle, children, footer }: { title: string; subtitle?: ReactNode; children: ReactNode; footer?: ReactNode }) {
  return (
    <div className="auth-page">
      <div className="auth-card">
        <div className="brand brand-lg"><span className="brand-mark">P</span><span className="brand-name">Pitsch</span></div>
        <h1>{title}</h1>
        {subtitle && <p className="muted">{subtitle}</p>}
        {children}
        {footer && <div className="auth-footer">{footer}</div>}
      </div>
      <p className="auth-tagline muted small">AI research for investors. Every action needs your approval.</p>
    </div>
  );
}

function passwordProblem(pw: string): string | null {
  if (pw.length < 10) return 'Use at least 10 characters.';
  if (/^[A-Za-z]+$/.test(pw) || /^\d+$/.test(pw)) return 'Mix letters with numbers or symbols.';
  return null;
}

function GoogleButton({ returnTo }: { returnTo: string }) {
  const [error, setError] = useState<unknown>(null);
  const [busy, setBusy] = useState(false);
  if (!GOOGLE_LOGIN) return null;
  const go = async () => {
    setBusy(true);
    try {
      const { authorizationUrl } = await authApi.googleStart(returnTo);
      const safe = safeHttpUrl(authorizationUrl);
      if (safe && new URL(safe).hostname === 'accounts.google.com') window.location.assign(safe);
      else throw new Error('Unexpected sign-in address.');
    } catch (e) {
      setError(e);
      setBusy(false);
    }
  };
  return (
    <>
      <div className="divider"><span>or</span></div>
      <Button className="w-full" onClick={go} loading={busy}>Continue with Google</Button>
      <InlineError error={error} />
    </>
  );
}

export function LoginPage() {
  const { login } = useAuth();
  const navigate = useNavigate();
  const [params] = useSearchParams();
  const returnTo = safeAppPath(params.get('returnTo'));
  const [email, setEmail] = useState(DEMO ? 'demo@pitsch.app' : '');
  const [password, setPassword] = useState('');
  const [error, setError] = useState<unknown>(params.get('error') ? new Error(OAUTH_ERRORS[params.get('error')!] ?? 'Sign-in failed.') : null);
  const [busy, setBusy] = useState(false);

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      const me = await login(email.trim(), password);
      navigate(me.workspace ? returnTo : '/onboarding', { replace: true });
    } catch (err) {
      setError(err);
    } finally {
      setBusy(false);
    }
  };

  return (
    <AuthLayout title="Sign in" subtitle="Welcome back."
      footer={<>New to Pitsch? <Link to="/signup">Create an account</Link></>}>
      {DEMO && <div className="banner banner-demo small">Demo: sign in as <strong>demo@pitsch.app</strong> with the password from your deployment (default <code>pitsch-demo-2026</code>).</div>}
      <form className="stack" onSubmit={submit}>
        <TextInput label="Email" type="email" autoComplete="email" required value={email} onChange={(e) => setEmail(e.target.value)} />
        <TextInput label="Password" type="password" autoComplete="current-password" required value={password} onChange={(e) => setPassword(e.target.value)} />
        <div className="row space-between small"><span /><Link to="/forgot-password">Forgot password?</Link></div>
        <InlineError error={error} />
        <Button variant="primary" type="submit" className="w-full" loading={busy}>Sign in</Button>
      </form>
      <GoogleButton returnTo={returnTo} />
    </AuthLayout>
  );
}

export function SignupPage() {
  const { signup } = useAuth();
  const navigate = useNavigate();
  const [params] = useSearchParams();
  const inviteToken = params.get('invite') ?? undefined;
  const [name, setName] = useState('');
  const [email, setEmail] = useState(params.get('email') ?? '');
  const [password, setPassword] = useState('');
  const [workspaceName, setWorkspaceName] = useState('');
  const [error, setError] = useState<unknown>(null);
  const [busy, setBusy] = useState(false);
  const pwProblem = password ? passwordProblem(password) : null;

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    if (passwordProblem(password)) return;
    setBusy(true);
    setError(null);
    try {
      const timezone = Intl.DateTimeFormat().resolvedOptions().timeZone;
      await signup({ name: name.trim(), email: email.trim(), password, workspaceName: workspaceName.trim() || undefined, timezone, inviteToken });
      navigate(inviteToken ? '/dashboard' : '/onboarding', { replace: true });
    } catch (err) {
      setError(err);
    } finally {
      setBusy(false);
    }
  };

  const fieldErrors = error instanceof ApiError ? error.fieldErrors : {};
  return (
    <AuthLayout title="Create your account" subtitle={inviteToken ? 'You were invited to join a workspace.' : 'Start a workspace for your fund.'}
      footer={<>Already have an account? <Link to="/login">Sign in</Link></>}>
      <form className="stack" onSubmit={submit}>
        <TextInput label="Full name" autoComplete="name" required value={name} onChange={(e) => setName(e.target.value)} error={fieldErrors.name} />
        <TextInput label="Work email" type="email" autoComplete="email" required value={email} onChange={(e) => setEmail(e.target.value)} error={fieldErrors.email} />
        <TextInput label="Password" type="password" autoComplete="new-password" required value={password}
          onChange={(e) => setPassword(e.target.value)} error={pwProblem ?? fieldErrors.password}
          hint="At least 10 characters, mixing letters with numbers or symbols." />
        {!inviteToken && (
          <TextInput label="Workspace name" value={workspaceName} onChange={(e) => setWorkspaceName(e.target.value)} placeholder="e.g. Northwind Ventures" />
        )}
        <InlineError error={error} />
        <Button variant="primary" type="submit" className="w-full" loading={busy} disabled={!!pwProblem}>Create account</Button>
        <p className="muted small">By creating an account you agree that pitch content you import is processed by AI models to build research briefs. See our privacy notice.</p>
      </form>
      <GoogleButton returnTo="/onboarding" />
    </AuthLayout>
  );
}

export function ForgotPasswordPage() {
  const [email, setEmail] = useState('');
  const [sent, setSent] = useState<string | null>(null);
  const [error, setError] = useState<unknown>(null);
  const [busy, setBusy] = useState(false);
  const submit = async (e: FormEvent) => {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      const res = await authApi.forgotPassword(email.trim());
      setSent(res.message);
    } catch (err) {
      setError(err);
    } finally {
      setBusy(false);
    }
  };
  return (
    <AuthLayout title="Reset your password" footer={<Link to="/login">Back to sign in</Link>}>
      {sent ? <p className="success-text">{sent}</p> : (
        <form className="stack" onSubmit={submit}>
          <TextInput label="Email" type="email" required value={email} onChange={(e) => setEmail(e.target.value)} />
          <InlineError error={error} />
          <Button variant="primary" type="submit" className="w-full" loading={busy}>Send reset link</Button>
        </form>
      )}
    </AuthLayout>
  );
}

export function ResetPasswordPage() {
  const [params] = useSearchParams();
  const navigate = useNavigate();
  const token = params.get('token') ?? '';
  const [password, setPassword] = useState('');
  const [error, setError] = useState<unknown>(token ? null : new Error('This link is invalid.'));
  const [busy, setBusy] = useState(false);
  const problem = password ? passwordProblem(password) : null;
  const submit = async (e: FormEvent) => {
    e.preventDefault();
    if (passwordProblem(password)) return;
    setBusy(true);
    setError(null);
    try {
      await authApi.resetPassword(token, password);
      navigate('/login', { replace: true });
    } catch (err) {
      setError(err);
    } finally {
      setBusy(false);
    }
  };
  return (
    <AuthLayout title="Choose a new password" subtitle="All your other sessions will be signed out." footer={<Link to="/login">Back to sign in</Link>}>
      <form className="stack" onSubmit={submit}>
        <TextInput label="New password" type="password" autoComplete="new-password" required value={password}
          onChange={(e) => setPassword(e.target.value)} error={problem} />
        <InlineError error={error} />
        <Button variant="primary" type="submit" className="w-full" loading={busy} disabled={!token || !!problem}>Set password</Button>
      </form>
    </AuthLayout>
  );
}

export function VerifyEmailPage() {
  const [params] = useSearchParams();
  const { status, refresh } = useAuth();
  const token = params.get('token') ?? '';
  const [state, setState] = useState<'working' | 'done' | 'error'>(token ? 'working' : 'error');
  const [error, setError] = useState<unknown>(token ? null : new Error('This link is invalid.'));
  useEffect(() => {
    if (!token) return;
    let cancelled = false;
    authApi.verifyEmail(token)
      .then(() => {
        if (!cancelled) setState('done');
        if (status === 'authenticated') void refresh();
      })
      .catch((e: unknown) => {
        if (!cancelled) { setError(e); setState('error'); }
      });
    return () => { cancelled = true; };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [token]);
  return (
    <AuthLayout title="Email verification" footer={<Link to={status === 'authenticated' ? '/dashboard' : '/login'}>Continue</Link>}>
      {state === 'working' && <Spinner />}
      {state === 'done' && <p className="success-text">Your email address is verified.</p>}
      {state === 'error' && <InlineError error={error} />}
    </AuthLayout>
  );
}

export function AcceptInvitePage() {
  const { token = '' } = useParams();
  const { status, me, refresh } = useAuth();
  const navigate = useNavigate();
  const [invite, setInvite] = useState<InvitationLookup | null>(null);
  const [error, setError] = useState<unknown>(null);
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    workspaceApi.lookupInvitation(token).then(setInvite).catch(setError);
  }, [token]);

  const accept = async () => {
    setBusy(true);
    try {
      await workspaceApi.acceptInvitation(token);
      await refresh();
      navigate('/dashboard', { replace: true });
    } catch (e) {
      setError(e);
    } finally {
      setBusy(false);
    }
  };

  return (
    <AuthLayout title="Workspace invitation">
      {!invite && !error && <Spinner />}
      <InlineError error={error} />
      {invite && (
        <div className="stack">
          <p>You were invited to join <strong>{invite.workspaceName}</strong> as <strong>{ROLE_LABEL[invite.role]}</strong> ({invite.email}).</p>
          {status === 'authenticated' && me ? (
            me.user.email.toLowerCase() === invite.email.toLowerCase()
              ? <Button variant="primary" onClick={accept} loading={busy}>Join workspace</Button>
              : <p className="muted">You are signed in as {me.user.email}. Sign in with {invite.email} to accept.</p>
          ) : (
            <div className="row gap-sm">
              <Link className="btn btn-primary" to={`/signup?invite=${encodeURIComponent(token)}&email=${encodeURIComponent(invite.email)}`}>Create account</Link>
              <Link className="btn btn-secondary" to={`/login?returnTo=${encodeURIComponent(`/invite/${token}`)}`}>Sign in</Link>
            </div>
          )}
        </div>
      )}
    </AuthLayout>
  );
}

/** Google sign-in lands here; the backend already set the refresh cookie. */
export function AuthCallbackPage() {
  const { adoptSession } = useAuth();
  const navigate = useNavigate();
  const [failed, setFailed] = useState(false);
  useEffect(() => {
    adoptSession().then((me) => {
      if (!me) setFailed(true);
      else navigate(me.workspace ? '/dashboard' : '/onboarding', { replace: true });
    });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);
  return (
    <AuthLayout title="Signing you in…">
      {failed ? <p>Sign-in could not be completed. <Link to="/login">Try again</Link></p> : <Spinner />}
    </AuthLayout>
  );
}
