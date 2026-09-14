import type { ChannelType } from "@/api/types";

/**
 * Builds the sample recipient file we hand to operators.
 *
 * The rows must satisfy the server's own rules in CsvRecipientParser, or the sample would teach the
 * wrong format: emails match `^[^\s@]+@[^\s@]+\.[^\s@]+$`, and LINE IDs match `^U[0-9a-f]{32}$` —
 * lowercase hex only. Anything else is silently skipped on import.
 */

const SAMPLE_EMAILS = ["ann.chen@example.com", "bob.lin@example.com", "cara.wu@example.com"];

// U + exactly 32 lowercase hex characters
const SAMPLE_LINE_IDS = [
  "U4af4980a1b2c3d4e5f60718293a4b5c6",
  "U8bc5091d2e3f4a5b6c7d8e9f0a1b2c3d",
  "Uc1d2e3f4a5b60718293a4b5c6d7e8f90",
];

/** Plausible values beat three identical dummies: the file doubles as a format example. */
const BY_NAME: { match: RegExp; values: [string, string, string] }[] = [
  { match: /^(name|first_?name|customer|recipient_?name)$/i, values: ["Ann Chen", "Bob Lin", "Cara Wu"] },
  { match: /(order|invoice|reference|ref)/i, values: ["SO-10482", "SO-10483", "SO-10484"] },
  { match: /(tracking)/i, values: ["KE882931045TW", "KE882931046TW", "KE882931047TW"] },
  { match: /(code|otp|token)/i, values: ["418205", "730164", "952037"] },
  { match: /(url|link)/i, values: ["https://example.com/a", "https://example.com/b", "https://example.com/c"] },
  { match: /(amount|total|price|tax|subtotal)/i, values: ["NT$2,400", "NT$1,150", "NT$860"] },
  { match: /(date|eta|due|expires)/i, values: ["2026-09-15", "2026-09-16", "2026-09-17"] },
  { match: /(company|brand|org)/i, values: ["Acme Co", "Beta Ltd", "Ceres Inc"] },
  { match: /(carrier|courier|method)/i, values: ["Kerry Express", "Black Cat", "Post Office"] },
];

function valuesFor(placeholder: string): [string, string, string] {
  const hit = BY_NAME.find((r) => r.match.test(placeholder));
  if (hit) return hit.values;
  return [`${placeholder} 1`, `${placeholder} 2`, `${placeholder} 3`];
}

/** RFC 4180: quote when the value contains a comma, quote or newline, and double any inner quotes. */
function cell(value: string): string {
  return /[",\n\r]/.test(value) ? `"${value.replace(/"/g, '""')}"` : value;
}

export function sampleColumns(placeholders: string[]): string[] {
  return ["recipient", ...placeholders];
}

export function buildSampleCsv(channel: ChannelType, placeholders: string[]): string {
  const recipients = channel === "EMAIL" ? SAMPLE_EMAILS : SAMPLE_LINE_IDS;
  const columns = sampleColumns(placeholders);
  const perPlaceholder = placeholders.map(valuesFor);

  const lines = [columns.map(cell).join(",")];
  for (let row = 0; row < 3; row++) {
    lines.push([recipients[row]!, ...perPlaceholder.map((v) => v[row]!)].map(cell).join(","));
  }
  // Leading BOM so Excel reads UTF-8 (and Chinese) correctly. The importer takes the recipient column
  // positionally, so a BOM stuck to the first header name cannot break it.
  return "﻿" + lines.join("\r\n") + "\r\n";
}

export function downloadTextFile(filename: string, content: string, mime = "text/csv;charset=utf-8") {
  const url = URL.createObjectURL(new Blob([content], { type: mime }));
  const a = document.createElement("a");
  a.href = url;
  a.download = filename;
  document.body.appendChild(a);
  a.click();
  a.remove();
  URL.revokeObjectURL(url);
}
