import * as React from "react";
import { Link, Navigate, useNavigate } from "react-router-dom";

import { useAuth } from "@/lib/auth";
import { errorMessage } from "@/api/client";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Field } from "@/components/ui/field";
import { LogoMark } from "@/components/Logo";

export function SignupPage() {
  const { session, ready, signup } = useAuth();
  const navigate = useNavigate();

  const [tenantName, setTenantName] = React.useState("");
  const [email, setEmail] = React.useState("");
  const [password, setPassword] = React.useState("");
  const [code, setCode] = React.useState("");
  const [error, setError] = React.useState<string | null>(null);
  const [busy, setBusy] = React.useState(false);

  if (ready && session) return <Navigate to="/" replace />;

  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      await signup({ tenantName: tenantName.trim(), email: email.trim(), password, code: code.trim() });
      navigate("/", { replace: true });
    } catch (err) {
      setError(errorMessage(err));
    } finally {
      setBusy(false);
    }
  };

  return (
    <main className="flex min-h-screen items-center justify-center bg-gcp-ground px-4">
      <form onSubmit={submit} className="gcp-card w-full max-w-[420px] px-10 py-9" noValidate>
        <LogoMark size={40} className="mb-4" />
        <h1 className="text-2xl font-normal text-gcp-text">Create your workspace</h1>
        <p className="mb-6 mt-1 text-sm text-gcp-text2">
          You will be the first administrator on it.
        </p>

        <div className="flex flex-col gap-4">
          <Field label="Company" htmlFor="tenantName" hint="Shown across the console. You can be the only person in it." required>
            <Input
              id="tenantName"
              value={tenantName}
              onChange={(e) => setTenantName(e.target.value)}
              maxLength={100}
              required
              autoFocus
            />
          </Field>
          <Field label="Email" htmlFor="email" required>
            <Input
              id="email"
              type="email"
              autoComplete="username"
              value={email}
              onChange={(e) => setEmail(e.target.value)}
              required
            />
          </Field>
          <Field label="Password" htmlFor="password" hint="At least 8 characters." required>
            <Input
              id="password"
              type="password"
              autoComplete="new-password"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              minLength={8}
              required
            />
          </Field>
          <Field label="Signup code" htmlFor="code" hint="Ask whoever runs this LetThemKnow for the code." required>
            <Input
              id="code"
              value={code}
              onChange={(e) => setCode(e.target.value)}
              autoComplete="off"
              required
            />
          </Field>
          {error && (
            <p role="alert" className="rounded bg-gcp-redBg px-3 py-2 text-sm text-gcp-red">{error}</p>
          )}
        </div>

        <div className="mt-8 flex items-center justify-between">
          <Link to="/login" className="text-sm text-gcp-blue hover:underline">
            Sign in instead
          </Link>
          <Button type="submit" loading={busy}>Create workspace</Button>
        </div>
      </form>
    </main>
  );
}
