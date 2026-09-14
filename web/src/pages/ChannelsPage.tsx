import * as React from "react";
import { toast } from "sonner";

import { useChannels, useSaveLineChannel, useSaveSmtpChannel } from "@/api/channels";
import { errorMessage } from "@/api/client";
import type { ChannelConfigDto } from "@/api/types";
import { formatDateTime } from "@/lib/time";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Field } from "@/components/ui/field";
import { Card, CardBody, CardHeader, PageHeader, Spinner } from "@/components/ui/page";

function Configured({ config }: { config?: ChannelConfigDto }) {
  return config ? (
    <span className="inline-flex items-center gap-1.5 text-xs text-gcp-green"><span className="h-1.5 w-1.5 rounded-full bg-gcp-green" />Configured · updated {formatDateTime(config.updatedAt)}</span>
  ) : (
    <span className="inline-flex items-center gap-1.5 text-xs text-gcp-text2"><span className="h-1.5 w-1.5 rounded-full bg-gcp-text3" />Not configured</span>
  );
}

const STORED = "•••••••• (stored)";

function LineCard({ config }: { config?: ChannelConfigDto }) {
  const save = useSaveLineChannel();
  const [channelId, setChannelId] = React.useState(config?.lineChannelId ?? "");
  const [secret, setSecret] = React.useState("");
  const [token, setToken] = React.useState("");
  React.useEffect(() => setChannelId(config?.lineChannelId ?? ""), [config?.lineChannelId]);

  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    try {
      await save.mutateAsync({ channelId: channelId.trim(), channelSecret: secret || undefined, channelAccessToken: token || undefined });
      setSecret("");
      setToken("");
      toast("LINE channel saved");
    } catch (err) {
      toast.error(errorMessage(err));
    }
  };

  return (
    <Card>
      <CardHeader title="LINE Messaging API" description={<Configured config={config} />} />
      <form onSubmit={submit}>
        <CardBody className="flex flex-col gap-4">
          <Field label="Channel ID" htmlFor="line-id" required>
            <Input id="line-id" value={channelId} onChange={(e) => setChannelId(e.target.value)} className="font-mono" />
          </Field>
          <Field label="Channel secret" htmlFor="line-secret" required={!config?.hasLineChannelSecret} hint={config?.hasLineChannelSecret ? "Leave blank to keep the stored secret." : undefined}>
            <Input id="line-secret" type="password" autoComplete="off" value={secret} onChange={(e) => setSecret(e.target.value)} placeholder={config?.hasLineChannelSecret ? STORED : ""} />
          </Field>
          <Field label="Channel access token" htmlFor="line-token" required={!config?.hasLineChannelToken} hint={config?.hasLineChannelToken ? "Leave blank to keep the stored token." : "Long-lived token from the LINE Developers console."}>
            <Input id="line-token" type="password" autoComplete="off" value={token} onChange={(e) => setToken(e.target.value)} placeholder={config?.hasLineChannelToken ? STORED : ""} />
          </Field>
          <div className="flex justify-end border-t border-gcp-border pt-4">
            <Button type="submit" loading={save.isPending}>Save</Button>
          </div>
        </CardBody>
      </form>
    </Card>
  );
}

function SmtpCard({ config }: { config?: ChannelConfigDto }) {
  const save = useSaveSmtpChannel();
  const [form, setForm] = React.useState({
    host: config?.smtpHost ?? "", port: String(config?.smtpPort ?? 587), username: config?.smtpUsername ?? "", password: "",
    fromEmail: config?.smtpFromEmail ?? "", fromName: config?.smtpFromName ?? "", sslEnabled: config?.smtpSslEnabled ?? true, testRecipient: "",
  });
  React.useEffect(() => {
    if (!config) return;
    setForm((f) => ({ ...f, host: config.smtpHost ?? "", port: String(config.smtpPort ?? 587), username: config.smtpUsername ?? "", fromEmail: config.smtpFromEmail ?? "", fromName: config.smtpFromName ?? "", sslEnabled: config.smtpSslEnabled }));
  }, [config]);
  const set = (k: keyof typeof form, v: string | boolean) => setForm({ ...form, [k]: v });

  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    try {
      await save.mutateAsync({
        host: form.host.trim(), port: Number(form.port), username: form.username.trim() || undefined,
        password: form.password || undefined, fromEmail: form.fromEmail.trim(), fromName: form.fromName.trim() || undefined,
        sslEnabled: form.sslEnabled, testRecipient: form.testRecipient.trim() || undefined,
      });
      setForm((f) => ({ ...f, password: "" }));
      toast(`Saved. Test email delivered to ${form.testRecipient.trim() || form.fromEmail.trim()}`);
    } catch (err) {
      toast.error(errorMessage(err));
    }
  };

  return (
    <Card>
      <CardHeader title="SMTP (email)" description={<Configured config={config} />} />
      <form onSubmit={submit}>
        <CardBody className="flex flex-col gap-4">
          <div className="grid gap-4 sm:grid-cols-[1fr_120px]">
            <Field label="Host" htmlFor="smtp-host" required><Input id="smtp-host" value={form.host} onChange={(e) => set("host", e.target.value)} placeholder="smtp.example.com" /></Field>
            <Field label="Port" htmlFor="smtp-port" required><Input id="smtp-port" inputMode="numeric" value={form.port} onChange={(e) => set("port", e.target.value)} /></Field>
          </div>
          <div className="grid gap-4 sm:grid-cols-2">
            <Field label="Username" htmlFor="smtp-user" hint="Leave blank for servers without authentication."><Input id="smtp-user" autoComplete="off" value={form.username} onChange={(e) => set("username", e.target.value)} /></Field>
            <Field label="Password" htmlFor="smtp-pass" hint={config?.hasSmtpPassword ? "Leave blank to keep the stored password." : undefined}><Input id="smtp-pass" type="password" autoComplete="new-password" value={form.password} onChange={(e) => set("password", e.target.value)} placeholder={config?.hasSmtpPassword ? STORED : ""} /></Field>
          </div>
          <div className="grid gap-4 sm:grid-cols-2">
            <Field label="From address" htmlFor="smtp-from" required><Input id="smtp-from" type="email" value={form.fromEmail} onChange={(e) => set("fromEmail", e.target.value)} /></Field>
            <Field label="From name" htmlFor="smtp-name"><Input id="smtp-name" value={form.fromName} onChange={(e) => set("fromName", e.target.value)} /></Field>
          </div>
          <label className="flex items-center gap-2 text-sm text-gcp-text">
            <input type="checkbox" className="accent-gcp-blue" checked={form.sslEnabled} onChange={(e) => set("sslEnabled", e.target.checked)} />
            Use TLS (STARTTLS, or implicit TLS on port 465)
          </label>
          <Field label="Send the test email to" htmlFor="smtp-test" hint="Defaults to the from address. Settings are saved only if this email is delivered.">
            <Input id="smtp-test" type="email" value={form.testRecipient} onChange={(e) => set("testRecipient", e.target.value)} placeholder={form.fromEmail || "you@example.com"} />
          </Field>
          <div className="flex justify-end border-t border-gcp-border pt-4">
            <Button type="submit" loading={save.isPending}>Save and send test email</Button>
          </div>
        </CardBody>
      </form>
    </Card>
  );
}

export function ChannelsPage() {
  const { data, isPending } = useChannels();
  if (isPending) return <Spinner />;
  const line = data?.find((c) => c.channelType === "LINE");
  const smtp = data?.find((c) => c.channelType === "EMAIL");
  return (
    <>
      <PageHeader title="Channels" description="Where your messages go out from. Secrets are encrypted at rest and never shown again." />
      <div className="grid gap-6 xl:grid-cols-2">
        <SmtpCard config={smtp} />
        <LineCard config={line} />
      </div>
    </>
  );
}
