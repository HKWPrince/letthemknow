package io.letthemknow.campaign;

import io.letthemknow.campaign.dto.CampaignDto;
import io.letthemknow.channel.ChannelConfigRepository;
import io.letthemknow.channel.ChannelType;
import io.letthemknow.common.ApiException;
import io.letthemknow.common.ErrorCode;
import io.letthemknow.common.tenant.TenantContextHolder;
import io.letthemknow.dispatch.CampaignProgressService;
import io.letthemknow.dispatch.CampaignScheduler;
import io.letthemknow.dispatch.DispatchPublisher;
import io.letthemknow.dispatch.NarrowcastService;
import io.letthemknow.template.MessageTemplateRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

/**
 * Lifecycle operations: publish (now or scheduled), start, abort, retry-failed. Status changes go
 * through {@link CampaignStateMachine}; stream/narrowcast side effects run after the DB commit.
 */
@Service
public class CampaignRunner {

    private static final Logger log = LoggerFactory.getLogger(CampaignRunner.class);

    private final CampaignRepository campaigns;
    private final CampaignRecipientRepository recipients;
    private final MessageTemplateRepository templates;
    private final ChannelConfigRepository channels;
    private final CampaignStateMachine stateMachine;
    private final CampaignMapper mapper;
    private final DispatchPublisher publisher;
    private final CampaignScheduler scheduler;
    private final NarrowcastService narrowcast;
    private final CampaignProgressService progress;

    public CampaignRunner(CampaignRepository campaigns, CampaignRecipientRepository recipients,
                          MessageTemplateRepository templates, ChannelConfigRepository channels,
                          CampaignStateMachine stateMachine, CampaignMapper mapper, DispatchPublisher publisher,
                          CampaignScheduler scheduler, NarrowcastService narrowcast, CampaignProgressService progress) {
        this.campaigns = campaigns;
        this.recipients = recipients;
        this.templates = templates;
        this.channels = channels;
        this.stateMachine = stateMachine;
        this.mapper = mapper;
        this.publisher = publisher;
        this.scheduler = scheduler;
        this.narrowcast = narrowcast;
        this.progress = progress;
    }

    /** DRAFT → SCHEDULED (future {@code scheduledAt}) or straight to PROCESSING. */
    @Transactional
    public CampaignDto publish(Long campaignId, OffsetDateTime scheduledAt) {
        Campaign campaign = require(campaignId);
        validateReady(campaign);
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        if (scheduledAt != null && scheduledAt.isAfter(now)) {
            stateMachine.transition(campaign, CampaignEvent.SCHEDULE);
            campaign.setScheduledAt(scheduledAt);
            Campaign saved = campaigns.save(campaign);
            afterCommit(() -> scheduler.schedule(campaignId, scheduledAt));
            return mapper.toDto(saved);
        }
        campaign.setScheduledAt(null);
        return mapper.toDto(startNow(campaign));
    }

    /** Called by the schedule poller (tenant context bound). Ignores campaigns no longer startable. */
    @Transactional
    public void start(Long campaignId) {
        Campaign campaign = require(campaignId);
        if (campaign.getStatus() != CampaignStatus.SCHEDULED && campaign.getStatus() != CampaignStatus.DRAFT) {
            log.info("Campaign {} is {}; start ignored", campaignId, campaign.getStatus());
            return;
        }
        validateReady(campaign);
        startNow(campaign);
    }

    @Transactional
    public CampaignDto abort(Long campaignId) {
        long tenantId = TenantContextHolder.require();
        Campaign campaign = require(campaignId);
        CampaignStatus before = campaign.getStatus();
        stateMachine.transition(campaign, CampaignEvent.TERMINATE);
        int cancelled = recipients.transitionAll(campaignId, RecipientStatus.PENDING, RecipientStatus.CANCELLED, tenantId);
        campaigns.refreshCounts(campaignId, tenantId);
        Campaign saved = campaigns.save(campaign);
        if (before == CampaignStatus.SCHEDULED) {
            afterCommit(() -> scheduler.cancel(campaignId));
        }
        log.info("Campaign {} aborted from {} ({} pending recipient(s) cancelled)", campaignId, before, cancelled);
        return mapper.toDto(saved);
    }

    /** AWAITING_RESOLUTION → RETRYING: FAILED rows back to PENDING (+1 retry_count) and re-chunked. */
    @Transactional
    public CampaignDto retryFailed(Long campaignId) {
        long tenantId = TenantContextHolder.require();
        Campaign campaign = require(campaignId);
        stateMachine.transition(campaign, CampaignEvent.RETRY);
        Campaign saved = campaigns.save(campaign);
        if (campaign.getTargetAudienceType() == AudienceType.LINE_AUDIENCE_GROUP) {
            afterCommit(() -> TenantContextHolder.runAs(tenantId, () -> narrowcast.launch(campaignId)));
            return mapper.toDto(saved);
        }
        int reset = recipients.resetFailedToPending(campaignId, tenantId);
        campaigns.refreshCounts(campaignId, tenantId);
        List<Long> ids = recipients.findIdsByCampaignIdAndStatus(campaignId, RecipientStatus.PENDING);
        log.info("Campaign {} retrying {} failed recipient(s)", campaignId, reset);
        afterCommit(() -> TenantContextHolder.runAs(tenantId, () -> {
            if (ids.isEmpty()) {
                progress.finalise(campaignId);
            } else {
                publisher.enqueueChunks(tenantId, campaignId, campaign.getChannelType(), ids);
            }
        }));
        return mapper.toDto(saved);
    }

    private Campaign startNow(Campaign campaign) {
        long tenantId = TenantContextHolder.require();
        long campaignId = campaign.getId();
        stateMachine.transition(campaign, CampaignEvent.START);
        if (campaign.getTargetAudienceType() == AudienceType.CSV_LIST) {
            campaign.setTotalCount((int) recipients.countByCampaignId(campaignId));
            campaign.setSuccessCount(0);
            campaign.setFailedCount(0);
            Campaign saved = campaigns.save(campaign);
            List<Long> ids = recipients.findIdsByCampaignIdAndStatus(campaignId, RecipientStatus.PENDING);
            ChannelType channel = campaign.getChannelType();
            afterCommit(() -> TenantContextHolder.runAs(tenantId, () -> {
                if (ids.isEmpty()) {
                    progress.finalise(campaignId);
                } else {
                    publisher.enqueueChunks(tenantId, campaignId, channel, ids);
                }
            }));
            return saved;
        }
        Campaign saved = campaigns.save(campaign);
        afterCommit(() -> TenantContextHolder.runAs(tenantId, () -> narrowcast.launch(campaignId)));
        return saved;
    }

    private void validateReady(Campaign campaign) {
        if (campaign.getStatus() != CampaignStatus.DRAFT && campaign.getStatus() != CampaignStatus.SCHEDULED) {
            throw new IllegalStateTransitionException(campaign.getStatus(), CampaignEvent.START);
        }
        if (campaign.getTemplateId() == null || templates.findScopedById(campaign.getTemplateId()).isEmpty()) {
            throw new ApiException(ErrorCode.UNPROCESSABLE, "Campaign template is missing");
        }
        if (channels.findByChannelType(campaign.getChannelType()).isEmpty()) {
            throw new ApiException(ErrorCode.UNPROCESSABLE,
                    campaign.getChannelType() + " channel is not configured for this tenant");
        }
        if (campaign.getTargetAudienceType() == AudienceType.CSV_LIST) {
            if (campaign.getImportStatus() == ImportStatus.IMPORTING) {
                throw new ApiException(ErrorCode.CONFLICT, "Recipient import is still running");
            }
            if (recipients.countByCampaignId(campaign.getId()) == 0) {
                throw new ApiException(ErrorCode.UNPROCESSABLE, "Campaign has no recipients; upload a CSV first");
            }
        } else if (campaign.getChannelType() != ChannelType.LINE) {
            throw new ApiException(ErrorCode.UNPROCESSABLE, "Audience-group campaigns require the LINE channel");
        }
    }

    private Campaign require(Long id) {
        return campaigns.findScopedById(id)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Campaign not found"));
    }

    private static void afterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    action.run();
                }
            });
        } else {
            action.run();
        }
    }
}
