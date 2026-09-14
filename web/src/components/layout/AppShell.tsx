import * as React from "react";
import { NavLink, Outlet, useNavigate } from "react-router-dom";
import { ChevronDown, Code2, FileText, KeyRound, LayoutDashboard, LogOut, Megaphone, Menu, Radio } from "lucide-react";

import { useAuth } from "@/lib/auth";
import { cn } from "@/lib/utils";
import { Button } from "@/components/ui/button";
import { Wordmark } from "@/components/Logo";

const NAV = [
  { to: "/", label: "Dashboard", icon: LayoutDashboard, end: true },
  { to: "/campaigns", label: "Campaigns", icon: Megaphone },
  { to: "/templates", label: "Templates", icon: FileText },
  { to: "/channels", label: "Channels", icon: Radio },
  { to: "/api-keys", label: "API keys", icon: KeyRound },
  { to: "/developers", label: "Developers", icon: Code2 },
];

export function AppShell() {
  const { session, logout } = useAuth();
  const navigate = useNavigate();
  const [collapsed, setCollapsed] = React.useState(() => {
    try {
      return localStorage.getItem("ltk.nav") === "collapsed";
    } catch {
      return false;
    }
  });

  const toggleNav = () => {
    setCollapsed((c) => {
      try {
        localStorage.setItem("ltk.nav", c ? "expanded" : "collapsed");
      } catch {
        /* ignore */
      }
      return !c;
    });
  };

  return (
    <div className="flex min-h-screen flex-col bg-gcp-ground">
      <header className="sticky top-0 z-30 flex h-12 items-center gap-3 border-b border-gcp-border bg-white px-2">
        <Button variant="ghost" size="icon" onClick={toggleNav} aria-label={collapsed ? "Expand navigation" : "Collapse navigation"}>
          <Menu className="h-5 w-5" />
        </Button>
        <Wordmark />
        <div className="mx-2 h-6 w-px bg-gcp-border" aria-hidden />
        {/* tenant selector, styled like the console's project picker; single-tenant in v1 */}
        <button
          type="button"
          className="flex h-8 items-center gap-2 rounded px-2 text-sm text-gcp-text hover:bg-gcp-hover"
          title="Signed-in tenant"
          disabled
        >
          <span className="grid h-5 w-5 place-items-center rounded-sm bg-gcp-blueBg text-[11px] font-medium uppercase text-gcp-blue">
            {session?.tenant.name.slice(0, 1)}
          </span>
          <span className="font-medium">{session?.tenant.name}</span>
          <ChevronDown className="h-4 w-4 text-gcp-text2" aria-hidden />
        </button>
        <div className="flex-1" />
        <span className="hidden text-xs text-gcp-text2 sm:block">{session?.user.email}</span>
        <Button variant="ghost" size="sm" onClick={() => { logout(); navigate("/login"); }}>
          <LogOut className="h-4 w-4" aria-hidden />
          Sign out
        </Button>
      </header>

      <div className="flex flex-1">
        <nav
          className={cn(
            "sticky top-12 hidden h-[calc(100vh-48px)] shrink-0 flex-col border-r border-gcp-border bg-white py-2 transition-[width] duration-200 md:flex",
            collapsed ? "w-[68px]" : "w-64",
          )}
          aria-label="Primary"
        >
          {NAV.map(({ to, label, icon: Icon, end }) => (
            <NavLink
              key={to}
              to={to}
              end={end}
              title={collapsed ? label : undefined}
              className={({ isActive }) =>
                cn(
                  "mx-2 my-0.5 flex h-9 items-center gap-3 rounded-r-full pl-4 pr-3 text-sm",
                  isActive ? "bg-gcp-selected font-medium text-gcp-blue" : "text-gcp-text hover:bg-gcp-hover",
                  collapsed && "justify-center px-0",
                )
              }
            >
              <Icon className="h-5 w-5 shrink-0" aria-hidden />
              {!collapsed && <span>{label}</span>}
            </NavLink>
          ))}
        </nav>

        <main className="min-w-0 flex-1">
          <div className="mx-auto max-w-[1400px] px-6 py-6">
            <Outlet />
          </div>
        </main>
      </div>

      {/* mobile nav */}
      <nav className="sticky bottom-0 z-30 flex border-t border-gcp-border bg-white md:hidden" aria-label="Primary">
        {NAV.map(({ to, label, icon: Icon, end }) => (
          <NavLink
            key={to}
            to={to}
            end={end}
            className={({ isActive }) =>
              cn("flex flex-1 flex-col items-center gap-1 py-2 text-[11px]", isActive ? "text-gcp-blue" : "text-gcp-text2")
            }
          >
            <Icon className="h-5 w-5" aria-hidden />
            {label}
          </NavLink>
        ))}
      </nav>
    </div>
  );
}
