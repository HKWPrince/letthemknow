import { Link } from "react-router-dom";
import { ArrowRight, Megaphone } from "lucide-react";

import { useCampaigns } from "@/api/campaigns";
import type { CampaignDto, CampaignStatus } from "@/api/types";
import { CHANNEL_LABEL } from "@/api/types";
import { formatRelative } from "@/lib/time";
import { Button } from "@/components/ui/button";
import { StatusChip } from "@/components/ui/status-chip";
import { Card, CardHeader, EmptyState, PageHeader, Spinner } from "@/components/ui/page";
import { useAuth } from "@/lib/auth";

function Stat({ label, value, tone, to }: { label: string; value: number; tone: string; to: string }) {
  return (
    <Link to={to} className="gcp-card flex flex-col gap-1 px-5 py-4 hover:bg-gcp-hover">
      <span className="text-xs text-gcp-text2">{label}</span>
      <span className={`font-display text-[28px] leading-8 ${tone}`}>{value.toLocaleString()}</span>
    </Link>
  );
}

export function DashboardPage() {
  const { session } = useAuth();
  const { data, isPending } = useCampaigns(0, 100);
  const items = data?.items ?? [];
  const count = (...statuses: CampaignStatus[]) => items.filter((c) => statuses.includes(c.status as CampaignStatus)).length;
  const sentTotal = items.reduce((n, c) => n + c.successCount, 0);

  return (
    <>
      <PageHeader
        title="Dashboard"
        description={`${session?.tenant.name} · messages you have sent and campaigns in flight`}
        actions={
          <Button asChild>
            <Link to="/campaigns/new">Create campaign</Link>
          </Button>
        }
      />

      <div className="mb-6 grid grid-cols-2 gap-4 lg:grid-cols-4">
        <Stat label="Sending now" value={count("PROCESSING", "RETRYING")} tone="text-gcp-blue" to="/campaigns?status=PROCESSING" />
        <Stat label="Needs attention" value={count("AWAITING_RESOLUTION")} tone="text-gcp-yellowText" to="/campaigns?status=AWAITING_RESOLUTION" />
        <Stat label="Scheduled" value={count("SCHEDULED")} tone="text-gcp-purple" to="/campaigns?status=SCHEDULED" />
        <Stat label="Messages delivered" value={sentTotal} tone="text-gcp-green" to="/campaigns?status=COMPLETED" />
      </div>

      <Card>
        <CardHeader
          title="Recent campaigns"
          actions={
            <Button variant="text" size="sm" asChild>
              <Link to="/campaigns">View all <ArrowRight className="h-4 w-4" aria-hidden /></Link>
            </Button>
          }
        />
        {isPending ? (
          <Spinner />
        ) : items.length === 0 ? (
          <EmptyState
            icon={<Megaphone className="h-10 w-10" />}
            title="No campaigns yet"
            description="Create a campaign to send an email or LINE message to a list of recipients."
            action={<Button asChild><Link to="/campaigns/new">Create campaign</Link></Button>}
          />
        ) : (
          <div className="overflow-x-auto">
            <table className="gcp-table">
              <thead>
                <tr>
                  <th>Campaign</th>
                  <th>Channel</th>
                  <th>Status</th>
                  <th className="text-right">Sent</th>
                  <th className="text-right">Failed</th>
                  <th>Updated</th>
                </tr>
              </thead>
              <tbody>
                {items.slice(0, 10).map((c: CampaignDto) => (
                  <tr key={c.id}>
                    <td><Link to={`/campaigns/${c.id}`} className="gcp-link font-medium">{c.title}</Link></td>
                    <td>{CHANNEL_LABEL[c.channelType as "EMAIL" | "LINE"]}</td>
                    <td><StatusChip status={c.status} /></td>
                    <td className="text-right font-mono">{c.successCount.toLocaleString()}</td>
                    <td className={`text-right font-mono ${c.failedCount ? "text-gcp-red" : ""}`}>{c.failedCount.toLocaleString()}</td>
                    <td className="text-gcp-text2">{formatRelative(c.updatedAt)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </Card>
    </>
  );
}
