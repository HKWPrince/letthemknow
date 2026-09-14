package io.letthemknow.campaign;

import com.fasterxml.jackson.databind.JsonNode;
import io.letthemknow.campaign.dto.CampaignDetailDto;
import io.letthemknow.campaign.dto.CampaignDto;
import io.letthemknow.campaign.dto.CampaignRecipientDto;
import io.letthemknow.campaign.dto.CampaignRequest;
import io.letthemknow.campaign.dto.CampaignStatsDto;
import io.letthemknow.campaign.dto.FailureBreakdownDto;
import io.letthemknow.campaign.dto.RecipientCounts;
import io.letthemknow.channel.ChannelType;
import io.letthemknow.common.ApiException;
import io.letthemknow.common.ErrorCode;
import io.letthemknow.common.PageResponse;
import io.letthemknow.common.tenant.TenantContextHolder;
import io.letthemknow.template.MessageTemplate;
import io.letthemknow.template.MessageTemplateService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

@Service
public class CampaignService {

    private final CampaignRepository campaigns;
    private final CampaignRecipientRepository recipients;
    private final CampaignMapper mapper;
    private final MessageTemplateService templates;

    public CampaignService(CampaignRepository campaigns, CampaignRecipientRepository recipients,
                           CampaignMapper mapper, MessageTemplateService templates) {
        this.campaigns = campaigns;
        this.recipients = recipients;
        this.mapper = mapper;
        this.templates = templates;
    }

    @Transactional
    public CampaignDto create(CampaignRequest request) {
        validate(request);
        Campaign campaign = new Campaign(request.title().trim(), request.channelType(), request.templateId(),
                request.targetAudienceType(), metaJson(request));
        campaign.setScheduledAt(request.scheduledAt());
        return mapper.toDto(campaigns.save(campaign));
    }

    @Transactional
    public CampaignDto update(Long id, CampaignRequest request) {
        Campaign campaign = require(id);
        requireDraft(campaign);
        validate(request);
        if (campaign.getTargetAudienceType() != request.targetAudienceType()
                && recipients.countByCampaignId(id) > 0) {
            throw new ApiException(ErrorCode.CONFLICT, "Remove uploaded recipients before changing the audience type");
        }
        campaign.setTitle(request.title().trim());
        campaign.setChannelType(request.channelType());
        campaign.setTemplateId(request.templateId());
        campaign.setTargetAudienceType(request.targetAudienceType());
        campaign.setTargetAudienceMeta(metaJson(request));
        campaign.setScheduledAt(request.scheduledAt());
        return mapper.toDto(campaigns.save(campaign));
    }

    @Transactional(readOnly = true)
    public CampaignDetailDto get(Long id) {
        Campaign campaign = require(id);
        return new CampaignDetailDto(mapper.toDto(campaign), counts(id));
    }

    @Transactional(readOnly = true)
    public PageResponse<CampaignDto> list(int page, int size, CampaignStatus status) {
        Pageable pageable = PageRequest.of(page, size);
        Page<Campaign> result = status == null
                ? campaigns.findAllByOrderByCreatedAtDesc(pageable)
                : campaigns.findAllByStatusOrderByCreatedAtDesc(status, pageable);
        return PageResponse.from(result, mapper::toDto);
    }

    @Transactional
    public void delete(Long id) {
        Campaign campaign = require(id);
        requireDraft(campaign);
        recipients.deleteByCampaign(id, TenantContextHolder.require());
        campaigns.delete(campaign);
    }

    @Transactional(readOnly = true)
    public PageResponse<CampaignRecipientDto> recipients(Long id, RecipientStatus status, int page, int size) {
        require(id);
        Pageable pageable = PageRequest.of(page, size);
        Page<CampaignRecipient> result = status == null
                ? recipients.findByCampaignIdOrderByIdAsc(id, pageable)
                : recipients.findByCampaignIdAndStatusOrderByIdAsc(id, status, pageable);
        return PageResponse.from(result, mapper::toDto);
    }

    /** FAILED recipients with their error code/message; optionally filtered to one code. */
    @Transactional(readOnly = true)
    public PageResponse<CampaignRecipientDto> failures(Long id, String errorCode, int page, int size) {
        require(id);
        Pageable pageable = PageRequest.of(page, size);
        Page<CampaignRecipient> result = errorCode == null || errorCode.isBlank()
                ? recipients.findByCampaignIdAndStatusOrderByIdAsc(id, RecipientStatus.FAILED, pageable)
                : recipients.findByCampaignIdAndStatusAndErrorCodeOrderByIdAsc(id, RecipientStatus.FAILED,
                        errorCode.trim(), pageable);
        return PageResponse.from(result, mapper::toDto);
    }

    @Transactional(readOnly = true)
    public CampaignStatsDto stats(Long id) {
        Campaign campaign = require(id);
        List<FailureBreakdownDto> breakdown = recipients.countFailuresByErrorCode(id).stream()
                .map(c -> new FailureBreakdownDto(c.getErrorCode(), c.getCount()))
                .toList();
        return new CampaignStatsDto(campaign.getId(), campaign.getStatus(), campaign.getStatus().isProcessing(),
                campaign.getImportStatus(), campaign.getTotalCount(), campaign.getSuccessCount(),
                campaign.getFailedCount(), counts(id), breakdown, campaign.getScheduledAt(),
                campaign.getStartedAt(), campaign.getFinishedAt(), campaign.getUpdatedAt());
    }

    @Transactional(readOnly = true)
    public RecipientCounts counts(Long campaignId) {
        Map<RecipientStatus, Long> byStatus = new EnumMap<>(RecipientStatus.class);
        recipients.countByStatusForCampaign(campaignId).forEach(c -> byStatus.put(c.getStatus(), c.getCount()));
        return new RecipientCounts(
                byStatus.getOrDefault(RecipientStatus.PENDING, 0L),
                byStatus.getOrDefault(RecipientStatus.SENDING, 0L),
                byStatus.getOrDefault(RecipientStatus.SENT, 0L),
                byStatus.getOrDefault(RecipientStatus.FAILED, 0L),
                byStatus.getOrDefault(RecipientStatus.CANCELLED, 0L));
    }

    public Campaign require(Long id) {
        return campaigns.findScopedById(id)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Campaign not found"));
    }

    static void requireDraft(Campaign campaign) {
        if (campaign.getStatus() != CampaignStatus.DRAFT) {
            throw new ApiException(ErrorCode.CONFLICT, "Campaign can only be modified in DRAFT status");
        }
    }

    private void validate(CampaignRequest request) {
        MessageTemplate template = templates.require(request.templateId());
        if (template.getChannelType() != request.channelType()) {
            throw new ApiException(ErrorCode.BAD_REQUEST, "Template channel (" + template.getChannelType()
                    + ") does not match campaign channel (" + request.channelType() + ")");
        }
        if (request.targetAudienceType() == AudienceType.LINE_AUDIENCE_GROUP) {
            if (request.channelType() != ChannelType.LINE) {
                throw new ApiException(ErrorCode.BAD_REQUEST, "LINE_AUDIENCE_GROUP requires the LINE channel");
            }
            JsonNode meta = request.targetAudienceMeta();
            if (meta == null || !meta.isObject() || !meta.path("audienceGroupId").canConvertToLong()
                    || meta.path("audienceGroupId").asLong() <= 0) {
                throw new ApiException(ErrorCode.BAD_REQUEST,
                        "targetAudienceMeta must be {\"audienceGroupId\": <positive number>}");
            }
        }
    }

    private static String metaJson(CampaignRequest request) {
        if (request.targetAudienceType() != AudienceType.LINE_AUDIENCE_GROUP) {
            return null;
        }
        return "{\"audienceGroupId\":" + request.targetAudienceMeta().path("audienceGroupId").asLong() + "}";
    }
}
