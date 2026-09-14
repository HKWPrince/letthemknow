import * as React from "react";
import { Download, FileUp, Loader2 } from "lucide-react";

import type { CampaignDto, ChannelType, MessageTemplateDto } from "@/api/types";
import { extractPlaceholders } from "@/api/templates";
import { buildSampleCsv, downloadTextFile, sampleColumns } from "@/lib/sampleCsv";
import { cn } from "@/lib/utils";
import { Button } from "@/components/ui/button";
import { StatusChip } from "@/components/ui/status-chip";

const MAX_BYTES = 20 * 1024 * 1024;

interface Props {
  channel: ChannelType;
  campaign?: CampaignDto;
  template?: MessageTemplateDto;
  uploading: boolean;
  onFile: (file: File) => void;
  className?: string;
}

/** Drop zone shared by the campaign wizard and the campaign page, so both behave identically. */
export function RecipientUpload({ channel, campaign, template, uploading, onFile, className }: Props) {
  const [dragging, setDragging] = React.useState(false);
  const [error, setError] = React.useState<string | null>(null);
  const inputRef = React.useRef<HTMLInputElement>(null);

  const importing = campaign?.importStatus === "IMPORTING";
  const busy = uploading || importing;

  const placeholders = React.useMemo(() => {
    if (!template) return [];
    const payload = template.contentPayload as { html?: string; text?: string; messages?: unknown[] } | undefined;
    return template.channelType === "EMAIL"
      ? extractPlaceholders(template.subjectTemplate, payload?.html, payload?.text)
      : extractPlaceholders(JSON.stringify(payload?.messages ?? []));
  }, [template]);

  const accept = (file: File | undefined) => {
    if (!file) return;
    setError(null);
    if (!/\.csv$/i.test(file.name) && file.type !== "text/csv") {
      setError(`“${file.name}” is not a CSV. Export your list as CSV and try again.`);
      return;
    }
    if (file.size > MAX_BYTES) {
      setError(`That file is ${(file.size / 1024 / 1024).toFixed(1)} MB. The limit is 20 MB.`);
      return;
    }
    onFile(file);
  };

  const idLabel = channel === "EMAIL" ? "email address" : "LINE user ID";

  return (
    <div className={cn("flex flex-col gap-3", className)}>
      <div
        onDragOver={(e) => { e.preventDefault(); if (!busy) setDragging(true); }}
        onDragLeave={() => setDragging(false)}
        onDrop={(e) => { e.preventDefault(); setDragging(false); if (!busy) accept(e.dataTransfer.files?.[0]); }}
        className={cn(
          "rounded-lg border-2 border-dashed px-6 py-8 text-center transition-colors",
          dragging ? "border-gcp-blue bg-gcp-blueBg" : "border-gcp-border bg-gcp-ground",
          busy && "opacity-70",
        )}
      >
        {busy ? (
          <Loader2 className="mx-auto mb-3 h-8 w-8 animate-spin text-gcp-blue" aria-hidden />
        ) : (
          <FileUp className={cn("mx-auto mb-3 h-8 w-8", dragging ? "text-gcp-blue" : "text-gcp-text3")} aria-hidden />
        )}
        <p className="text-sm text-gcp-text">
          {busy ? (
            importing ? "Checking and importing recipients…" : "Uploading…"
          ) : (
            <>
              Drag a CSV here, or{" "}
              <button
                type="button"
                className="font-medium text-gcp-blue hover:underline"
                onClick={() => inputRef.current?.click()}
              >
                browse your files
              </button>
            </>
          )}
        </p>
        <p className="mt-1 text-xs text-gcp-text2">
          Header row required. First column is the {idLabel}. Up to 20 MB or 200,000 rows.
          {campaign && campaign.totalCount > 0 && " Uploading again replaces the current list."}
        </p>
        {/* Accessible name stays "Recipient CSV": the smoke test selects this input by that label. */}
        <label className="sr-only" htmlFor="recipient-csv">Recipient CSV</label>
        <input
          id="recipient-csv"
          ref={inputRef}
          type="file"
          accept=".csv,text/csv"
          className="sr-only"
          disabled={busy}
          onChange={(e) => { accept(e.target.files?.[0]); e.target.value = ""; }}
        />
      </div>

      {error && <p className="rounded bg-gcp-redBg px-3 py-2 text-sm text-gcp-red" role="alert">{error}</p>}

      {campaign && campaign.importStatus !== "NONE" && (
        <div className="flex items-center gap-3 rounded border border-gcp-border px-4 py-3">
          <StatusChip status={campaign.importStatus} />
          <span className="text-sm text-gcp-text">
            {campaign.importStatus === "IMPORTING" && "Parsing and checking recipients…"}
            {campaign.importStatus === "READY" && `${campaign.totalCount.toLocaleString()} recipients ready`}
            {campaign.importStatus === "FAILED" && (campaign.importError ?? "Import failed")}
          </span>
        </div>
      )}

      <div className="flex flex-wrap items-center justify-between gap-3 rounded border border-gcp-border bg-white px-4 py-3">
        <div className="min-w-0">
          <p className="text-xs font-medium text-gcp-text2">Columns for this template</p>
          <p className="mt-1 break-words font-mono text-xs text-gcp-text">{sampleColumns(placeholders).join(", ")}</p>
          {placeholders.length === 0 && (
            <p className="mt-1 text-xs text-gcp-text3">
              This template has no {"{{placeholders}}"}, so only the recipient column is needed.
            </p>
          )}
        </div>
        <Button
          variant="outline"
          size="sm"
          onClick={() => downloadTextFile("recipients-sample.csv", buildSampleCsv(channel, placeholders))}
        >
          <Download className="h-4 w-4" aria-hidden />
          Download sample CSV
        </Button>
      </div>
    </div>
  );
}
