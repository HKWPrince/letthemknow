/**
 * Starter templates for email campaigns.
 *
 * Every starter is produced by one `layout()` builder, so the parts that make email look professional —
 * and the parts that break easily — are written once and stay correct:
 *
 *  - 600px centred table layout with inline styles; email clients ignore most external CSS.
 *  - No images at all. SVG is stripped by Gmail and Outlook, and there is nowhere to host a PNG, so the
 *    header is a text wordmark the operator edits to their own company name.
 *  - A hidden preheader, so the inbox list shows a chosen snippet instead of the first sentence.
 *  - A button built from a table cell with `bgcolor`, which survives Outlook (square corners there).
 *  - A `<style>` block for the mobile breakpoint and dark mode; both are progressive, never required.
 *
 * The palette is deliberately neutral — near-black headings, one blue accent — so each tenant can
 * recolour by changing ACCENT and little else.
 */

const ACCENT = "#1a73e8";
const GROUND = "#f4f5f7";
const CARD = "#ffffff";
const HEADING = "#111418";
const BODY = "#4a5057";
const MUTED = "#8b9096";
const BORDER = "#e6e8eb";
const FONT = "-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,Helvetica,Arial,sans-serif";

export interface EmailStarter {
  id: string;
  name: string;
  description: string;
  subject: string;
  html: string;
  text: string;
}

interface Parts {
  company: string;
  preheader: string;
  eyebrow?: string;
  heading: string;
  paragraphs: string[];
  /** Prominent single value, e.g. a verification code. */
  panel?: { label: string; value: string };
  /** Label/value rows, e.g. order metadata. */
  details?: { title?: string; rows: [string, string][] };
  /** Line items with an optional emphasised total row. */
  lineItems?: { rows: [string, string][]; total?: [string, string] };
  /** Numbered steps; only use where order genuinely matters. */
  steps?: string[];
  cta?: { label: string; href: string };
  note?: string;
  signoff?: string;
  footerNote: string;
}

const P = `margin:0 0 16px;font-family:${FONT};font-size:16px;line-height:1.65;color:${BODY};`;

function button(cta: { label: string; href: string }): string {
  return `
              <table role="presentation" cellpadding="0" cellspacing="0" border="0" class="ltk-btn" style="margin:8px 0 4px;">
                <tr>
                  <td align="center" bgcolor="${ACCENT}" style="border-radius:6px;">
                    <a href="${cta.href}" style="display:inline-block;padding:13px 28px;font-family:${FONT};font-size:15px;font-weight:600;line-height:1;color:#ffffff;text-decoration:none;border-radius:6px;">${cta.label}</a>
                  </td>
                </tr>
              </table>`;
}

function detailRows(rows: [string, string][], emphasiseLast = false): string {
  return rows
    .map(([label, value], i) => {
      const last = emphasiseLast && i === rows.length - 1;
      const weight = last ? "600" : "400";
      const color = last ? HEADING : BODY;
      const top = last ? `border-top:1px solid ${BORDER};` : "";
      return `
                  <tr>
                    <td class="ltk-td" style="padding:9px 0;${top}font-family:${FONT};font-size:14px;color:${MUTED};">${label}</td>
                    <td class="ltk-td" align="right" style="padding:9px 0;${top}font-family:${FONT};font-size:14px;font-weight:${weight};color:${color};">${value}</td>
                  </tr>`;
    })
    .join("");
}

function layout(parts: Parts): string {
  const blocks: string[] = [];

  if (parts.eyebrow) {
    blocks.push(
      `<p style="margin:0 0 10px;font-family:${FONT};font-size:12px;font-weight:600;letter-spacing:.8px;text-transform:uppercase;color:${ACCENT};">${parts.eyebrow}</p>`,
    );
  }
  blocks.push(
    `<h1 class="ltk-h" style="margin:0 0 18px;font-family:${FONT};font-size:24px;line-height:1.3;font-weight:700;color:${HEADING};">${parts.heading}</h1>`,
  );
  parts.paragraphs.forEach((p) => blocks.push(`<p class="ltk-p" style="${P}">${p}</p>`));

  if (parts.panel) {
    blocks.push(`
              <table role="presentation" cellpadding="0" cellspacing="0" border="0" width="100%" style="margin:8px 0 20px;">
                <tr>
                  <td class="ltk-panel" align="center" style="padding:20px;background:${GROUND};border-radius:6px;">
                    <p class="ltk-muted" style="margin:0 0 8px;font-family:${FONT};font-size:12px;letter-spacing:.6px;text-transform:uppercase;color:${MUTED};">${parts.panel.label}</p>
                    <p class="ltk-h" style="margin:0;font-family:'SF Mono',Menlo,Consolas,monospace;font-size:30px;font-weight:700;letter-spacing:5px;color:${HEADING};">${parts.panel.value}</p>
                  </td>
                </tr>
              </table>`);
  }

  if (parts.steps) {
    const items = parts.steps
      .map(
        (s, i) => `
                  <tr>
                    <td valign="top" width="30" style="padding:0 0 14px;font-family:${FONT};font-size:14px;font-weight:700;color:${ACCENT};">${i + 1}.</td>
                    <td class="ltk-td" valign="top" style="padding:0 0 14px;font-family:${FONT};font-size:15px;line-height:1.6;color:${BODY};">${s}</td>
                  </tr>`,
      )
      .join("");
    blocks.push(`
              <table role="presentation" cellpadding="0" cellspacing="0" border="0" width="100%" style="margin:4px 0 20px;">${items}
              </table>`);
  }

  if (parts.details) {
    const title = parts.details.title
      ? `<p class="ltk-muted" style="margin:0 0 4px;font-family:${FONT};font-size:12px;font-weight:600;letter-spacing:.6px;text-transform:uppercase;color:${MUTED};">${parts.details.title}</p>`
      : "";
    blocks.push(`
              ${title}
              <table role="presentation" cellpadding="0" cellspacing="0" border="0" width="100%" class="ltk-box" style="margin:6px 0 22px;padding:6px 18px;background:${GROUND};border-radius:6px;">${detailRows(parts.details.rows)}
              </table>`);
  }

  if (parts.lineItems) {
    const rows: [string, string][] = [...parts.lineItems.rows];
    if (parts.lineItems.total) rows.push(parts.lineItems.total);
    blocks.push(`
              <table role="presentation" cellpadding="0" cellspacing="0" border="0" width="100%" style="margin:6px 0 22px;">${detailRows(rows, !!parts.lineItems.total)}
              </table>`);
  }

  if (parts.cta) blocks.push(button(parts.cta));
  if (parts.note) {
    blocks.push(`
              <table role="presentation" cellpadding="0" cellspacing="0" border="0" width="100%" style="margin:20px 0 0;">
                <tr>
                  <td class="ltk-box" style="padding:14px 16px;background:${GROUND};border-radius:6px;font-family:${FONT};font-size:14px;line-height:1.6;color:${BODY};">${parts.note}</td>
                </tr>
              </table>`);
  }
  if (parts.signoff) {
    blocks.push(`<p class="ltk-p" style="${P}margin-top:22px;margin-bottom:0;">${parts.signoff}</p>`);
  }

  return `<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<meta name="x-apple-disable-message-reformatting">
<meta name="color-scheme" content="light dark">
<meta name="supported-color-schemes" content="light dark">
<title>${parts.heading}</title>
<style>
  @media only screen and (max-width:600px) {
    .ltk-wrap { width:100% !important; }
    .ltk-pad { padding:28px 22px !important; }
    .ltk-btn, .ltk-btn td, .ltk-btn a { width:100% !important; display:block !important; text-align:center !important; }
  }
  @media (prefers-color-scheme: dark) {
    .ltk-body { background:#191c1e !important; }
    .ltk-card { background:#24272a !important; border-color:#34383b !important; }
    .ltk-h { color:#f1f3f4 !important; }
    .ltk-p, .ltk-td { color:#c8ccce !important; }
    .ltk-muted { color:#9aa0a6 !important; }
    .ltk-box, .ltk-panel { background:#2c3033 !important; }
  }
</style>
</head>
<body class="ltk-body" style="margin:0;padding:0;background:${GROUND};">
  <div style="display:none;max-height:0;overflow:hidden;opacity:0;mso-hide:all;">${parts.preheader}</div>
  <table role="presentation" cellpadding="0" cellspacing="0" border="0" width="100%" style="background:${GROUND};">
    <tr>
      <td align="center" style="padding:32px 12px;">
        <table role="presentation" cellpadding="0" cellspacing="0" border="0" width="600" class="ltk-wrap" style="width:600px;max-width:600px;">
          <tr>
            <td style="padding:0 4px 16px;font-family:${FONT};font-size:15px;font-weight:700;letter-spacing:.3px;color:${HEADING};" class="ltk-h">${parts.company}</td>
          </tr>
          <tr>
            <td class="ltk-card" style="background:${CARD};border:1px solid ${BORDER};border-radius:10px;">
              <table role="presentation" cellpadding="0" cellspacing="0" border="0" width="100%">
                <tr>
                  <td class="ltk-pad" style="padding:36px 40px;">
              ${blocks.join("\n              ")}
                  </td>
                </tr>
              </table>
            </td>
          </tr>
          <tr>
            <td class="ltk-muted" style="padding:20px 4px 0;font-family:${FONT};font-size:12px;line-height:1.6;color:${MUTED};">
              ${parts.footerNote}<br>
              Sent by ${parts.company}
            </td>
          </tr>
        </table>
      </td>
    </tr>
  </table>
</body>
</html>`;
}

const strip = (s: string) =>
  s.replace(/<br\s*\/?>/gi, "\n").replace(/<[^>]+>/g, "").replace(/&amp;/g, "&").replace(/&nbsp;/g, " ").trim();

function plain(parts: Parts): string {
  const out: string[] = [parts.heading, ""];
  parts.paragraphs.forEach((p) => out.push(strip(p), ""));
  if (parts.panel) out.push(`${parts.panel.label}: ${parts.panel.value}`, "");
  if (parts.steps) {
    parts.steps.forEach((s, i) => out.push(`${i + 1}. ${strip(s)}`));
    out.push("");
  }
  if (parts.details) {
    if (parts.details.title) out.push(parts.details.title);
    parts.details.rows.forEach(([l, v]) => out.push(`${l}: ${strip(v)}`));
    out.push("");
  }
  if (parts.lineItems) {
    parts.lineItems.rows.forEach(([l, v]) => out.push(`${l}  ${strip(v)}`));
    if (parts.lineItems.total) out.push(`${parts.lineItems.total[0]}  ${strip(parts.lineItems.total[1])}`);
    out.push("");
  }
  if (parts.cta) out.push(`${parts.cta.label}: ${parts.cta.href}`, "");
  if (parts.note) out.push(strip(parts.note), "");
  if (parts.signoff) out.push(strip(parts.signoff), "");
  out.push("—", strip(parts.footerNote), `Sent by ${parts.company}`);
  return out.join("\n").replace(/\n{3,}/g, "\n\n");
}

function starter(meta: { id: string; name: string; description: string; subject: string }, parts: Parts): EmailStarter {
  return { ...meta, html: layout(parts), text: plain(parts) };
}

const COMPANY = "Your Company";

const announcement = starter(
  {
    id: "announcement",
    name: "Announcement",
    description: "A product update or news, with one clear action.",
    subject: "Scheduled reports are here",
  },
  {
    company: COMPANY,
    preheader: "Set a report once and have it arrive in your inbox every Monday.",
    eyebrow: "Product update",
    heading: "Scheduled reports are here",
    paragraphs: [
      `Hi {{name}},`,
      `You can now schedule any report to arrive by email — daily, weekly, or on the day of the month you choose. No more exporting the same numbers every Monday morning.`,
      `Existing reports keep working exactly as they do today. Scheduling is opt-in, one report at a time.`,
    ],
    cta: { label: "See what's new", href: "https://example.com/whats-new" },
    signoff: `— The ${COMPANY} team`,
    footerNote: "You are receiving this because you have an account with us.",
  },
);

const orderUpdate = starter(
  {
    id: "order-update",
    name: "Order update",
    description: "Status change with a details table. Good for shipping and fulfilment.",
    subject: "Your order {{order}} has shipped",
  },
  {
    company: COMPANY,
    preheader: "Track your delivery — estimated arrival {{eta}}.",
    eyebrow: "Order update",
    heading: "Your order is on its way",
    paragraphs: [
      `Hi {{name}}, your order has left our warehouse and is now with the carrier.`,
    ],
    details: {
      title: "Shipment details",
      rows: [
        ["Order number", "{{order}}"],
        ["Carrier", "{{carrier}}"],
        ["Tracking number", "{{tracking}}"],
        ["Estimated delivery", "{{eta}}"],
      ],
    },
    cta: { label: "Track your package", href: "{{tracking_url}}" },
    note: `Tracking can take a few hours to show its first scan. If nothing appears by tomorrow, just reply to this email.`,
    footerNote: "You are receiving this because you placed an order with us.",
  },
);

const welcome = starter(
  {
    id: "welcome",
    name: "Welcome",
    description: "Onboarding email with numbered first steps.",
    subject: "Welcome to " + COMPANY + ", {{name}}",
  },
  {
    company: COMPANY,
    preheader: "Three short steps to get your account ready.",
    heading: "Welcome aboard, {{name}}",
    paragraphs: [
      `We are glad you are here. Your account is active and ready to use.`,
      `Here is the quickest way to get value in the first ten minutes:`,
    ],
    steps: [
      `<strong>Finish your profile</strong> so your team knows who is who.`,
      `<strong>Invite a teammate</strong> — most work here happens with at least two people.`,
      `<strong>Connect your data</strong> and your first report builds itself.`,
    ],
    cta: { label: "Open your account", href: "https://example.com/app" },
    signoff: `If you get stuck, reply to this email. A real person reads it.`,
    footerNote: "You are receiving this because you signed up for an account.",
  },
);

const minimal = starter(
  {
    id: "minimal",
    name: "Minimal notice",
    description: "Text-forward, no button. Reads like a note from a colleague.",
    subject: "A quick note about your account",
  },
  {
    company: COMPANY,
    preheader: "A short update about your account — no action needed.",
    heading: "A quick note about your account",
    paragraphs: [
      `Hi {{name}},`,
      `We are making a small change to how {{topic}} works, starting {{date}}. You do not need to do anything — we are telling you before it happens rather than after.`,
      `If this affects a workflow you rely on, reply to this email and we will walk through it with you.`,
    ],
    signoff: `— {{sender}}`,
    footerNote: "You are receiving this because you have an account with us.",
  },
);

const receipt = starter(
  {
    id: "receipt",
    name: "Receipt",
    description: "Payment confirmation with line items and a total.",
    subject: "Your receipt from " + COMPANY + " ({{invoice}})",
  },
  {
    company: COMPANY,
    preheader: "Payment of {{total}} received — thank you.",
    eyebrow: "Receipt",
    heading: "Thanks for your payment",
    paragraphs: [`Hi {{name}}, we received your payment. Here is your receipt for your records.`],
    lineItems: {
      rows: [
        ["{{item_1}}", "{{amount_1}}"],
        ["{{item_2}}", "{{amount_2}}"],
        ["Tax", "{{tax}}"],
      ],
      total: ["Total paid", "{{total}}"],
    },
    details: {
      rows: [
        ["Invoice", "{{invoice}}"],
        ["Date", "{{date}}"],
        ["Payment method", "{{method}}"],
      ],
    },
    cta: { label: "View invoice", href: "{{invoice_url}}" },
    footerNote: "This receipt confirms a payment on your account.",
  },
);

const securityAlert = starter(
  {
    id: "security-alert",
    name: "Security alert",
    description: "Verification code or sign-in notice, with a warning panel.",
    subject: "Your verification code is {{code}}",
  },
  {
    company: COMPANY,
    preheader: "Your code expires in 10 minutes.",
    eyebrow: "Security",
    heading: "Here is your verification code",
    paragraphs: [`Hi {{name}}, enter this code to finish signing in. It expires in 10 minutes.`],
    panel: { label: "Verification code", value: "{{code}}" },
    details: {
      title: "Request details",
      rows: [
        ["Device", "{{device}}"],
        ["Location", "{{location}}"],
        ["Time", "{{time}}"],
      ],
    },
    note: `<strong>Didn't request this?</strong> Someone may have your password. Change it now and turn on two-factor authentication.`,
    footerNote: "We send this email whenever a sign-in needs verification.",
  },
);

export const EMAIL_STARTERS: EmailStarter[] = [
  announcement,
  orderUpdate,
  welcome,
  minimal,
  receipt,
  securityAlert,
];

export const DEFAULT_EMAIL_STARTER = announcement;
