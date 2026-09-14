import * as React from "react";
import { Link } from "react-router-dom";
import { Check, Copy, ExternalLink } from "lucide-react";
import { toast } from "sonner";

import { useApiKeys } from "@/api/apiKeys";
import { useTemplates } from "@/api/templates";
import { cn } from "@/lib/utils";
import { Button } from "@/components/ui/button";
import { Card, CardBody, CardHeader, PageHeader } from "@/components/ui/page";

const LANGUAGES = ["curl", "Node", "Python"] as const;
type Language = (typeof LANGUAGES)[number];

function CodeBlock({ code, className }: { code: string; className?: string }) {
  const [copied, setCopied] = React.useState(false);
  const copy = async () => {
    try {
      await navigator.clipboard.writeText(code);
      setCopied(true);
      setTimeout(() => setCopied(false), 2000);
    } catch {
      toast.error("Clipboard is not available. Select the text and copy it manually.");
    }
  };
  return (
    <div className={cn("relative", className)}>
      <pre className="overflow-x-auto rounded border border-gcp-border bg-[#202124] px-4 py-3 pr-12 font-mono text-[12.5px] leading-relaxed text-[#e8eaed]">
        {code}
      </pre>
      <button
        type="button"
        onClick={copy}
        aria-label="Copy to clipboard"
        className="absolute right-2 top-2 rounded p-1.5 text-[#9aa0a6] hover:bg-white/10 hover:text-white"
      >
        {copied ? <Check className="h-4 w-4 text-gcp-green" /> : <Copy className="h-4 w-4" />}
      </button>
    </div>
  );
}

/** Tabbed snippets. One example, three languages, all sharing the same values. */
function Snippets({ samples }: { samples: Record<Language, string> }) {
  const [lang, setLang] = React.useState<Language>("curl");
  return (
    <div>
      <div className="mb-2 flex rounded border border-gcp-border text-xs" role="tablist" aria-label="Language">
        {LANGUAGES.map((l) => (
          <button
            key={l}
            role="tab"
            aria-selected={lang === l}
            onClick={() => setLang(l)}
            className={cn("px-3 py-1", lang === l ? "bg-gcp-blueBg font-medium text-gcp-blue" : "text-gcp-text2 hover:bg-gcp-hover")}
          >
            {l}
          </button>
        ))}
      </div>
      <CodeBlock code={samples[lang]} />
    </div>
  );
}

const ERRORS: [string, string, string][] = [
  ["200", "Sent, or failed to deliver", "Check data.status. FAILED carries errorCode and errorMessage."],
  ["400", "Bad request", "Malformed JSON, both or neither of templateId/templateName, or the template's channel does not match."],
  ["401", "Bad key", "X-API-KEY missing, malformed or revoked."],
  ["404", "Not found", "No such template, or a message id belonging to another tenant."],
  ["429", "Rate limited", "More than 60 requests in a minute for this key. Carries Retry-After."],
];

export function DevelopersPage() {
  const keys = useApiKeys();
  const templates = useTemplates(0, 50, "EMAIL");

  // In production the API has its own hostname, so integrators must be told that one rather than the
  // console's. Unset in local dev, where same-origin through the Vite proxy is correct.
  const baseUrl = import.meta.env.VITE_PUBLIC_API_URL || `${window.location.origin}/api/v1`;
  const activeKey = keys.data?.find((k) => k.status === "ACTIVE");
  const keyExample = activeKey ? `ltk_${activeKey.prefix}_YOUR_SECRET` : "ltk_XXXXXXXX_YOUR_SECRET";
  const templateName = templates.data?.items[0]?.name ?? "order-shipped";

  const body = JSON.stringify(
    { channel: "EMAIL", templateName, recipient: "ann.chen@example.com", params: { name: "Ann Chen", order: "SO-10482" } },
    null,
    2,
  );

  const send: Record<Language, string> = {
    curl: `curl -X POST ${baseUrl}/integration/push/single \\
  -H 'X-API-KEY: ${keyExample}' \\
  -H 'Content-Type: application/json' \\
  -d '${JSON.stringify({ channel: "EMAIL", templateName, recipient: "ann.chen@example.com", params: { name: "Ann Chen", order: "SO-10482" } })}'`,
    Node: `const res = await fetch("${baseUrl}/integration/push/single", {
  method: "POST",
  headers: {
    "X-API-KEY": "${keyExample}",
    "Content-Type": "application/json",
  },
  body: JSON.stringify(${body.split("\n").join("\n  ")}),
});

const { data } = await res.json();
if (data.status === "FAILED") {
  console.error(data.errorCode, data.errorMessage);
}`,
    Python: `import requests

res = requests.post(
    "${baseUrl}/integration/push/single",
    headers={"X-API-KEY": "${keyExample}"},
    json=${body.replace(/"([^"]+)":/g, '"$1":').split("\n").join("\n    ")},
    timeout=30,
)
data = res.json()["data"]
if data["status"] == "FAILED":
    raise RuntimeError(f"{data['errorCode']}: {data['errorMessage']}")`,
  };

  const lookup: Record<Language, string> = {
    curl: `curl ${baseUrl}/integration/messages/4821 \\
  -H 'X-API-KEY: ${keyExample}'`,
    Node: `const res = await fetch("${baseUrl}/integration/messages/4821", {
  headers: { "X-API-KEY": "${keyExample}" },
});
const { data } = await res.json();`,
    Python: `res = requests.get(
    "${baseUrl}/integration/messages/4821",
    headers={"X-API-KEY": "${keyExample}"},
    timeout=30,
)
data = res.json()["data"]`,
  };

  return (
    <>
      <PageHeader
        title="Developers"
        description="Send messages from your own system. Give this page to whoever writes the integration."
        actions={
          <Button variant="outline" asChild>
            <a href="/api/docs" target="_blank" rel="noreferrer">
              OpenAPI reference <ExternalLink className="h-4 w-4" aria-hidden />
            </a>
          </Button>
        }
      />

      <div className="flex max-w-4xl flex-col gap-6">
        <Card>
          <CardHeader title="Getting started" description="Two things to know before the first call." />
          <CardBody className="flex flex-col gap-4">
            <div>
              <p className="mb-1 text-xs font-medium text-gcp-text2">Base URL</p>
              <CodeBlock code={baseUrl} />
            </div>
            <div>
              <p className="mb-1 text-xs font-medium text-gcp-text2">Authentication</p>
              <p className="mb-2 text-sm text-gcp-text2">
                Send your key in the <span className="gcp-kbd">X-API-KEY</span> header on every request. It works only on{" "}
                <span className="gcp-kbd">/integration/*</span> paths.{" "}
                {activeKey ? (
                  <>Your key starts <span className="gcp-kbd">ltk_{activeKey.prefix}_</span>.</>
                ) : (
                  <>You have no active key yet — <Link to="/api-keys" className="gcp-link">create one</Link>.</>
                )}{" "}
                The secret is shown once at creation and cannot be retrieved later.
              </p>
              <CodeBlock code={`X-API-KEY: ${keyExample}`} />
            </div>
            <p className="text-sm text-gcp-text2">
              Every response is <span className="gcp-kbd">{"{ code, message, data }"}</span>. The HTTP status matches{" "}
              <span className="gcp-kbd">code</span>, and your payload is always under{" "}
              <span className="gcp-kbd">data</span>.
            </p>
          </CardBody>
        </Card>

        <Card>
          <CardHeader
            title="Send one message"
            description={<><span className="gcp-kbd">POST /integration/push/single</span> — renders a template and sends it immediately.</>}
          />
          <CardBody className="flex flex-col gap-4">
            <Snippets samples={send} />
            <div>
              <p className="mb-1 text-xs font-medium text-gcp-text2">Response</p>
              <CodeBlock
                code={`{
  "code": 200,
  "message": "OK",
  "data": {
    "messageId": 4821,
    "status": "SENT",
    "externalMessageId": "<a1b2c3@smtp.example.com>",
    "errorCode": null,
    "errorMessage": null,
    "sentAt": "2026-09-11T04:20:31Z"
  }
}`}
              />
            </div>
            <div className="rounded border border-gcp-yellow bg-gcp-yellowBg px-4 py-3 text-sm text-gcp-text">
              <strong>A delivery failure is still HTTP 200.</strong> If the address bounces or LINE rejects the
              user, you get <span className="gcp-kbd">status: "FAILED"</span> with an{" "}
              <span className="gcp-kbd">errorCode</span>, because the request itself was valid and the attempt is
              recorded. Branch on <span className="gcp-kbd">data.status</span>, not on the HTTP status alone.
            </div>
            <p className="text-sm text-gcp-text2">
              Use <span className="gcp-kbd">templateName</span> or <span className="gcp-kbd">templateId</span>, not
              both. Names are easier to keep stable across environments.{" "}
              <Link to="/templates" className="gcp-link">Your templates</Link> define which{" "}
              <span className="gcp-kbd">{"{{placeholders}}"}</span> go in <span className="gcp-kbd">params</span>;
              anything missing renders empty.
            </p>
          </CardBody>
        </Card>

        <Card>
          <CardHeader
            title="Look up a message"
            description={<><span className="gcp-kbd">GET /integration/messages/{"{id}"}</span> — the status of something you sent earlier.</>}
          />
          <CardBody className="flex flex-col gap-4">
            <Snippets samples={lookup} />
            <p className="text-sm text-gcp-text2">
              Also <span className="gcp-kbd">GET /integration/ping</span>, which returns your tenant and key id. Use it
              to check a key works without sending anything.
            </p>
          </CardBody>
        </Card>

        <Card>
          <CardHeader title="Errors and limits" />
          <div className="overflow-x-auto">
            <table className="gcp-table">
              <thead>
                <tr><th className="w-20">Status</th><th className="w-40">Meaning</th><th>When</th></tr>
              </thead>
              <tbody>
                {ERRORS.map(([code, meaning, when]) => (
                  <tr key={code}>
                    <td className="font-mono font-medium">{code}</td>
                    <td>{meaning}</td>
                    <td className="text-gcp-text2">{when}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          <CardBody>
            <p className="text-sm text-gcp-text2">
              Each key is limited to <strong>60 requests per minute</strong>. A 429 carries{" "}
              <span className="gcp-kbd">Retry-After: 60</span>. Note that some HTTP clients, Apache HttpClient among
              them, honour that header by sleeping and retrying on their own — so a rejection can look like a slow
              success. Disable automatic retries if you need to see the 429 yourself.
            </p>
          </CardBody>
        </Card>
      </div>
    </>
  );
}
