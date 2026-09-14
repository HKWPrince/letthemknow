import * as React from "react";

import { cn } from "@/lib/utils";

/**
 * Renders LINE message objects roughly as the LINE app would.
 *
 * Deliberately a subset: text, template/buttons, and the Flex primitives the starters use (bubble, box,
 * text, separator, button). Anything else degrades to a labelled placeholder rather than guessing, so the
 * preview never claims to show something it did not actually render.
 */

type Json = Record<string, unknown>;

const num = (v: unknown, fallback: number) => (typeof v === "number" ? v : fallback);
const str = (v: unknown) => (typeof v === "string" ? v : undefined);

const SIZE: Record<string, string> = {
  xxs: "text-[9px]", xs: "text-[10px]", sm: "text-[11px]", md: "text-[13px]",
  lg: "text-[15px]", xl: "text-[17px]", xxl: "text-[19px]",
};
const MARGIN: Record<string, string> = {
  none: "mt-0", xs: "mt-0.5", sm: "mt-1", md: "mt-2", lg: "mt-3", xl: "mt-4", xxl: "mt-6",
};
const SPACING: Record<string, string> = {
  none: "gap-0", xs: "gap-0.5", sm: "gap-1", md: "gap-2", lg: "gap-3", xl: "gap-4", xxl: "gap-6",
};

function FlexNode({ node }: { node: Json }) {
  const type = str(node.type);

  if (type === "text") {
    return (
      <span
        className={cn(
          SIZE[str(node.size) ?? "md"] ?? SIZE.md,
          MARGIN[str(node.margin) ?? "none"],
          str(node.weight) === "bold" && "font-semibold",
          str(node.align) === "end" && "text-right",
          // LINE clips to one line unless wrap is set
          !node.wrap && "truncate",
          "block leading-snug",
        )}
        style={{ color: str(node.color) ?? "#111418", flex: node.flex !== undefined ? num(node.flex, 1) : undefined }}
      >
        {str(node.text)}
      </span>
    );
  }

  if (type === "separator") {
    return <div className={cn("border-t border-gcp-border", MARGIN[str(node.margin) ?? "none"])} />;
  }

  if (type === "button") {
    const action = (node.action ?? {}) as Json;
    const primary = str(node.style) === "primary";
    return (
      <div
        className={cn(
          "grid place-items-center rounded px-3 py-1.5 text-[12px] font-medium",
          MARGIN[str(node.margin) ?? "none"],
          primary ? "text-white" : "border border-gcp-border text-gcp-text",
        )}
        style={primary ? { background: str(node.color) ?? "#1a73e8" } : undefined}
      >
        {str(action.label) ?? "Button"}
      </div>
    );
  }

  if (type === "box") {
    const horizontal = str(node.layout) === "horizontal" || str(node.layout) === "baseline";
    const contents = Array.isArray(node.contents) ? (node.contents as Json[]) : [];
    return (
      <div
        className={cn(
          "flex",
          horizontal ? "flex-row items-baseline" : "flex-col",
          SPACING[str(node.spacing) ?? "none"],
          MARGIN[str(node.margin) ?? "none"],
        )}
      >
        {contents.map((c, i) => <FlexNode key={i} node={c} />)}
      </div>
    );
  }

  return <span className="text-[11px] text-gcp-text3">[{type ?? "unknown"}]</span>;
}

function Bubble({ children, className }: { children: React.ReactNode; className?: string }) {
  return (
    <div className={cn("max-w-[260px] overflow-hidden rounded-2xl rounded-tl-sm bg-white shadow-sm", className)}>
      {children}
    </div>
  );
}

function Message({ message }: { message: Json }) {
  const type = str(message.type);

  if (type === "text") {
    return (
      <div className="max-w-[260px] whitespace-pre-wrap rounded-2xl rounded-tl-sm bg-white px-3.5 py-2 text-[13px] leading-relaxed text-gcp-text shadow-sm">
        {str(message.text)}
      </div>
    );
  }

  if (type === "template") {
    const t = (message.template ?? {}) as Json;
    const actions = Array.isArray(t.actions) ? (t.actions as Json[]) : [];
    return (
      <Bubble>
        <div className="px-3.5 py-3">
          {str(t.title) && <p className="text-[14px] font-semibold text-gcp-text">{str(t.title)}</p>}
          {str(t.text) && <p className="mt-1 text-[12.5px] leading-snug text-gcp-text2">{str(t.text)}</p>}
        </div>
        <div className="border-t border-gcp-border">
          {actions.map((a, i) => (
            <div key={i} className="border-b border-gcp-border px-3 py-2 text-center text-[12.5px] font-medium text-gcp-blue last:border-b-0">
              {str(a.label) ?? "Action"}
            </div>
          ))}
        </div>
      </Bubble>
    );
  }

  if (type === "flex") {
    const contents = (message.contents ?? {}) as Json;
    if (str(contents.type) !== "bubble") {
      return <Bubble><div className="px-3.5 py-3 text-[12px] text-gcp-text3">[flex {str(contents.type) ?? "?"}]</div></Bubble>;
    }
    const body = contents.body as Json | undefined;
    const footer = contents.footer as Json | undefined;
    return (
      <Bubble>
        {body && <div className="px-3.5 py-3"><FlexNode node={body} /></div>}
        {footer && <div className="border-t border-gcp-border px-3.5 py-2.5"><FlexNode node={footer} /></div>}
      </Bubble>
    );
  }

  return (
    <div className="max-w-[260px] rounded-2xl rounded-tl-sm bg-white px-3.5 py-2 text-[12px] text-gcp-text3 shadow-sm">
      [{type ?? "unknown"}{str(message.altText) ? `: ${str(message.altText)}` : ""}]
    </div>
  );
}

/** LINE's chat background, so the bubbles read the way they will on a phone. */
export function LineMessagePreview({ messages, className }: { messages: unknown[]; className?: string }) {
  return (
    <div className={cn("flex flex-col gap-2 bg-[#8cabd8] p-4", className)}>
      {messages.map((m, i) => <Message key={i} message={(m ?? {}) as Json} />)}
    </div>
  );
}
