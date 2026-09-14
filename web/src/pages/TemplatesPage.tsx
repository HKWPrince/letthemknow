import * as React from "react";
import { Link, useNavigate, useSearchParams } from "react-router-dom";
import { FileText } from "lucide-react";

import { useTemplates } from "@/api/templates";
import type { ChannelType } from "@/api/types";
import { CHANNEL_LABEL } from "@/api/types";
import { EMAIL_STARTERS } from "@/lib/emailTemplates";
import { LINE_STARTERS } from "@/lib/lineTemplates";
import { LineMessagePreview } from "@/components/LineMessagePreview";
import { formatDateTime } from "@/lib/time";
import { cn } from "@/lib/utils";
import { Button } from "@/components/ui/button";
import { Card, EmptyState, PageHeader, Pager, Spinner } from "@/components/ui/page";

/**
 * Live thumbnails: the starter HTML carries no images or web fonts, so six iframes cost no network
 * requests. They are decorative — the surrounding link owns the accessible name.
 */
function ChannelBadge({ channel }: { channel: "EMAIL" | "LINE" }) {
  return (
    <span
      className={cn(
        "rounded px-1.5 py-0.5 text-[10px] font-medium uppercase tracking-wide",
        channel === "EMAIL" ? "bg-gcp-blueBg text-gcp-blue" : "bg-gcp-greenBg text-gcp-green",
      )}
    >
      {channel === "EMAIL" ? "Email" : "LINE"}
    </span>
  );
}

function StarterCard({
  id, name, description, channel, thumbnail,
}: { id: string; name: string; description: string; channel: "EMAIL" | "LINE"; thumbnail: React.ReactNode }) {
  return (
    <Link
      to={`/templates/new?starter=${id}`}
      className="group gcp-card overflow-hidden focus-visible:ring-2 focus-visible:ring-gcp-blue"
    >
      <div className="h-[168px] overflow-hidden border-b border-gcp-border bg-[#f4f5f7]">{thumbnail}</div>
      <div className="px-4 py-3">
        <p className="flex items-center gap-2 text-sm font-medium text-gcp-text group-hover:text-gcp-blue">
          {name} <ChannelBadge channel={channel} />
        </p>
        <p className="mt-0.5 text-xs leading-relaxed text-gcp-text2">{description}</p>
      </div>
    </Link>
  );
}

function StarterGallery() {
  return (
    <section className="mb-8">
      <h2 className="mb-1 text-base font-medium text-gcp-text">Start from a design</h2>
      <p className="mb-4 text-sm text-gcp-text2">
        Ready-made layouts: email that renders in Gmail, Outlook and Apple Mail, and LINE messages built
        from real Messaging API objects. Pick one and edit the words.
      </p>
      <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
        {EMAIL_STARTERS.map((s) => (
          <StarterCard
            key={s.id}
            id={s.id}
            name={s.name}
            description={s.description}
            channel="EMAIL"
            thumbnail={
              <iframe
                title=""
                aria-hidden
                tabIndex={-1}
                sandbox=""
                scrolling="no"
                srcDoc={s.html}
                className="pointer-events-none h-[480px] w-[600px] origin-top-left scale-[0.42] border-0"
              />
            }
          />
        ))}
        {LINE_STARTERS.map((s) => (
          <StarterCard
            key={s.id}
            id={s.id}
            name={s.name}
            description={s.description}
            channel="LINE"
            thumbnail={
              <div aria-hidden className="pointer-events-none h-full">
                <LineMessagePreview messages={s.messages} className="h-full w-full" />
              </div>
            }
          />
        ))}
      </div>
    </section>
  );
}

export function TemplatesPage() {
  const [params, setParams] = useSearchParams();
  const navigate = useNavigate();
  const channel = (params.get("channel") ?? "") as ChannelType | "";
  const page = Number(params.get("page") ?? 0);
  const { data, isPending } = useTemplates(page, 25, channel);

  return (
    <>
      <PageHeader
        title="Templates"
        description="Reusable message bodies with {{placeholders}} filled from each recipient's CSV columns."
        actions={<Button asChild><Link to="/templates/new">Create template</Link></Button>}
      />

      <StarterGallery />

      <h2 className="mb-3 text-base font-medium text-gcp-text">Your templates</h2>
      <div className="mb-4 flex gap-2" role="tablist" aria-label="Filter by channel">
        {([["", "All"], ["EMAIL", "Email"], ["LINE", "LINE"]] as const).map(([v, label]) => (
          <button key={v} role="tab" aria-selected={channel === v} onClick={() => setParams(v ? { channel: v } : {})} className={cn("h-8 rounded-full border px-3 text-[13px]", channel === v ? "border-gcp-blue bg-gcp-blueBg text-gcp-blue" : "border-gcp-border bg-white text-gcp-text hover:bg-gcp-hover")}>
            {label}
          </button>
        ))}
      </div>
      <Card>
        {isPending ? (
          <Spinner />
        ) : !data || data.items.length === 0 ? (
          <EmptyState icon={<FileText className="h-10 w-10" />} title="No saved templates yet" description="Pick one of the designs above, or start from scratch. Campaigns and single pushes both send from templates." action={<Button asChild><Link to="/templates/new">Create template</Link></Button>} />
        ) : (
          <>
            <div className="overflow-x-auto">
              <table className="gcp-table">
                <thead>
                  <tr><th>Name</th><th>Channel</th><th>Subject</th><th>Updated</th></tr>
                </thead>
                <tbody>
                  {data.items.map((t) => (
                    <tr key={t.id} data-clickable="true" onClick={() => navigate(`/templates/${t.id}`)}>
                      <td className="font-medium text-gcp-text">{t.name}</td>
                      <td>{CHANNEL_LABEL[t.channelType as ChannelType]}</td>
                      <td className="max-w-[360px] truncate text-gcp-text2">{t.subjectTemplate ?? <span className="text-gcp-text3">—</span>}</td>
                      <td className="text-gcp-text2">{formatDateTime(t.updatedAt)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
            <Pager page={data.page} totalPages={data.totalPages} totalElements={data.totalElements} onPage={(p) => setParams({ ...(channel ? { channel } : {}), page: String(p) })} />
          </>
        )}
      </Card>
    </>
  );
}
