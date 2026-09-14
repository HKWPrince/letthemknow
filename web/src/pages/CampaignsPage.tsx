import { Link, useNavigate, useSearchParams } from "react-router-dom";
import { Megaphone } from "lucide-react";

import { useCampaigns } from "@/api/campaigns";
import type { CampaignStatus } from "@/api/types";
import { CHANNEL_LABEL } from "@/api/types";
import { formatDateTime } from "@/lib/time";
import { cn } from "@/lib/utils";
import { Button } from "@/components/ui/button";
import { StatusChip, STATUS } from "@/components/ui/status-chip";
import { Card, EmptyState, PageHeader, Pager, Spinner } from "@/components/ui/page";

const FILTERS: (CampaignStatus | "")[] = ["", "DRAFT", "SCHEDULED", "PROCESSING", "AWAITING_RESOLUTION", "COMPLETED", "TERMINATED"];

export function CampaignsPage() {
  const [params, setParams] = useSearchParams();
  const navigate = useNavigate();
  const status = (params.get("status") ?? "") as CampaignStatus | "";
  const page = Number(params.get("page") ?? 0);
  const { data, isPending, isFetching } = useCampaigns(page, 25, status);

  const setFilter = (s: CampaignStatus | "") => {
    const next = new URLSearchParams();
    if (s) next.set("status", s);
    setParams(next);
  };
  const setPage = (p: number) => {
    const next = new URLSearchParams(params);
    next.set("page", String(p));
    setParams(next);
  };

  return (
    <>
      <PageHeader
        title="Campaigns"
        description="Bulk messages sent to an uploaded recipient list or a LINE audience group."
        actions={<Button asChild><Link to="/campaigns/new">Create campaign</Link></Button>}
      />

      <div className="mb-4 flex flex-wrap gap-2" role="tablist" aria-label="Filter by status">
        {FILTERS.map((f) => (
          <button
            key={f}
            role="tab"
            aria-selected={status === f}
            onClick={() => setFilter(f)}
            className={cn(
              "h-8 rounded-full border px-3 text-[13px] transition-colors",
              status === f ? "border-gcp-blue bg-gcp-blueBg text-gcp-blue" : "border-gcp-border bg-white text-gcp-text hover:bg-gcp-hover",
            )}
          >
            {f === "" ? "All" : STATUS[f]!.label}
          </button>
        ))}
      </div>

      <Card>
        {isPending ? (
          <Spinner />
        ) : !data || data.items.length === 0 ? (
          <EmptyState
            icon={<Megaphone className="h-10 w-10" />}
            title={status ? `No ${STATUS[status]!.label.toLowerCase()} campaigns` : "No campaigns yet"}
            description="Campaigns start as drafts. Add a template, upload recipients, then publish now or on a schedule."
            action={<Button asChild><Link to="/campaigns/new">Create campaign</Link></Button>}
          />
        ) : (
          <>
            <div className={cn("overflow-x-auto transition-opacity", isFetching && "opacity-60")}>
              <table className="gcp-table">
                <thead>
                  <tr>
                    <th>Campaign</th>
                    <th>Channel</th>
                    <th>Status</th>
                    <th className="text-right">Recipients</th>
                    <th className="w-[220px]">Progress</th>
                    <th>Scheduled / started</th>
                  </tr>
                </thead>
                <tbody>
                  {data.items.map((c) => {
                    const total = Math.max(c.totalCount, 1);
                    const sentPct = (c.successCount / total) * 100;
                    const failedPct = (c.failedCount / total) * 100;
                    return (
                      <tr key={c.id} data-clickable="true" onClick={() => navigate(`/campaigns/${c.id}`)}>
                        <td>
                          <span className="font-medium text-gcp-text">{c.title}</span>
                          <span className="ml-2 text-xs text-gcp-text3">#{c.id}</span>
                        </td>
                        <td>{CHANNEL_LABEL[c.channelType as "EMAIL" | "LINE"]}</td>
                        <td><StatusChip status={c.status} /></td>
                        <td className="text-right font-mono">{c.totalCount.toLocaleString()}</td>
                        <td>
                          {c.totalCount > 0 ? (
                            <div className="flex items-center gap-2">
                              <div className="h-1.5 flex-1 overflow-hidden rounded-full bg-gcp-hover" aria-hidden>
                                <div className="flex h-full">
                                  <div className="bg-gcp-green" style={{ width: `${sentPct}%` }} />
                                  <div className="bg-gcp-red" style={{ width: `${failedPct}%` }} />
                                </div>
                              </div>
                              <span className="w-[72px] text-right font-mono text-xs text-gcp-text2">
                                {c.successCount.toLocaleString()}{c.failedCount ? <span className="text-gcp-red"> / {c.failedCount}</span> : null}
                              </span>
                            </div>
                          ) : (
                            <span className="text-xs text-gcp-text3">—</span>
                          )}
                        </td>
                        <td className="text-gcp-text2">
                          {c.status === "SCHEDULED" ? formatDateTime(c.scheduledAt) : formatDateTime(c.startedAt)}
                        </td>
                      </tr>
                    );
                  })}
                </tbody>
              </table>
            </div>
            <Pager page={data.page} totalPages={data.totalPages} totalElements={data.totalElements} onPage={setPage} />
          </>
        )}
      </Card>
    </>
  );
}
