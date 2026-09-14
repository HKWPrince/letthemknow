import * as React from "react";
import { useNavigate, useParams, useSearchParams } from "react-router-dom";
import { toast } from "sonner";

import { extractPlaceholders, useDeleteTemplate, useSaveTemplate, useTemplate } from "@/api/templates";
import { DEFAULT_EMAIL_STARTER, EMAIL_STARTERS, type EmailStarter } from "@/lib/emailTemplates";
import { DEFAULT_LINE_STARTER, LINE_STARTERS, lineStarterJson, type LineStarter } from "@/lib/lineTemplates";
import { LineMessagePreview } from "@/components/LineMessagePreview";
import { errorMessage } from "@/api/client";
import type { ChannelType, MessageTemplateRequest } from "@/api/types";
import { cn } from "@/lib/utils";
import { Button } from "@/components/ui/button";
import { Input, Select, Textarea } from "@/components/ui/input";
import { Field } from "@/components/ui/field";
import { ConfirmDialog } from "@/components/ui/dialog";
import { Card, CardBody, CardHeader, PageHeader, Spinner } from "@/components/ui/page";

const BLANK_LINE = JSON.stringify([{ type: "text", text: "Hi {{name}}," }], null, 2);

function escapeHtml(s: string) {
  return s.replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" })[c]!);
}

/** Same substitution rules as the server: {{name}} → value, missing → empty. */
function render(template: string, params: Record<string, string>, html: boolean) {
  return template.replace(/\{\{\s*([A-Za-z0-9_.\-]+)\s*}}/g, (_, k: string) => {
    const v = params[k] ?? "";
    return html ? escapeHtml(v) : v;
  });
}

function validateLine(json: string): { messages?: unknown[]; error?: string } {
  let parsed: unknown;
  try {
    parsed = JSON.parse(json);
  } catch (e) {
    return { error: `Not valid JSON: ${(e as Error).message}` };
  }
  if (!Array.isArray(parsed) || parsed.length === 0 || parsed.length > 5) return { error: "Provide an array of 1 to 5 LINE message objects." };
  for (const [i, m] of parsed.entries()) {
    if (!m || typeof m !== "object" || typeof (m as { type?: unknown }).type !== "string") return { error: `Message ${i + 1} needs a "type" (text, flex, image, …).` };
  }
  return { messages: parsed };
}

/** Escape hatch for pasting your own HTML, so nobody has to clear a 100-line textarea by hand. */
const BLANK_STARTER: EmailStarter = {
  id: "blank",
  name: "Blank",
  description: "Start from an empty body and paste your own HTML.",
  subject: "",
  html: "<p>Hi {{name}},</p>\n<p></p>\n",
  text: "Hi {{name}},\n\n",
};

export function TemplateEditorPage() {
  const params = useParams();
  const id = params.id ? Number(params.id) : null;
  const navigate = useNavigate();
  const [search] = useSearchParams();
  // ?starter=<id> comes from the gallery on the Templates page, and may name an email or a LINE design
  const requestedId = search.get("starter");
  const requested = React.useMemo(
    () => [...EMAIL_STARTERS, BLANK_STARTER].find((s) => s.id === requestedId) ?? DEFAULT_EMAIL_STARTER,
    // read once, on mount: later edits must not be clobbered by the URL
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [],
  );
  const requestedLine = React.useMemo(
    () => LINE_STARTERS.find((s) => s.id === requestedId),
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [],
  );
  const existing = useTemplate(id);
  const save = useSaveTemplate(id);
  const del = useDeleteTemplate();

  const [name, setName] = React.useState("");
  const [channel, setChannel] = React.useState<ChannelType>(requestedLine ? "LINE" : "EMAIL");
  const [subject, setSubject] = React.useState(requested.subject);
  const [html, setHtml] = React.useState(requested.html);
  const [text, setText] = React.useState(requested.text);
  const [starterId, setStarterId] = React.useState(requested.id);
  const [lineJson, setLineJson] = React.useState(lineStarterJson(requestedLine ?? DEFAULT_LINE_STARTER));
  const [lineStarterId, setLineStarterId] = React.useState((requestedLine ?? DEFAULT_LINE_STARTER).id);
  const [sample, setSample] = React.useState<Record<string, string>>({});
  const [previewMode, setPreviewMode] = React.useState<"html" | "text">("html");
  const [previewWidth, setPreviewWidth] = React.useState<"desktop" | "mobile">("desktop");
  const [deleteOpen, setDeleteOpen] = React.useState(false);
  const [loaded, setLoaded] = React.useState(id === null);

  const applyStarter = (s: EmailStarter) => {
    setStarterId(s.id);
    setSubject(s.subject);
    setHtml(s.html);
    setText(s.text);
  };

  const applyLineStarter = (s: LineStarter | null) => {
    setLineStarterId(s?.id ?? "blank");
    setLineJson(s ? lineStarterJson(s) : BLANK_LINE);
  };

  React.useEffect(() => {
    const t = existing.data;
    if (!t || loaded) return;
    setName(t.name);
    setChannel(t.channelType as ChannelType);
    setSubject(t.subjectTemplate ?? "");
    const payload = t.contentPayload as { html?: string; text?: string; messages?: unknown[] } | undefined;
    if (t.channelType === "EMAIL") {
      setHtml(payload?.html ?? "");
      setText(payload?.text ?? "");
    } else {
      setLineJson(JSON.stringify(payload?.messages ?? [], null, 2));
    }
    setLoaded(true);
  }, [existing.data, loaded]);

  const lineCheck = channel === "LINE" ? validateLine(lineJson) : {};
  const placeholders = channel === "EMAIL" ? extractPlaceholders(subject, html, text) : extractPlaceholders(lineJson);
  const sampleOf = (k: string) => sample[k] ?? "";

  const insertPlaceholder = (k: string) => {
    if (channel === "EMAIL") setHtml((h) => `${h}{{${k}}}`);
    else setLineJson((j) => j.replace(/"\s*}\s*\]?\s*$/, (m) => ` {{${k}}}${m}`));
  };

  const onSave = async () => {
    if (!name.trim()) return toast.error("Give the template a name.");
    if (channel === "EMAIL" && !subject.trim()) return toast.error("Email templates need a subject.");
    if (channel === "EMAIL" && !html.trim()) return toast.error("Email templates need an HTML body.");
    if (channel === "LINE" && lineCheck.error) return toast.error(lineCheck.error);
    const body: MessageTemplateRequest = {
      name: name.trim(),
      channelType: channel,
      subjectTemplate: channel === "EMAIL" ? subject.trim() : undefined,
      contentPayload: channel === "EMAIL" ? { html, text: text.trim() ? text : undefined } : { messages: lineCheck.messages },
    };
    try {
      const saved = await save.mutateAsync(body);
      toast(id === null ? "Template created" : "Template saved");
      if (id === null) navigate(`/templates/${saved.id}`, { replace: true });
    } catch (err) {
      toast.error(errorMessage(err));
    }
  };

  if (id !== null && existing.isPending) return <Spinner />;

  const renderedLine = lineCheck.messages ? render(JSON.stringify(lineCheck.messages, null, 2), sample, false) : null;

  return (
    <>
      <PageHeader
        title={id === null ? "Create template" : name || "Template"}
        crumbs={[{ label: "Templates", to: "/templates" }, { label: id === null ? "Create" : `#${id}` }]}
        actions={
          <>
            {id !== null && <Button variant="dangerText" onClick={() => setDeleteOpen(true)}>Delete</Button>}
            <Button variant="text" onClick={() => navigate("/templates")}>Cancel</Button>
            <Button onClick={onSave} loading={save.isPending}>{id === null ? "Create" : "Save"}</Button>
          </>
        }
      />

      <div className="grid gap-6 xl:grid-cols-[minmax(0,1fr)_minmax(340px,660px)]">
        <Card>
          <CardBody className="flex flex-col gap-5">
            {id === null && (
              <div>
                <p className="mb-2 text-xs font-medium text-gcp-text2">Start from</p>
                <div className="flex flex-wrap gap-2">
                  {channel === "EMAIL"
                    ? [...EMAIL_STARTERS, BLANK_STARTER].map((s) => (
                        <button
                          key={s.id}
                          type="button"
                          title={s.description}
                          aria-pressed={starterId === s.id}
                          onClick={() => applyStarter(s)}
                          className={cn(
                            "h-8 rounded-full border px-3 text-[13px] transition-colors",
                            starterId === s.id
                              ? "border-gcp-blue bg-gcp-blueBg text-gcp-blue"
                              : "border-gcp-border bg-white text-gcp-text hover:bg-gcp-hover",
                          )}
                        >
                          {s.name}
                        </button>
                      ))
                    : [...LINE_STARTERS, null].map((s) => {
                        const sid = s?.id ?? "blank";
                        return (
                          <button
                            key={sid}
                            type="button"
                            title={s?.description}
                            aria-pressed={lineStarterId === sid}
                            onClick={() => applyLineStarter(s)}
                            className={cn(
                              "h-8 rounded-full border px-3 text-[13px] transition-colors",
                              lineStarterId === sid
                                ? "border-gcp-blue bg-gcp-blueBg text-gcp-blue"
                                : "border-gcp-border bg-white text-gcp-text hover:bg-gcp-hover",
                            )}
                          >
                            {s?.name ?? "Blank"}
                          </button>
                        );
                      })}
                </div>
                <p className="mt-2 text-xs text-gcp-text3">
                  {channel === "EMAIL" ? (
                    <>
                      {[...EMAIL_STARTERS, BLANK_STARTER].find((s) => s.id === starterId)?.description}
                      {starterId !== BLANK_STARTER.id && " Edit “Your Company” to your own name."}
                    </>
                  ) : (
                    LINE_STARTERS.find((s) => s.id === lineStarterId)?.description ??
                    "Start from an empty text message."
                  )}
                </p>
              </div>
            )}
            <div className="grid gap-5 sm:grid-cols-[1fr_180px]">
              <Field label="Name" htmlFor="name" required hint="Unique within your tenant. ERP integrations can send by this name.">
                <Input id="name" value={name} onChange={(e) => setName(e.target.value)} maxLength={100} />
              </Field>
              <Field label="Channel" htmlFor="channel" required>
                <Select id="channel" value={channel} onChange={(e) => setChannel(e.target.value as ChannelType)}>
                  <option value="EMAIL">Email</option>
                  <option value="LINE">LINE</option>
                </Select>
              </Field>
            </div>

            {channel === "EMAIL" ? (
              <>
                <Field label="Subject" htmlFor="subject" required>
                  <Input id="subject" value={subject} onChange={(e) => setSubject(e.target.value)} maxLength={255} />
                </Field>
                <Field label="HTML body" htmlFor="html" required hint="Placeholder values are HTML-escaped when inserted here.">
                  <Textarea id="html" value={html} onChange={(e) => setHtml(e.target.value)} className="min-h-[260px] font-mono text-xs" spellCheck={false} />
                </Field>
                <Field label="Plain-text body" htmlFor="text" hint="Optional fallback for mail clients that do not render HTML.">
                  <Textarea id="text" value={text} onChange={(e) => setText(e.target.value)} className="min-h-[100px] font-mono text-xs" spellCheck={false} />
                </Field>
              </>
            ) : (
              <Field label="Messages (JSON array)" htmlFor="line" required error={lineCheck.error} hint={!lineCheck.error ? `${lineCheck.messages?.length ?? 0} of 5 messages. Any LINE message object works: text, image, flex, template…` : undefined}>
                <Textarea id="line" value={lineJson} onChange={(e) => setLineJson(e.target.value)} className="min-h-[320px] font-mono text-xs" spellCheck={false} aria-invalid={!!lineCheck.error} />
              </Field>
            )}
          </CardBody>
        </Card>

        <div className="flex flex-col gap-6">
          <Card>
            <CardHeader title="Placeholders" description={placeholders.length ? "Detected in this template. Give sample values to preview." : "Write {{name}} anywhere to insert a recipient value."} />
            <CardBody className="flex flex-col gap-3">
              {placeholders.length === 0 ? (
                <div className="flex flex-wrap gap-2">
                  {["name", "code", "order"].map((k) => (
                    <button key={k} type="button" onClick={() => insertPlaceholder(k)} className="gcp-kbd hover:bg-gcp-blueBg hover:text-gcp-blue">{`{{${k}}}`}</button>
                  ))}
                </div>
              ) : (
                placeholders.map((k) => (
                  <Field key={k} label={`{{${k}}}`} htmlFor={`p-${k}`}>
                    <Input id={`p-${k}`} value={sampleOf(k)} onChange={(e) => setSample({ ...sample, [k]: e.target.value })} placeholder="Sample value" className="h-8 text-xs" />
                  </Field>
                ))
              )}
            </CardBody>
          </Card>

          <Card>
            <CardHeader
              title="Preview"
              actions={channel === "EMAIL" ? (
                <div className="flex items-center gap-2">
                  {previewMode === "html" && (
                    <div className="flex rounded border border-gcp-border text-xs" role="tablist" aria-label="Preview width">
                      {([["desktop", "Desktop"], ["mobile", "Mobile"]] as const).map(([w, label]) => (
                        <button key={w} role="tab" aria-selected={previewWidth === w} onClick={() => setPreviewWidth(w)} className={cn("px-3 py-1", previewWidth === w ? "bg-gcp-blueBg text-gcp-blue" : "text-gcp-text2")}>{label}</button>
                      ))}
                    </div>
                  )}
                  <div className="flex rounded border border-gcp-border text-xs" role="tablist" aria-label="Preview format">
                    {(["html", "text"] as const).map((m) => (
                      <button key={m} role="tab" aria-selected={previewMode === m} onClick={() => setPreviewMode(m)} className={cn("px-3 py-1", previewMode === m ? "bg-gcp-blueBg text-gcp-blue" : "text-gcp-text2")}>{m.toUpperCase()}</button>
                    ))}
                  </div>
                </div>
              ) : undefined}
            />
            {channel === "EMAIL" ? (
              <div>
                <div className="border-b border-gcp-border px-6 py-3 text-sm">
                  <span className="text-gcp-text2">Subject: </span>
                  <span className="text-gcp-text">{render(subject, sample, false) || <span className="text-gcp-text3">(empty)</span>}</span>
                </div>
                {previewMode === "html" ? (
                  // The template centres itself, so narrowing the frame triggers its own mobile breakpoint.
                  <div className={cn("bg-[#f4f5f7]", previewWidth === "mobile" && "flex justify-center py-3")}>
                    <iframe
                      title="Email preview"
                      sandbox=""
                      className={cn("h-[560px] border-0 bg-white", previewWidth === "mobile" ? "w-[375px] shadow-menu" : "w-full")}
                      srcDoc={render(html, sample, true)}
                    />
                  </div>
                ) : (
                  <pre className="max-h-[560px] overflow-auto whitespace-pre-wrap px-6 py-4 font-mono text-xs text-gcp-text">{render(text, sample, false) || "(no plain-text body)"}</pre>
                )}
              </div>
            ) : (
              <CardBody>
                {lineCheck.error ? (
                  <p className="text-sm text-gcp-red">{lineCheck.error}</p>
                ) : (
                  <LineMessagePreview
                    messages={JSON.parse(renderedLine ?? "[]") as unknown[]}
                    className="-mx-6 -my-5 min-h-[320px]"
                  />
                )}
              </CardBody>
            )}
          </Card>
        </div>
      </div>

      <ConfirmDialog open={deleteOpen} onOpenChange={setDeleteOpen} title="Delete this template?" description="Templates used by a campaign or a sent message cannot be deleted." confirmLabel="Delete" danger loading={del.isPending} onConfirm={async () => { try { await del.mutateAsync(id!); toast("Template deleted"); navigate("/templates"); } catch (err) { toast.error(errorMessage(err)); setDeleteOpen(false); } }} />
    </>
  );
}
