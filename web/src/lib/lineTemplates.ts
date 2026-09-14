/**
 * Starter templates for LINE campaigns.
 *
 * These are real LINE Messaging API message objects. The server accepts an array of 1 to 5 objects, each
 * with a `type` (MessageTemplateService.validatePayload), and substitutes {{placeholders}} into every
 * text value in the tree, however deep.
 *
 * Limits that bite at send time rather than save time, so they are respected here:
 *  - `template`/`buttons`: title ≤ 40 chars, and text ≤ 60 chars when a title is present (160 without).
 *  - Every `template` and `flex` message needs `altText` — that is what appears in the chat list and in
 *    the push notification, and it is the easiest thing to forget.
 */

const ACCENT = "#1a73e8";
const HEADING = "#111418";
const MUTED = "#8b9096";

export interface LineStarter {
  id: string;
  name: string;
  description: string;
  messages: unknown[];
}

const announcement: LineStarter = {
  id: "line-announcement",
  name: "Announcement",
  description: "A plain text notice. The most reliable format — it renders everywhere.",
  messages: [
    {
      type: "text",
      text: "Hi {{name}} 👋\n\nOur store is closed on {{date}} for stocktaking. Orders placed that day ship the next working day.\n\nThanks for your patience.",
    },
  ],
};

const buttonCard: LineStarter = {
  id: "line-buttons",
  name: "Button card",
  description: "Title, a short line, and a tappable button. Keep the text under 60 characters.",
  messages: [
    {
      type: "template",
      altText: "Your order {{order}} has shipped",
      template: {
        type: "buttons",
        title: "Your order has shipped",
        // with a title present LINE caps this at 60 characters
        text: "{{name}}, it arrives around {{eta}}.",
        actions: [
          {
            type: "uri",
            label: "Track package",
            uri: "{{tracking_url}}",
          },
        ],
      },
    },
  ],
};

const orderCard: LineStarter = {
  id: "line-order-card",
  name: "Order card",
  description: "A Flex bubble with order details and a button. The richest layout LINE offers.",
  messages: [
    {
      type: "flex",
      altText: "Order {{order}} confirmed",
      contents: {
        type: "bubble",
        body: {
          type: "box",
          layout: "vertical",
          spacing: "md",
          contents: [
            { type: "text", text: "ORDER CONFIRMED", size: "xs", weight: "bold", color: ACCENT },
            { type: "text", text: "Thanks, {{name}}", size: "xl", weight: "bold", color: HEADING, wrap: true },
            { type: "separator", margin: "lg" },
            {
              type: "box",
              layout: "vertical",
              margin: "lg",
              spacing: "sm",
              contents: [
                {
                  type: "box",
                  layout: "baseline",
                  contents: [
                    { type: "text", text: "Order", size: "sm", color: MUTED, flex: 2 },
                    { type: "text", text: "{{order}}", size: "sm", color: HEADING, flex: 3, align: "end", wrap: true },
                  ],
                },
                {
                  type: "box",
                  layout: "baseline",
                  contents: [
                    { type: "text", text: "Total", size: "sm", color: MUTED, flex: 2 },
                    { type: "text", text: "{{total}}", size: "sm", color: HEADING, flex: 3, align: "end" },
                  ],
                },
                {
                  type: "box",
                  layout: "baseline",
                  contents: [
                    { type: "text", text: "Arrives", size: "sm", color: MUTED, flex: 2 },
                    { type: "text", text: "{{eta}}", size: "sm", color: HEADING, flex: 3, align: "end", wrap: true },
                  ],
                },
              ],
            },
          ],
        },
        footer: {
          type: "box",
          layout: "vertical",
          contents: [
            {
              type: "button",
              style: "primary",
              color: ACCENT,
              height: "sm",
              action: { type: "uri", label: "View order", uri: "{{order_url}}" },
            },
          ],
        },
      },
    },
  ],
};

export const LINE_STARTERS: LineStarter[] = [announcement, buttonCard, orderCard];

export const DEFAULT_LINE_STARTER = announcement;

export function lineStarterJson(starter: LineStarter): string {
  return JSON.stringify(starter.messages, null, 2);
}
