/** The API speaks UTC ISO-8601; the console displays Asia/Taipei (UTC+8, no DST). */
export const DISPLAY_TZ = "Asia/Taipei";
const TAIPEI_OFFSET_MINUTES = 8 * 60;

const dateTime = new Intl.DateTimeFormat("en-GB", {
  timeZone: DISPLAY_TZ,
  year: "numeric",
  month: "short",
  day: "2-digit",
  hour: "2-digit",
  minute: "2-digit",
  hour12: false,
});

const dateOnly = new Intl.DateTimeFormat("en-GB", {
  timeZone: DISPLAY_TZ,
  year: "numeric",
  month: "short",
  day: "2-digit",
});

export function formatDateTime(iso?: string | null): string {
  if (!iso) return "—";
  return dateTime.format(new Date(iso));
}

export function formatDate(iso?: string | null): string {
  if (!iso) return "—";
  return dateOnly.format(new Date(iso));
}

export function formatRelative(iso?: string | null): string {
  if (!iso) return "—";
  const diff = Date.now() - new Date(iso).getTime();
  const abs = Math.abs(diff);
  const [unit, size]: [Intl.RelativeTimeFormatUnit, number] =
    abs < 60_000 ? ["second", 1000] : abs < 3_600_000 ? ["minute", 60_000] : abs < 86_400_000 ? ["hour", 3_600_000] : ["day", 86_400_000];
  const value = Math.round(diff / size);
  return new Intl.RelativeTimeFormat("en", { numeric: "auto" }).format(-value, unit);
}

/** `datetime-local` value (interpreted as Taipei wall time) → UTC ISO string for the API. */
export function taipeiLocalToIso(local: string): string {
  const [date = "", time = "00:00"] = local.split("T");
  const [y = 0, m = 1, d = 1] = date.split("-").map(Number);
  const [hh = 0, mm = 0] = time.split(":").map(Number);
  const utcMillis = Date.UTC(y, m - 1, d, hh, mm) - TAIPEI_OFFSET_MINUTES * 60_000;
  return new Date(utcMillis).toISOString();
}

/** UTC ISO → `datetime-local` value in Taipei wall time. */
export function isoToTaipeiLocal(iso: string): string {
  const t = new Date(iso).getTime() + TAIPEI_OFFSET_MINUTES * 60_000;
  return new Date(t).toISOString().slice(0, 16);
}

/** Default suggestion for a schedule picker: the next full hour, Taipei time. */
export function nextHourTaipeiLocal(): string {
  const t = Date.now() + TAIPEI_OFFSET_MINUTES * 60_000;
  const d = new Date(t);
  d.setUTCMinutes(0, 0, 0);
  d.setUTCHours(d.getUTCHours() + 1);
  return d.toISOString().slice(0, 16);
}
