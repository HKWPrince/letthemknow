import * as React from "react";
import { Navigate, useLocation } from "react-router-dom";

import { api, configureApiAuth } from "@/api/client";
import type { LoginResponse, MeResponse, TenantDto, UserDto } from "@/api/types";

const STORAGE_KEY = "ltk.session";

interface Session {
  token: string;
  expiresAt: string;
  user: UserDto;
  tenant: TenantDto;
}

interface SignupInput {
  tenantName: string;
  email: string;
  password: string;
  code: string;
}

interface AuthContextValue {
  session: Session | null;
  ready: boolean;
  login: (email: string, password: string, tenant?: string) => Promise<void>;
  signup: (input: SignupInput) => Promise<void>;
  logout: () => void;
}

const AuthContext = React.createContext<AuthContextValue | null>(null);

function readStored(): Session | null {
  try {
    const raw = localStorage.getItem(STORAGE_KEY);
    if (!raw) return null;
    const s = JSON.parse(raw) as Session;
    if (!s.token || new Date(s.expiresAt).getTime() <= Date.now()) return null;
    return s;
  } catch {
    return null;
  }
}

export function AuthProvider({ children }: { children: React.ReactNode }) {
  const [session, setSession] = React.useState<Session | null>(() => readStored());
  const [ready, setReady] = React.useState(false);
  const sessionRef = React.useRef(session);
  sessionRef.current = session;

  const logout = React.useCallback(() => {
    try {
      localStorage.removeItem(STORAGE_KEY);
    } catch {
      /* storage unavailable */
    }
    setSession(null);
  }, []);

  // token lives in memory; localStorage only restores it across reloads
  React.useEffect(() => {
    configureApiAuth({ getToken: () => sessionRef.current?.token ?? null, onUnauthorized: logout });
  }, [logout]);

  // validate a restored token once, so a revoked/expired session is dropped early
  React.useEffect(() => {
    let cancelled = false;
    if (!session) {
      setReady(true);
      return;
    }
    api.get<MeResponse>("/auth/me")
      .then((me) => {
        if (!cancelled) setSession((s) => (s ? { ...s, user: me.user, tenant: me.tenant } : s));
      })
      .catch(() => {
        if (!cancelled) logout();
      })
      .finally(() => {
        if (!cancelled) setReady(true);
      });
    return () => {
      cancelled = true;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // Both entry points end in a session, so they share one place that stores it.
  const adopt = React.useCallback((res: LoginResponse) => {
    const next: Session = { token: res.token, expiresAt: res.expiresAt, user: res.user, tenant: res.tenant };
    try {
      localStorage.setItem(STORAGE_KEY, JSON.stringify(next));
    } catch {
      /* storage unavailable */
    }
    setSession(next);
  }, []);

  const login = React.useCallback(async (email: string, password: string, tenant?: string) => {
    adopt(await api.post<LoginResponse>("/auth/login", { email, password, tenant: tenant || undefined }));
  }, [adopt]);

  // Signup returns a session too, so a new admin lands signed in with no second round-trip.
  const signup = React.useCallback(async (input: SignupInput) => {
    adopt(await api.post<LoginResponse>("/auth/signup", input));
  }, [adopt]);

  const value = React.useMemo(
    () => ({ session, ready, login, signup, logout }),
    [session, ready, login, signup, logout],
  );
  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthContextValue {
  const ctx = React.useContext(AuthContext);
  if (!ctx) throw new Error("useAuth must be used within AuthProvider");
  return ctx;
}

export function RequireAuth({ children }: { children: React.ReactNode }) {
  const { session, ready } = useAuth();
  const location = useLocation();
  if (!ready) return null;
  if (!session) return <Navigate to="/login" replace state={{ from: location.pathname }} />;
  return <>{children}</>;
}
