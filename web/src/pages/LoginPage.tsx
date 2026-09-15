import * as React from "react";
import { Link, Navigate, useLocation, useNavigate } from "react-router-dom";

import { useAuth } from "@/lib/auth";
import { errorMessage } from "@/api/client";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Field } from "@/components/ui/field";
import { LogoMark } from "@/components/Logo";

export function LoginPage() {
  const { session, ready, login } = useAuth();
  const navigate = useNavigate();
  const location = useLocation();
  const from = (location.state as { from?: string } | null)?.from ?? "/";

  const [email, setEmail] = React.useState("");
  const [password, setPassword] = React.useState("");
  const [tenant, setTenant] = React.useState("");
  const [showTenant, setShowTenant] = React.useState(false);
  const [error, setError] = React.useState<string | null>(null);
  const [busy, setBusy] = React.useState(false);

  if (ready && session) return <Navigate to={from} replace />;

  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      await login(email.trim(), password, tenant.trim() || undefined);
      navigate(from, { replace: true });
    } catch (err) {
      const msg = errorMessage(err);
      setError(msg);
      if (/several tenants/i.test(msg)) setShowTenant(true);
    } finally {
      setBusy(false);
    }
  };

  return (
    <main className="flex min-h-screen flex-col items-center justify-center gap-4 bg-gcp-ground px-4">
      <form onSubmit={submit} className="gcp-card w-full max-w-[420px] px-10 py-9" noValidate>
        <LogoMark size={40} className="mb-4" />
        <h1 className="text-2xl font-normal text-gcp-text">Sign in</h1>
        <p className="mb-6 mt-1 text-sm text-gcp-text2">to continue to LetThemKnow</p>

        <div className="flex flex-col gap-4">
          <Field label="Email" htmlFor="email" required>
            <Input id="email" type="email" autoComplete="username" value={email} onChange={(e) => setEmail(e.target.value)} required autoFocus />
          </Field>
          <Field label="Password" htmlFor="password" required>
            <Input id="password" type="password" autoComplete="current-password" value={password} onChange={(e) => setPassword(e.target.value)} required />
          </Field>
          {showTenant ? (
            <Field label="Tenant" htmlFor="tenant" hint="Your email belongs to more than one tenant. Enter the tenant name.">
              <Input id="tenant" value={tenant} onChange={(e) => setTenant(e.target.value)} />
            </Field>
          ) : (
            <button type="button" className="self-start text-xs text-gcp-blue hover:underline" onClick={() => setShowTenant(true)}>
              Sign in to a specific tenant
            </button>
          )}
          {error && (
            <p role="alert" className="rounded bg-gcp-redBg px-3 py-2 text-sm text-gcp-red">{error}</p>
          )}
        </div>

        <div className="mt-8 flex items-center justify-between">
          <Link to="/signup" className="text-sm text-gcp-blue hover:underline">
            Create a workspace
          </Link>
          <Button type="submit" loading={busy}>Sign in</Button>
        </div>
      </form>

      {/*
        Sits outside the card and below it, so someone evaluating the product can find the spec
        without it competing with sign-in. A plain anchor, not a router Link: PRD.html is a static
        file served by nginx, and react-router would try to resolve it as an app route.
      */}
      <p className="text-xs text-gcp-text3">
        <a
          href="/PRD.html"
          target="_blank"
          rel="noopener noreferrer"
          className="rounded-sm text-gcp-text2 underline-offset-2 hover:text-gcp-blue hover:underline focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-gcp-blue"
        >
          產品說明文件
        </a>
      </p>
    </main>
  );
}
