import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";

import { api, qs } from "./client";
import type { ChannelType, MessageTemplateDto, MessageTemplateRequest, Page, TemplatePreviewDto } from "./types";

export const templateKeys = {
  all: ["templates"] as const,
  list: (p: { page: number; size: number; channel?: ChannelType | "" }) => ["templates", "list", p] as const,
  detail: (id: number) => ["templates", id] as const,
};

export function useTemplates(page: number, size: number, channel?: ChannelType | "") {
  return useQuery({
    queryKey: templateKeys.list({ page, size, channel }),
    queryFn: () => api.get<Page<MessageTemplateDto>>(`/templates${qs({ page, size, channel })}`),
    placeholderData: (prev) => prev,
  });
}

export function useTemplate(id: number | null) {
  return useQuery({
    queryKey: templateKeys.detail(id ?? -1),
    queryFn: () => api.get<MessageTemplateDto>(`/templates/${id}`),
    enabled: id !== null,
  });
}

export function useSaveTemplate(id: number | null) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (body: MessageTemplateRequest) =>
      id === null ? api.post<MessageTemplateDto>("/templates", body) : api.put<MessageTemplateDto>(`/templates/${id}`, body),
    onSuccess: () => qc.invalidateQueries({ queryKey: templateKeys.all }),
  });
}

export function useDeleteTemplate() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (id: number) => api.delete<void>(`/templates/${id}`),
    onSuccess: () => qc.invalidateQueries({ queryKey: templateKeys.all }),
  });
}

export function usePreviewTemplate(id: number | null) {
  return useMutation({
    mutationFn: (params: Record<string, string>) => api.post<TemplatePreviewDto>(`/templates/${id}/preview`, { params }),
  });
}

/** `{{name}}` placeholders, in order of first appearance — mirrors the server-side renderer. */
export function extractPlaceholders(...sources: (string | undefined | null)[]): string[] {
  const found: string[] = [];
  const re = /\{\{\s*([A-Za-z0-9_.\-]+)\s*}}/g;
  for (const s of sources) {
    if (!s) continue;
    let m: RegExpExecArray | null;
    while ((m = re.exec(s)) !== null) {
      if (!found.includes(m[1]!)) found.push(m[1]!);
    }
  }
  return found;
}
