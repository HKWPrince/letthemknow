/** DTO types come from the generated OpenAPI schema; enums are pinned here so switch statements are exhaustive. */
import type { components } from "./schema";

/**
 * springdoc does not emit `required`, so every generated field is optional. The API serialises every
 * field of every record (`default-property-inclusion: always`), so treating them as present is accurate;
 * fields that can be null at runtime are handled by the formatting helpers.
 */
type DeepRequired<T> = T extends (infer U)[]
  ? DeepRequired<U>[]
  : T extends object
    ? { [K in keyof T]-?: DeepRequired<T[K]> }
    : T;

type S = { [K in keyof components["schemas"]]: DeepRequired<components["schemas"][K]> };

export type CampaignStatus =
  | "DRAFT" | "SCHEDULED" | "PROCESSING" | "COMPLETED" | "AWAITING_RESOLUTION" | "RETRYING" | "TERMINATED";
export type RecipientStatus = "PENDING" | "SENDING" | "SENT" | "FAILED" | "CANCELLED";
export type ImportStatus = "NONE" | "IMPORTING" | "READY" | "FAILED";
export type ChannelType = "EMAIL" | "LINE";
export type AudienceType = "CSV_LIST" | "LINE_AUDIENCE_GROUP";
export type ApiKeyStatus = "ACTIVE" | "REVOKED";

export const PROCESSING_STATUSES: CampaignStatus[] = ["PROCESSING", "RETRYING"];
export const FINAL_STATUSES: CampaignStatus[] = ["COMPLETED", "TERMINATED"];

export type CampaignDto = S["CampaignDto"];
export type CampaignDetailDto = S["CampaignDetailDto"];
export type CampaignStatsDto = S["CampaignStatsDto"];
export type CampaignRecipientDto = S["CampaignRecipientDto"];
export type RecipientCounts = S["RecipientCounts"];
export type FailureBreakdownDto = S["FailureBreakdownDto"];
export type MessageTemplateDto = S["MessageTemplateDto"];
export type TemplatePreviewDto = S["TemplatePreviewDto"];
export type ChannelConfigDto = S["ChannelConfigDto"];

/** Request bodies keep their optional fields; JSON payloads are free-form objects. */
export interface CampaignRequest {
  title: string;
  channelType: ChannelType;
  templateId: number;
  targetAudienceType: AudienceType;
  targetAudienceMeta?: Record<string, unknown>;
  scheduledAt?: string | null;
}

export interface MessageTemplateRequest {
  name: string;
  channelType: ChannelType;
  subjectTemplate?: string;
  contentPayload: Record<string, unknown>;
}

export interface LineChannelRequest {
  channelId: string;
  channelSecret?: string;
  channelAccessToken?: string;
}

export interface SmtpChannelRequest {
  host: string;
  port: number;
  username?: string;
  password?: string;
  fromEmail: string;
  fromName?: string;
  sslEnabled?: boolean;
  testRecipient?: string;
}
export type ApiKeyDto = S["ApiKeyDto"];
export type ApiKeyCreatedDto = S["ApiKeyCreatedDto"];
export type LoginResponse = S["LoginResponse"];
export type MeResponse = S["MeResponse"];
export type UserDto = S["UserDto"];
export type TenantDto = S["TenantDto"];

/** PageResponse<T> as the API returns it (springdoc names each instantiation separately). */
export interface Page<T> {
  items: T[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}

export const CHANNEL_LABEL: Record<ChannelType, string> = { EMAIL: "Email", LINE: "LINE" };
export const AUDIENCE_LABEL: Record<AudienceType, string> = {
  CSV_LIST: "Recipient list (CSV)",
  LINE_AUDIENCE_GROUP: "LINE audience group",
};
