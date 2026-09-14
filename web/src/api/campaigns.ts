import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";

import { api, qs } from "./client";
import type {
  CampaignDetailDto, CampaignDto, CampaignRecipientDto, CampaignRequest, CampaignStatsDto, CampaignStatus,
  Page, RecipientStatus,
} from "./types";
import { PROCESSING_STATUSES } from "./types";

export const campaignKeys = {
  all: ["campaigns"] as const,
  list: (p: { page: number; size: number; status?: CampaignStatus | "" }) => ["campaigns", "list", p] as const,
  detail: (id: number) => ["campaigns", id] as const,
  stats: (id: number) => ["campaigns", id, "stats"] as const,
  recipients: (id: number, p: { status?: RecipientStatus | ""; page: number; size: number }) =>
    ["campaigns", id, "recipients", p] as const,
  failures: (id: number, p: { errorCode?: string; page: number; size: number }) =>
    ["campaigns", id, "failures", p] as const,
};

export function useCampaigns(page: number, size: number, status?: CampaignStatus | "") {
  return useQuery({
    queryKey: campaignKeys.list({ page, size, status }),
    queryFn: () => api.get<Page<CampaignDto>>(`/campaigns${qs({ page, size, status })}`),
    placeholderData: (prev) => prev,
  });
}

export function useCampaign(id: number) {
  return useQuery({
    queryKey: campaignKeys.detail(id),
    queryFn: () => api.get<CampaignDetailDto>(`/campaigns/${id}`),
    refetchInterval: (query) => {
      const c = query.state.data?.campaign;
      if (!c) return false;
      return c.importStatus === "IMPORTING" || PROCESSING_STATUSES.includes(c.status as CampaignStatus) ? 5000 : false;
    },
  });
}

/** Polls every 5 s while the campaign is sending or importing. */
export function useCampaignStats(id: number, enabled = true) {
  return useQuery({
    queryKey: campaignKeys.stats(id),
    queryFn: () => api.get<CampaignStatsDto>(`/campaigns/${id}/stats`),
    enabled,
    refetchInterval: (query) => {
      const s = query.state.data;
      if (!s) return 5000;
      return s.processing || s.importStatus === "IMPORTING" ? 5000 : false;
    },
  });
}

export function useRecipients(id: number, status: RecipientStatus | "", page: number, size = 50) {
  return useQuery({
    queryKey: campaignKeys.recipients(id, { status, page, size }),
    queryFn: () => api.get<Page<CampaignRecipientDto>>(`/campaigns/${id}/recipients${qs({ status, page, size })}`),
    placeholderData: (prev) => prev,
  });
}

export function useFailures(id: number, errorCode: string, page: number, size = 50, enabled = true) {
  return useQuery({
    queryKey: campaignKeys.failures(id, { errorCode, page, size }),
    queryFn: () => api.get<Page<CampaignRecipientDto>>(`/campaigns/${id}/failures${qs({ errorCode, page, size })}`),
    placeholderData: (prev) => prev,
    enabled,
  });
}

function useInvalidate() {
  const qc = useQueryClient();
  return (id?: number) => {
    qc.invalidateQueries({ queryKey: campaignKeys.all });
    if (id !== undefined) qc.invalidateQueries({ queryKey: campaignKeys.detail(id) });
  };
}

export function useCreateCampaign() {
  const invalidate = useInvalidate();
  return useMutation({
    mutationFn: (body: CampaignRequest) => api.post<CampaignDto>("/campaigns", body),
    onSuccess: () => invalidate(),
  });
}

export function useUpdateCampaign(id: number) {
  const invalidate = useInvalidate();
  return useMutation({
    mutationFn: (body: CampaignRequest) => api.put<CampaignDto>(`/campaigns/${id}`, body),
    onSuccess: () => invalidate(id),
  });
}

export function useDeleteCampaign() {
  const invalidate = useInvalidate();
  return useMutation({
    mutationFn: (id: number) => api.delete<void>(`/campaigns/${id}`),
    onSuccess: () => invalidate(),
  });
}

export function useUploadRecipients(id: number) {
  const invalidate = useInvalidate();
  return useMutation({
    mutationFn: (file: File) => {
      const form = new FormData();
      form.append("file", file);
      return api.upload<CampaignDto>(`/campaigns/${id}/upload-recipients`, form);
    },
    onSuccess: () => invalidate(id),
  });
}

export function usePublishCampaign(id: number) {
  const invalidate = useInvalidate();
  return useMutation({
    mutationFn: (scheduledAt?: string | null) =>
      api.post<CampaignDto>(`/campaigns/${id}/publish`, scheduledAt ? { scheduledAt } : {}),
    onSuccess: () => invalidate(id),
  });
}

export function useAbortCampaign(id: number) {
  const invalidate = useInvalidate();
  return useMutation({
    mutationFn: () => api.post<CampaignDto>(`/campaigns/${id}/abort`),
    onSuccess: () => invalidate(id),
  });
}

export function useRetryFailed(id: number) {
  const invalidate = useInvalidate();
  return useMutation({
    mutationFn: () => api.post<CampaignDto>(`/campaigns/${id}/retry-failed`),
    onSuccess: () => invalidate(id),
  });
}
