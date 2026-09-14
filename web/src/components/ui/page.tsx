import * as React from "react";
import { Link } from "react-router-dom";
import { ChevronRight, Loader2 } from "lucide-react";

import { cn } from "@/lib/utils";

interface Crumb {
  label: string;
  to?: string;
}

interface PageHeaderProps {
  title: React.ReactNode;
  crumbs?: Crumb[];
  description?: React.ReactNode;
  actions?: React.ReactNode;
  meta?: React.ReactNode;
}

/** Console page header: breadcrumb, 22px display title, actions right-aligned on the same baseline. */
export function PageHeader({ title, crumbs, description, actions, meta }: PageHeaderProps) {
  return (
    <div className="mb-6">
      {crumbs && crumbs.length > 0 && (
        <nav className="mb-2 flex items-center gap-1 text-xs text-gcp-text2" aria-label="Breadcrumb">
          {crumbs.map((c, i) => (
            <React.Fragment key={i}>
              {i > 0 && <ChevronRight className="h-3 w-3 text-gcp-text3" aria-hidden />}
              {c.to ? <Link to={c.to} className="gcp-link">{c.label}</Link> : <span>{c.label}</span>}
            </React.Fragment>
          ))}
        </nav>
      )}
      <div className="flex flex-wrap items-start justify-between gap-4">
        <div className="min-w-0">
          <h1 className="flex items-center gap-3 text-[22px] font-normal leading-8 text-gcp-text">{title}{meta}</h1>
          {description && <p className="mt-1 text-sm text-gcp-text2">{description}</p>}
        </div>
        {actions && <div className="flex flex-wrap items-center gap-2">{actions}</div>}
      </div>
    </div>
  );
}

export function Card({ className, children, ...props }: React.HTMLAttributes<HTMLDivElement>) {
  return (
    <div className={cn("gcp-card", className)} {...props}>
      {children}
    </div>
  );
}

export function CardHeader({ title, description, actions }: { title: React.ReactNode; description?: React.ReactNode; actions?: React.ReactNode }) {
  return (
    <div className="flex items-start justify-between gap-4 border-b border-gcp-border px-6 py-4">
      <div>
        <h2 className="text-base font-medium text-gcp-text">{title}</h2>
        {description && <p className="mt-0.5 text-sm text-gcp-text2">{description}</p>}
      </div>
      {actions}
    </div>
  );
}

export function CardBody({ className, children }: { className?: string; children: React.ReactNode }) {
  return <div className={cn("px-6 py-5", className)}>{children}</div>;
}

export function Spinner({ className, label = "Loading" }: { className?: string; label?: string }) {
  return (
    <div className={cn("flex items-center justify-center gap-2 py-12 text-sm text-gcp-text2", className)} role="status">
      <Loader2 className="h-5 w-5 animate-spin text-gcp-blue" aria-hidden />
      {label}
    </div>
  );
}

interface EmptyStateProps {
  icon?: React.ReactNode;
  title: string;
  description?: string;
  action?: React.ReactNode;
}

export function EmptyState({ icon, title, description, action }: EmptyStateProps) {
  return (
    <div className="flex flex-col items-center justify-center px-6 py-16 text-center">
      {icon && <div className="mb-4 text-gcp-text3">{icon}</div>}
      <h3 className="text-base font-medium text-gcp-text">{title}</h3>
      {description && <p className="mt-1 max-w-md text-sm text-gcp-text2">{description}</p>}
      {action && <div className="mt-5">{action}</div>}
    </div>
  );
}

/** Key/value rows for detail views. */
export function DescriptionList({ items }: { items: { label: string; value: React.ReactNode }[] }) {
  return (
    <dl className="grid grid-cols-[minmax(120px,max-content)_1fr] gap-x-6 gap-y-3 text-sm">
      {items.map((it) => (
        <React.Fragment key={it.label}>
          <dt className="text-gcp-text2">{it.label}</dt>
          <dd className="min-w-0 break-words text-gcp-text">{it.value ?? <span className="text-gcp-text3">—</span>}</dd>
        </React.Fragment>
      ))}
    </dl>
  );
}

interface PagerProps {
  page: number;
  totalPages: number;
  totalElements: number;
  onPage: (p: number) => void;
}

export function Pager({ page, totalPages, totalElements, onPage }: PagerProps) {
  if (totalPages <= 1) return null;
  return (
    <div className="flex items-center justify-between border-t border-gcp-border px-4 py-2 text-xs text-gcp-text2">
      <span>{totalElements.toLocaleString()} rows</span>
      <div className="flex items-center gap-1">
        <button className="rounded px-2 py-1 hover:bg-gcp-hover disabled:opacity-40" disabled={page === 0} onClick={() => onPage(page - 1)}>
          Previous
        </button>
        <span className="px-2">Page {page + 1} of {totalPages}</span>
        <button className="rounded px-2 py-1 hover:bg-gcp-hover disabled:opacity-40" disabled={page >= totalPages - 1} onClick={() => onPage(page + 1)}>
          Next
        </button>
      </div>
    </div>
  );
}
