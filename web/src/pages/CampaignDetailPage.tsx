import * as React from "react";
import { useNavigate, useParams } from "react-router-dom";
import { AlertTriangle, RefreshCw } from "lucide-react";
import { toast } from "sonner";

import {
  useAbortCampaign, useCampaign, useCampaignStats, useDeleteCampaign, useFailures, usePublishCampaign,
  useRecipients, useRetryFailed, useUploadRecipients,
} from "@/api/campaigns";
import { useTemplate } from "@/api/templates";
import { errorMessage } from "@/api/client";
import type { CampaignStatsDto, CampaignStatus, RecipientStatus } from "@/api/types";
import { AUDIENCE_LABEL, CHANNEL_LABEL } from "@/api/types";
import { formatDateTime, nextHourTaipeiLocal, taipeiLocalToIso } from "@/lib/time";
import { cn } from "@/lib/utils";
import { Button } from "@/components/ui/button";
import { Input, Select } from "@/components/ui/input";
import { Field } from "@/components/ui/field";
import { StatusChip, STATUS } from "@/components/ui/status-chip";
import { ConfirmDialog, Dialog, DialogClose, DialogContent, Sheet } from "@/components/ui/dialog";
import { Card, CardBody, CardHeader, DescriptionList, EmptyState, PageHeader, Pager, Spinner } from "@/components/ui/page";
import { RecipientUpload } from "@/components/RecipientUpload";

function ProgressBar({ stats }: { stats: CampaignStatsDto }) {
  const r = stats.recipients;
  const total = Math.max(r.pending + r.sending + r.sent + r.failed + r.cancelled, stats.totalCount, 1);
  const pct = (n: number) => `${(n / total) * 100}%`;
  const cells: [string, number, string][] = [
    ["Sent", r.sent, "text-gcp-green"],
    ["Failed", r.failed, "text-gcp-red"],
    ["Pending", r.pending + r.sending, "text-gcp-blue"],
    ["Cancelled", r.cancelled, "text-gcp-text2"],
  ];
  return (
    <div>
      <div className="mb-4 flex h-2 overflow-hidden rounded-full bg-gcp-hover" aria-hidden>
        <div className="bg-gcp-green transition-[width]" style={{ width: pct(r.sent) }} />
        <div className="bg-gcp-red transition-[width]" style={{ width: pct(r.failed) }} />
        <div className="bg-gcp-blue/40 transition-[width]" style={{ width: pct(r.pending + r.sending) }} />
      </div>
      <dl className="grid grid-cols-2 gap-4 sm:grid-cols-5">
        <div>
          <dt className="text-xs text-gcp-text2">Total</dt>
          <dd className="font-display text-2xl text-gcp-text">{stats.totalCount.toLocaleString()}</dd>
        </div>
        {cells.map(([label, n, tone]) => (
          <div key={label}>
            <dt className="text-xs text-gcp-text2">{label}</dt>
            <dd className={cn("font-display text-2xl", tone)}>{n.toLocaleString()}</dd>
          </div>
        ))}
      </dl>
    </div>
  );
}

function PublishDialog({ open, onOpenChange, onPublish, loading }: { open: boolean; onOpenChange: (o: boolean) => void; onPublish: (at: string | null) => void; loading: boolean }) {
  const [mode, setMode] = React.useState<"now" | "later">("now");
  const [local, setLocal] = React.useState(nextHourTaipeiLocal());
  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent title="Publish campaign" description="Sending starts immediately, or at the time you choose (Asia/Taipei).">
        <div className="flex flex-col gap-3">
          <label className="flex items-center gap-3 text-sm"><input type="radio" className="accent-gcp-blue" checked={mode === "now"} onChange={() => setMode("now")} />Send now</label>
          <label className="flex items-center gap-3 text-sm"><input type="radio" className="accent-gcp-blue" checked={mode === "later"} onChange={() => setMode("later")} />Schedule for later</label>
          {mode === "later" && (
            <Field label="Send at (Asia/Taipei)" htmlFor="pub-at">
              <Input id="pub-at" type="datetime-local" value={local} onChange={(e) => setLocal(e.target.value)} />
            </Field>
          )}
        </div>
        <div className="mt-6 flex justify-end gap-2">
          <DialogClose asChild><Button variant="text">Cancel</Button></DialogClose>
          <Button loading={loading} onClick={() => onPublish(mode === "later" ? taipeiLocalToIso(local) : null)}>
            {mode === "now" ? "Publish" : "Schedule"}
          </Button>
        </div>
      </DialogContent>
    </Dialog>
  );
}

/** The resolution drawer: failed recipients with their reason, filterable by code, and the two ways out. */
function ResolutionDrawer({ id, stats, open, onOpenChange }: { id: number; stats: CampaignStatsDto; open: boolean; onOpenChange: (o: boolean) => void }) {
  const [code, setCode] = React.useState("");
  const [page, setPage] = React.useState(0);
  const [confirm, setConfirm] = React.useState<"retry" | "terminate" | null>(null);
  const failures = useFailures(id, code, page, 50, open);
  const retry = useRetryFailed(id);
  const abort = useAbortCampaign(id);

  const act = async (kind: "retry" | "terminate") => {
    try {
      if (kind === "retry") {
        await retry.mutateAsync();
        toast("Retrying failed recipients");
      } else {
        await abort.mutateAsync();
        toast("Campaign terminated");
      }
      setConfirm(null);
      onOpenChange(false);
    } catch (err) {
      toast.error(errorMessage(err));
    }
  };

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <Sheet title="Resolve failures" description={`${stats.failedCount.toLocaleString()} of ${stats.totalCount.toLocaleString()} recipients could not be reached.`} width="720px">
        <div className="flex flex-wrap gap-2 border-b border-gcp-border px-6 py-3" role="tablist" aria-label="Filter by error code">
          <button role="tab" aria-selected={code === ""} onClick={() => { setCode(""); setPage(0); }} className={cn("h-7 rounded-full border px-3 text-xs", code === "" ? "border-gcp-blue bg-gcp-blueBg text-gcp-blue" : "border-gcp-border text-gcp-text")}>
            All · {stats.failedCount.toLocaleString()}
          </button>
          {stats.failureBreakdown.map((b) => (
            <button key={b.errorCode} role="tab" aria-selected={code === b.errorCode} onClick={() => { setCode(b.errorCode); setPage(0); }} className={cn("h-7 rounded-full border px-3 font-mono text-xs", code === b.errorCode ? "border-gcp-blue bg-gcp-blueBg text-gcp-blue" : "border-gcp-border text-gcp-text")}>
              {b.errorCode} · {b.count.toLocaleString()}
            </button>
          ))}
        </div>

        {failures.isPending ? (
          <Spinner />
        ) : !failures.data || failures.data.items.length === 0 ? (
          <EmptyState title="No failed recipients" description="Every recipient was delivered or cancelled." />
        ) : (
          <>
            <table className="gcp-table">
              <thead>
                <tr><th>Recipient</th><th>Code</th><th>Reason</th><th className="text-right">Tries</th></tr>
              </thead>
              <tbody>
                {failures.data.items.map((r) => (
                  <tr key={r.id}>
                    <td className="font-mono text-xs">{r.recipientIdentifier}</td>
                    <td><span className="rounded bg-gcp-redBg px-1.5 py-0.5 font-mono text-[11px] text-gcp-red">{r.errorCode ?? "—"}</span></td>
                    <td className="max-w-[280px] truncate text-xs text-gcp-text2" title={r.errorMessage ?? ""}>{r.errorMessage}</td>
                    <td className="text-right font-mono text-xs">{r.retryCount + 1}</td>
                  </tr>
                ))}
              </tbody>
            </table>
            <Pager page={failures.data.page} totalPages={failures.data.totalPages} totalElements={failures.data.totalElements} onPage={setPage} />
          </>
        )}

        <footer className="mt-auto flex items-center justify-between gap-3 border-t border-gcp-border px-6 py-4">
          <p className="text-xs text-gcp-text2">Fix the cause first (channel settings, addresses), then retry. Terminating keeps what was sent.</p>
          <div className="flex shrink-0 gap-2">
            <Button variant="dangerText" onClick={() => setConfirm("terminate")}>Terminate</Button>
            <Button onClick={() => setConfirm("retry")}><RefreshCw className="h-4 w-4" aria-hidden />Retry failed</Button>
          </div>
        </footer>
      </Sheet>
      <ConfirmDialog
        open={confirm === "retry"} onOpenChange={(o) => !o && setConfirm(null)}
        title="Retry failed recipients?" description={`${stats.failedCount.toLocaleString()} recipients go back to pending and are sent again with the current channel settings.`}
        confirmLabel="Retry failed" loading={retry.isPending} onConfirm={() => act("retry")}
      />
      <ConfirmDialog
        open={confirm === "terminate"} onOpenChange={(o) => !o && setConfirm(null)}
        title="Terminate campaign?" description="The campaign closes as terminated. Delivered messages stay delivered; failed recipients are not retried."
        confirmLabel="Terminate" danger loading={abort.isPending} onConfirm={() => act("terminate")}
      />
    </Dialog>
  );
}

export function CampaignDetailPage() {
  const id = Number(useParams().id);
  const navigate = useNavigate();
  const detail = useCampaign(id);
  const stats = useCampaignStats(id, !!detail.data);
  const campaign = detail.data?.campaign;
  const template = useTemplate(campaign?.templateId ?? null);
  const upload = useUploadRecipients(id);
  const publish = usePublishCampaign(id);
  const abort = useAbortCampaign(id);
  const del = useDeleteCampaign();

  const [publishOpen, setPublishOpen] = React.useState(false);
  const [abortOpen, setAbortOpen] = React.useState(false);
  const [deleteOpen, setDeleteOpen] = React.useState(false);
  const [drawerOpen, setDrawerOpen] = React.useState(false);
  const [recipientStatus, setRecipientStatus] = React.useState<RecipientStatus | "">("");
  const [recipientPage, setRecipientPage] = React.useState(0);
  const recipients = useRecipients(id, recipientStatus, recipientPage);

  if (detail.isPending) return <Spinner />;
  if (detail.isError || !campaign) {
    return <EmptyState title="Campaign not found" description={detail.error ? errorMessage(detail.error) : undefined} action={<Button variant="outline" onClick={() => navigate("/campaigns")}>Back to campaigns</Button>} />;
  }

  const status = campaign.status as CampaignStatus;
  const isDraft = status === "DRAFT";
  const canAbort = !["COMPLETED", "TERMINATED", "DRAFT"].includes(status);
  const needsAttention = status === "AWAITING_RESOLUTION";
  const isCsv = campaign.targetAudienceType === "CSV_LIST";

  const run = async (fn: () => Promise<unknown>, done: string) => {
    try {
      await fn();
      toast(done);
    } catch (err) {
      toast.error(errorMessage(err));
    }
  };

  return (
    <>
      <PageHeader
        title={campaign.title}
        meta={<StatusChip status={status} />}
        crumbs={[{ label: "Campaigns", to: "/campaigns" }, { label: `#${campaign.id}` }]}
        actions={
          <>
            {isDraft && <Button variant="dangerText" onClick={() => setDeleteOpen(true)}>Delete</Button>}
            {isDraft && <Button onClick={() => setPublishOpen(true)}>Publish</Button>}
            {needsAttention && <Button onClick={() => setDrawerOpen(true)}><AlertTriangle className="h-4 w-4" aria-hidden />Resolve failures</Button>}
            {canAbort && !needsAttention && <Button variant="dangerText" onClick={() => setAbortOpen(true)}>{status === "SCHEDULED" ? "Cancel schedule" : "Abort"}</Button>}
          </>
        }
      />

      {needsAttention && stats.data && (
        <div className="mb-6 flex items-center gap-3 rounded-lg border border-gcp-yellow bg-gcp-yellowBg px-4 py-3 text-sm text-gcp-text">
          <AlertTriangle className="h-5 w-5 shrink-0 text-gcp-yellowText" aria-hidden />
          <span className="flex-1">
            Sending finished with <strong>{stats.data.failedCount.toLocaleString()}</strong> failed recipients. Review the reasons, then retry or terminate.
          </span>
          <Button variant="text" size="sm" onClick={() => setDrawerOpen(true)}>Open</Button>
        </div>
      )}

      <div className="grid gap-6 lg:grid-cols-[2fr_1fr]">
        <Card>
          <CardHeader title="Delivery" description={stats.data?.processing ? "Updating every 5 seconds" : undefined} />
          <CardBody>{stats.data ? <ProgressBar stats={stats.data} /> : <Spinner />}</CardBody>
        </Card>
        <Card>
          <CardHeader title="Details" />
          <CardBody>
            <DescriptionList
              items={[
                { label: "Channel", value: CHANNEL_LABEL[campaign.channelType as "EMAIL" | "LINE"] },
                { label: "Template", value: template.data ? <a className="gcp-link" href={`/templates/${template.data.id}`}>{template.data.name}</a> : `#${campaign.templateId}` },
                { label: "Audience", value: AUDIENCE_LABEL[campaign.targetAudienceType as "CSV_LIST" | "LINE_AUDIENCE_GROUP"] },
                ...(isCsv ? [{ label: "Import", value: <span className="flex items-center gap-2"><StatusChip status={campaign.importStatus} />{campaign.importError && <span className="text-xs text-gcp-red">{campaign.importError}</span>}</span> }] : []),
                { label: "Scheduled", value: formatDateTime(campaign.scheduledAt) },
                { label: "Started", value: formatDateTime(campaign.startedAt) },
                { label: "Finished", value: formatDateTime(campaign.finishedAt) },
                { label: "Created", value: formatDateTime(campaign.createdAt) },
              ]}
            />
          </CardBody>
        </Card>
      </div>

      {isCsv && (
        <Card className="mt-6">
          <CardHeader
            title="Recipients"
            actions={
              <Select value={recipientStatus} onChange={(e) => { setRecipientStatus(e.target.value as RecipientStatus | ""); setRecipientPage(0); }} className="w-40" aria-label="Filter recipients by status">
                <option value="">All statuses</option>
                {(["PENDING", "SENDING", "SENT", "FAILED", "CANCELLED"] as RecipientStatus[]).map((s) => <option key={s} value={s}>{STATUS[s]!.label}</option>)}
              </Select>
            }
          />
          {isDraft && (
            <CardBody className="border-b border-gcp-border">
              <RecipientUpload
                channel={campaign.channelType as "EMAIL" | "LINE"}
                campaign={campaign}
                template={template.data}
                uploading={upload.isPending}
                onFile={(f) => run(() => upload.mutateAsync(f), "Import started")}
              />
            </CardBody>
          )}
          {recipients.isPending ? (
            <Spinner />
          ) : !recipients.data || recipients.data.items.length === 0 ? (
            <EmptyState title={recipientStatus ? "No recipients with this status" : "No recipients yet"} description={isDraft ? "Upload a CSV above to add recipients." : undefined} />
          ) : (
            <>
              <div className="overflow-x-auto">
                <table className="gcp-table">
                  <thead>
                    <tr><th>Recipient</th><th>Status</th><th>Parameters</th><th>Error</th><th>Sent</th></tr>
                  </thead>
                  <tbody>
                    {recipients.data.items.map((r) => (
                      <tr key={r.id}>
                        <td className="font-mono text-xs">{r.recipientIdentifier}</td>
                        <td><StatusChip status={r.status} /></td>
                        <td className="max-w-[260px] truncate font-mono text-[11px] text-gcp-text2" title={r.payloadParams ? JSON.stringify(r.payloadParams) : ""}>{r.payloadParams ? JSON.stringify(r.payloadParams) : "—"}</td>
                        <td className="max-w-[240px] truncate text-xs text-gcp-red" title={r.errorMessage ?? ""}>{r.errorCode ? `${r.errorCode}: ${r.errorMessage ?? ""}` : ""}</td>
                        <td className="text-xs text-gcp-text2">{formatDateTime(r.sentAt)}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
              <Pager page={recipients.data.page} totalPages={recipients.data.totalPages} totalElements={recipients.data.totalElements} onPage={setRecipientPage} />
            </>
          )}
        </Card>
      )}

      <PublishDialog open={publishOpen} onOpenChange={setPublishOpen} loading={publish.isPending} onPublish={(at) => run(async () => { const r = await publish.mutateAsync(at); setPublishOpen(false); return r; }, at ? "Campaign scheduled" : "Campaign published")} />
      <ConfirmDialog open={abortOpen} onOpenChange={setAbortOpen} title={status === "SCHEDULED" ? "Cancel the schedule?" : "Abort sending?"} description={status === "SCHEDULED" ? "The campaign will not start. It closes as terminated." : "Sending stops after the current batch. Remaining recipients are cancelled; delivered messages stay delivered."} confirmLabel={status === "SCHEDULED" ? "Cancel schedule" : "Abort"} danger loading={abort.isPending} onConfirm={() => run(async () => { await abort.mutateAsync(); setAbortOpen(false); }, "Campaign terminated")} />
      <ConfirmDialog open={deleteOpen} onOpenChange={setDeleteOpen} title="Delete this draft?" description="The campaign and its uploaded recipients are removed. This cannot be undone." confirmLabel="Delete" danger loading={del.isPending} onConfirm={() => run(async () => { await del.mutateAsync(id); navigate("/campaigns"); }, "Campaign deleted")} />
      {stats.data && <ResolutionDrawer id={id} stats={stats.data} open={drawerOpen} onOpenChange={setDrawerOpen} />}
    </>
  );
}
