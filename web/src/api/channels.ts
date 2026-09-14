import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";

import { api } from "./client";
import type { ChannelConfigDto, LineChannelRequest, SmtpChannelRequest } from "./types";

export const channelKeys = { all: ["channels"] as const };

export function useChannels() {
  return useQuery({
    queryKey: channelKeys.all,
    queryFn: () => api.get<ChannelConfigDto[]>("/channels"),
  });
}

export function useSaveLineChannel() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (body: LineChannelRequest) => api.post<ChannelConfigDto>("/channels/line", body),
    onSuccess: () => qc.invalidateQueries({ queryKey: channelKeys.all }),
  });
}

export function useSaveSmtpChannel() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (body: SmtpChannelRequest) => api.post<ChannelConfigDto>("/channels/smtp", body),
    onSuccess: () => qc.invalidateQueries({ queryKey: channelKeys.all }),
  });
}
