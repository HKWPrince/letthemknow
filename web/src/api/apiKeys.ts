import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";

import { api } from "./client";
import type { ApiKeyCreatedDto, ApiKeyDto } from "./types";

export const apiKeyKeys = { all: ["api-keys"] as const };

export function useApiKeys() {
  return useQuery({
    queryKey: apiKeyKeys.all,
    queryFn: () => api.get<ApiKeyDto[]>("/api-keys"),
  });
}

export function useCreateApiKey() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (name: string) => api.post<ApiKeyCreatedDto>("/api-keys", { name }),
    onSuccess: () => qc.invalidateQueries({ queryKey: apiKeyKeys.all }),
  });
}

export function useRevokeApiKey() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (id: number) => api.delete<ApiKeyDto>(`/api-keys/${id}`),
    onSuccess: () => qc.invalidateQueries({ queryKey: apiKeyKeys.all }),
  });
}
