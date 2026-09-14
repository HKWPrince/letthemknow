import * as React from "react";
import { Link } from "react-router-dom";
import { Check, Copy, KeyRound } from "lucide-react";
import { toast } from "sonner";

import { useApiKeys, useCreateApiKey, useRevokeApiKey } from "@/api/apiKeys";
import { errorMessage } from "@/api/client";
import type { ApiKeyCreatedDto, ApiKeyDto } from "@/api/types";
import { formatDateTime, formatRelative } from "@/lib/time";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Field } from "@/components/ui/field";
import { StatusChip } from "@/components/ui/status-chip";
import { ConfirmDialog, Dialog, DialogClose, DialogContent } from "@/components/ui/dialog";
import { Card, EmptyState, PageHeader, Spinner } from "@/components/ui/page";

function CreatedKeyDialog({ created, onClose }: { created: ApiKeyCreatedDto | null; onClose: () => void }) {
  const [copied, setCopied] = React.useState(false);
  const copy = async () => {
    try {
      await navigator.clipboard.writeText(created!.key);
      setCopied(true);
      setTimeout(() => setCopied(false), 2000);
    } catch {
      toast.error("Clipboard is not available; select the key and copy it manually.");
    }
  };
  return (
    <Dialog open={!!created} onOpenChange={(o) => !o && onClose()}>
      <DialogContent title={`Key "${created?.apiKey.name}" created`} description="Copy it now. For security it is shown only once and cannot be retrieved later.">
        <div className="flex items-center gap-2 rounded border border-gcp-border bg-gcp-ground px-3 py-2">
          <code className="flex-1 break-all font-mono text-xs text-gcp-text" data-testid="api-key-plaintext">{created?.key}</code>
          <Button variant="ghost" size="iconSm" onClick={copy} aria-label="Copy key">
            {copied ? <Check className="h-4 w-4 text-gcp-green" /> : <Copy className="h-4 w-4" />}
          </Button>
        </div>
        <p className="mt-3 text-xs text-gcp-text2">
          Send it as the <span className="gcp-kbd">X-API-KEY</span> header on <span className="gcp-kbd">/api/v1/integration/*</span> requests.
          Limit: 60 requests per minute. <Link to="/developers" className="gcp-link">Developer docs</Link> has
          ready-made curl, Node and Python examples.
        </p>
        <div className="mt-6 flex justify-end">
          <DialogClose asChild><Button>Done</Button></DialogClose>
        </div>
      </DialogContent>
    </Dialog>
  );
}

export function ApiKeysPage() {
  const { data, isPending } = useApiKeys();
  const create = useCreateApiKey();
  const revoke = useRevokeApiKey();
  const [createOpen, setCreateOpen] = React.useState(false);
  const [name, setName] = React.useState("");
  const [created, setCreated] = React.useState<ApiKeyCreatedDto | null>(null);
  const [revoking, setRevoking] = React.useState<ApiKeyDto | null>(null);

  const doCreate = async (e: React.FormEvent) => {
    e.preventDefault();
    try {
      const res = await create.mutateAsync(name.trim());
      setCreateOpen(false);
      setName("");
      setCreated(res);
    } catch (err) {
      toast.error(errorMessage(err));
    }
  };

  const doRevoke = async () => {
    if (!revoking) return;
    try {
      await revoke.mutateAsync(revoking.id!);
      toast(`Key "${revoking.name}" revoked`);
      setRevoking(null);
    } catch (err) {
      toast.error(errorMessage(err));
    }
  };

  return (
    <>
      <PageHeader
        title="API keys"
        description="Credentials for ERP and other systems to send single messages through the integration API."
        actions={
          <>
            <Button variant="outline" asChild><Link to="/developers">Developer docs</Link></Button>
            <Button onClick={() => setCreateOpen(true)}>Create key</Button>
          </>
        }
      />
      <Card>
        {isPending ? (
          <Spinner />
        ) : !data || data.length === 0 ? (
          <EmptyState icon={<KeyRound className="h-10 w-10" />} title="No API keys" description="Create a key to let another system send messages on this tenant's behalf." action={<Button onClick={() => setCreateOpen(true)}>Create key</Button>} />
        ) : (
          <div className="overflow-x-auto">
            <table className="gcp-table">
              <thead>
                <tr><th>Name</th><th>Key</th><th>Status</th><th>Last used</th><th>Created</th><th></th></tr>
              </thead>
              <tbody>
                {data.map((k) => (
                  <tr key={k.id}>
                    <td className="font-medium text-gcp-text">{k.name}</td>
                    <td className="font-mono text-xs text-gcp-text2">ltk_{k.prefix}_••••••••</td>
                    <td><StatusChip status={k.status} /></td>
                    <td className="text-gcp-text2">{k.lastUsedAt ? formatRelative(k.lastUsedAt) : "Never"}</td>
                    <td className="text-gcp-text2">{formatDateTime(k.createdAt)}</td>
                    <td className="text-right">
                      {k.status === "ACTIVE" && <Button variant="dangerText" size="sm" onClick={() => setRevoking(k)}>Revoke</Button>}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </Card>

      <Dialog open={createOpen} onOpenChange={setCreateOpen}>
        <DialogContent title="Create API key" description="Name it after the system that will use it.">
          <form onSubmit={doCreate} className="flex flex-col gap-4">
            <Field label="Name" htmlFor="key-name" required>
              <Input id="key-name" value={name} onChange={(e) => setName(e.target.value)} placeholder="ERP production" maxLength={100} autoFocus required />
            </Field>
            <div className="flex justify-end gap-2">
              <DialogClose asChild><Button type="button" variant="text">Cancel</Button></DialogClose>
              <Button type="submit" loading={create.isPending}>Create</Button>
            </div>
          </form>
        </DialogContent>
      </Dialog>
      <CreatedKeyDialog created={created} onClose={() => setCreated(null)} />
      <ConfirmDialog open={!!revoking} onOpenChange={(o) => !o && setRevoking(null)} title={`Revoke "${revoking?.name}"?`} description="Requests using this key are rejected immediately. This cannot be undone." confirmLabel="Revoke" danger loading={revoke.isPending} onConfirm={doRevoke} />
    </>
  );
}
