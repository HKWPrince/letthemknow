import * as React from "react";
import { useNavigate } from "react-router-dom";
import { Check } from "lucide-react";
import { toast } from "sonner";

import { useCampaign, useCreateCampaign, usePublishCampaign, useUpdateCampaign, useUploadRecipients } from "@/api/campaigns";
import { useTemplates } from "@/api/templates";
import { useChannels } from "@/api/channels";
import { errorMessage } from "@/api/client";
import type { AudienceType, CampaignRequest, ChannelType } from "@/api/types";
import { AUDIENCE_LABEL, CHANNEL_LABEL } from "@/api/types";
import { formatDateTime, nextHourTaipeiLocal, taipeiLocalToIso } from "@/lib/time";
import { cn } from "@/lib/utils";
import { Button } from "@/components/ui/button";
import { Input, Select } from "@/components/ui/input";
import { Field } from "@/components/ui/field";
import { Card, CardBody, DescriptionList, PageHeader } from "@/components/ui/page";
import { RecipientUpload } from "@/components/RecipientUpload";

const STEPS = ["Basics", "Audience", "Schedule", "Review"] as const;

/** A real sequence: each step unlocks the next, so numbering carries meaning here. */
function Stepper({ current }: { current: number }) {
  return (
    <ol className="mb-6 flex items-center gap-2 text-sm" aria-label="Progress">
      {STEPS.map((label, i) => {
        const done = i < current;
        const active = i === current;
        return (
          <li key={label} className="flex items-center gap-2">
            <span
              className={cn(
                "grid h-7 w-7 place-items-center rounded-full text-xs font-medium",
                done ? "bg-gcp-blue text-white" : active ? "bg-gcp-blue text-white" : "bg-gcp-hover text-gcp-text2",
              )}
              aria-current={active ? "step" : undefined}
            >
              {done ? <Check className="h-4 w-4" aria-hidden /> : i + 1}
            </span>
            <span className={cn(active ? "font-medium text-gcp-text" : "text-gcp-text2")}>{label}</span>
            {i < STEPS.length - 1 && <span className="mx-2 h-px w-10 bg-gcp-border" aria-hidden />}
          </li>
        );
      })}
    </ol>
  );
}

interface Basics {
  title: string;
  channelType: ChannelType;
  templateId: string;
  targetAudienceType: AudienceType;
  audienceGroupId: string;
}

export function CampaignWizardPage() {
  const navigate = useNavigate();
  const [step, setStep] = React.useState(0);
  const [campaignId, setCampaignId] = React.useState<number | null>(null);
  const [basics, setBasics] = React.useState<Basics>({
    title: "", channelType: "EMAIL", templateId: "", targetAudienceType: "CSV_LIST", audienceGroupId: "",
  });
  const [schedule, setSchedule] = React.useState<"now" | "later">("now");
  const [scheduledLocal, setScheduledLocal] = React.useState(nextHourTaipeiLocal());
  const [errors, setErrors] = React.useState<Record<string, string>>({});

  const templates = useTemplates(0, 200, basics.channelType);
  const channels = useChannels();
  const channelConfigured = channels.data?.some((c) => c.channelType === basics.channelType) ?? true;
  const create = useCreateCampaign();
  const update = useUpdateCampaign(campaignId ?? -1);
  const campaign = useCampaign(campaignId ?? -1);
  const upload = useUploadRecipients(campaignId ?? -1);
  const publish = usePublishCampaign(campaignId ?? -1);

  const isAudienceGroup = basics.targetAudienceType === "LINE_AUDIENCE_GROUP";
  const templateName = templates.data?.items.find((t) => String(t.id) === basics.templateId)?.name;

  const buildRequest = (): CampaignRequest => ({
    title: basics.title.trim(),
    channelType: basics.channelType,
    templateId: Number(basics.templateId),
    targetAudienceType: basics.targetAudienceType,
    targetAudienceMeta: isAudienceGroup ? { audienceGroupId: Number(basics.audienceGroupId) } : undefined,
  });

  const validateBasics = () => {
    const e: Record<string, string> = {};
    if (!basics.title.trim()) e.title = "Give the campaign a title.";
    if (!basics.templateId) e.templateId = "Choose a template.";
    if (isAudienceGroup && !/^\d+$/.test(basics.audienceGroupId)) e.audienceGroupId = "Enter the numeric LINE audience group ID.";
    setErrors(e);
    return Object.keys(e).length === 0;
  };

  const saveBasics = async () => {
    if (!validateBasics()) return;
    try {
      if (campaignId === null) {
        const created = await create.mutateAsync(buildRequest());
        setCampaignId(created.id!);
      } else {
        await update.mutateAsync(buildRequest());
      }
      setStep(1);
    } catch (err) {
      toast.error(errorMessage(err));
    }
  };

  const onFile = async (file: File) => {
    try {
      await upload.mutateAsync(file);
      toast("Import started");
    } catch (err) {
      toast.error(errorMessage(err));
    }
  };

  const doPublish = async () => {
    try {
      const at = schedule === "later" ? taipeiLocalToIso(scheduledLocal) : null;
      const res = await publish.mutateAsync(at);
      toast(res.status === "SCHEDULED" ? "Campaign scheduled" : "Campaign published");
      navigate(`/campaigns/${campaignId}`);
    } catch (err) {
      toast.error(errorMessage(err));
    }
  };

  const c = campaign.data?.campaign;
  const importReady = isAudienceGroup || c?.importStatus === "READY";

  return (
    <>
      <PageHeader title="Create campaign" crumbs={[{ label: "Campaigns", to: "/campaigns" }, { label: "Create" }]} />
      <Stepper current={step} />

      <Card className="max-w-3xl">
        {step === 0 && (
          <CardBody className="flex flex-col gap-5">
            <Field label="Title" htmlFor="title" required error={errors.title}>
              <Input id="title" value={basics.title} onChange={(e) => setBasics({ ...basics, title: e.target.value })} placeholder="Spring sale announcement" maxLength={150} />
            </Field>
            <div className="grid gap-5 sm:grid-cols-2">
              <Field label="Channel" htmlFor="channel" required hint={!channelConfigured ? `${CHANNEL_LABEL[basics.channelType]} is not configured yet. You can still draft; configure it under Channels before publishing.` : undefined}>
                <Select
                  id="channel"
                  value={basics.channelType}
                  onChange={(e) => {
                    const channelType = e.target.value as ChannelType;
                    setBasics({ ...basics, channelType, templateId: "", targetAudienceType: channelType === "LINE" ? basics.targetAudienceType : "CSV_LIST" });
                  }}
                >
                  <option value="EMAIL">Email</option>
                  <option value="LINE">LINE</option>
                </Select>
              </Field>
              <Field label="Template" htmlFor="template" required error={errors.templateId} hint={templates.data && templates.data.items.length === 0 ? "No templates for this channel yet. Create one under Templates." : undefined}>
                <Select id="template" value={basics.templateId} onChange={(e) => setBasics({ ...basics, templateId: e.target.value })}>
                  <option value="">Choose a template</option>
                  {templates.data?.items.map((t) => (
                    <option key={t.id} value={t.id}>{t.name}</option>
                  ))}
                </Select>
              </Field>
            </div>
            <fieldset className="flex flex-col gap-2">
              <legend className="mb-1 text-xs font-medium text-gcp-text2">Audience</legend>
              {(["CSV_LIST", "LINE_AUDIENCE_GROUP"] as AudienceType[]).map((a) => {
                const disabled = a === "LINE_AUDIENCE_GROUP" && basics.channelType !== "LINE";
                return (
                  <label key={a} className={cn("flex items-start gap-3 rounded border px-4 py-3", basics.targetAudienceType === a ? "border-gcp-blue bg-gcp-blueBg" : "border-gcp-border", disabled && "opacity-50")}>
                    <input type="radio" name="audience" className="mt-1 accent-gcp-blue" checked={basics.targetAudienceType === a} disabled={disabled} onChange={() => setBasics({ ...basics, targetAudienceType: a })} />
                    <span>
                      <span className="block text-sm font-medium text-gcp-text">{AUDIENCE_LABEL[a]}</span>
                      <span className="block text-xs text-gcp-text2">
                        {a === "CSV_LIST" ? "Upload a CSV of recipients. Extra columns become template parameters." : "Send one LINE narrowcast to an audience group you manage in LINE Official Account Manager."}
                      </span>
                    </span>
                  </label>
                );
              })}
            </fieldset>
            {isAudienceGroup && (
              <Field label="Audience group ID" htmlFor="agid" required error={errors.audienceGroupId}>
                <Input id="agid" inputMode="numeric" value={basics.audienceGroupId} onChange={(e) => setBasics({ ...basics, audienceGroupId: e.target.value })} className="max-w-xs font-mono" />
              </Field>
            )}
            <div className="flex justify-end gap-2 border-t border-gcp-border pt-4">
              <Button variant="text" onClick={() => navigate("/campaigns")}>Cancel</Button>
              <Button onClick={saveBasics} loading={create.isPending || update.isPending}>Continue</Button>
            </div>
          </CardBody>
        )}

        {step === 1 && (
          <CardBody className="flex flex-col gap-5">
            {isAudienceGroup ? (
              <DescriptionList items={[{ label: "Audience group", value: <span className="font-mono">{basics.audienceGroupId}</span> }, { label: "Delivery", value: "One narrowcast request; LINE reports the reach when it finishes." }]} />
            ) : (
              <RecipientUpload
                channel={basics.channelType}
                campaign={c}
                template={templates.data?.items.find((t) => String(t.id) === basics.templateId)}
                uploading={upload.isPending}
                onFile={(f) => onFile(f)}
              />
            )}
            <div className="flex justify-between gap-2 border-t border-gcp-border pt-4">
              <Button variant="text" onClick={() => setStep(0)}>Back</Button>
              <Button onClick={() => setStep(2)} disabled={!importReady}>Continue</Button>
            </div>
          </CardBody>
        )}

        {step === 2 && (
          <CardBody className="flex flex-col gap-5">
            <fieldset className="flex flex-col gap-2">
              <legend className="mb-1 text-xs font-medium text-gcp-text2">When to send</legend>
              <label className={cn("flex items-center gap-3 rounded border px-4 py-3", schedule === "now" ? "border-gcp-blue bg-gcp-blueBg" : "border-gcp-border")}>
                <input type="radio" name="when" className="accent-gcp-blue" checked={schedule === "now"} onChange={() => setSchedule("now")} />
                <span className="text-sm">Send now</span>
              </label>
              <label className={cn("flex items-center gap-3 rounded border px-4 py-3", schedule === "later" ? "border-gcp-blue bg-gcp-blueBg" : "border-gcp-border")}>
                <input type="radio" name="when" className="accent-gcp-blue" checked={schedule === "later"} onChange={() => setSchedule("later")} />
                <span className="text-sm">Schedule for later</span>
              </label>
            </fieldset>
            {schedule === "later" && (
              <Field label="Send at (Asia/Taipei)" htmlFor="at" hint="Times are shown and entered in Taipei time; the server stores UTC.">
                <Input id="at" type="datetime-local" value={scheduledLocal} onChange={(e) => setScheduledLocal(e.target.value)} className="max-w-xs" />
              </Field>
            )}
            <div className="flex justify-between gap-2 border-t border-gcp-border pt-4">
              <Button variant="text" onClick={() => setStep(1)}>Back</Button>
              <Button onClick={() => setStep(3)}>Continue</Button>
            </div>
          </CardBody>
        )}

        {step === 3 && (
          <CardBody className="flex flex-col gap-5">
            <DescriptionList
              items={[
                { label: "Title", value: basics.title },
                { label: "Channel", value: CHANNEL_LABEL[basics.channelType] },
                { label: "Template", value: templateName },
                { label: "Audience", value: isAudienceGroup ? `${AUDIENCE_LABEL.LINE_AUDIENCE_GROUP} · ${basics.audienceGroupId}` : `${c?.totalCount.toLocaleString() ?? 0} recipients` },
                { label: "Send", value: schedule === "now" ? "Immediately after publishing" : formatDateTime(taipeiLocalToIso(scheduledLocal)) },
              ]}
            />
            {!channelConfigured && (
              <p className="rounded bg-gcp-yellowBg px-4 py-3 text-sm text-gcp-yellowText">
                {CHANNEL_LABEL[basics.channelType]} is not configured for this tenant. Publishing will be refused until it is.
              </p>
            )}
            <div className="flex justify-between gap-2 border-t border-gcp-border pt-4">
              <Button variant="text" onClick={() => setStep(2)}>Back</Button>
              <div className="flex gap-2">
                <Button variant="outline" onClick={() => navigate(`/campaigns/${campaignId}`)}>Save as draft</Button>
                <Button onClick={doPublish} loading={publish.isPending}>{schedule === "now" ? "Publish" : "Schedule"}</Button>
              </div>
            </div>
          </CardBody>
        )}
      </Card>
    </>
  );
}
