import { cn } from "@/lib/utils";

type Tone = "neutral" | "blue" | "green" | "yellow" | "red" | "purple";

const tones: Record<Tone, string> = {
  neutral: "bg-gcp-hover text-gcp-text2",
  blue: "bg-gcp-blueBg text-gcp-blue",
  green: "bg-gcp-greenBg text-gcp-green",
  yellow: "bg-gcp-yellowBg text-gcp-yellowText",
  red: "bg-gcp-redBg text-gcp-red",
  purple: "bg-gcp-purpleBg text-gcp-purple",
};

const dots: Record<Tone, string> = {
  neutral: "bg-gcp-text3",
  blue: "bg-gcp-blue",
  green: "bg-gcp-green",
  yellow: "bg-gcp-yellow",
  red: "bg-gcp-red",
  purple: "bg-gcp-purple",
};

/** Human labels and colours for every backend status, in one place. */
export const STATUS: Record<string, { label: string; tone: Tone; pulse?: boolean }> = {
  DRAFT: { label: "Draft", tone: "neutral" },
  SCHEDULED: { label: "Scheduled", tone: "purple" },
  PROCESSING: { label: "Sending", tone: "blue", pulse: true },
  RETRYING: { label: "Retrying", tone: "blue", pulse: true },
  COMPLETED: { label: "Completed", tone: "green" },
  AWAITING_RESOLUTION: { label: "Needs attention", tone: "yellow" },
  TERMINATED: { label: "Terminated", tone: "neutral" },
  // recipients / messages
  PENDING: { label: "Pending", tone: "neutral" },
  SENDING: { label: "Sending", tone: "blue", pulse: true },
  SENT: { label: "Sent", tone: "green" },
  FAILED: { label: "Failed", tone: "red" },
  CANCELLED: { label: "Cancelled", tone: "neutral" },
  // import
  NONE: { label: "No recipients", tone: "neutral" },
  IMPORTING: { label: "Importing", tone: "blue", pulse: true },
  READY: { label: "Ready", tone: "green" },
  // api keys
  ACTIVE: { label: "Active", tone: "green" },
  REVOKED: { label: "Revoked", tone: "neutral" },
};

export function StatusChip({ status, className }: { status: string; className?: string }) {
  const s = STATUS[status] ?? { label: status, tone: "neutral" as Tone };
  return (
    <span
      className={cn(
        "inline-flex h-6 items-center gap-1.5 rounded-full px-2.5 text-xs font-medium whitespace-nowrap",
        tones[s.tone],
        className,
      )}
    >
      <span className={cn("h-1.5 w-1.5 rounded-full", dots[s.tone], s.pulse && "animate-pulse")} aria-hidden />
      {s.label}
    </span>
  );
}
