package io.letthemknow.dispatch;

import io.letthemknow.campaign.Campaign;
import io.letthemknow.campaign.CampaignRecipient;
import io.letthemknow.campaign.CampaignRecipientRepository;
import io.letthemknow.campaign.CampaignRepository;
import io.letthemknow.campaign.RecipientStatus;
import io.letthemknow.common.tenant.TenantContextHolder;
import io.letthemknow.template.MessageTemplate;
import io.letthemknow.template.MessageTemplateRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Processes one stream entry end to end. The caller ACKs only after this returns, i.e. after every
 * DB status write. At-least-once without double-send: only rows the {@code claimPending} update
 * actually flipped PENDING→SENDING are sent.
 */
@Component
public class ChunkProcessor {

    private static final Logger log = LoggerFactory.getLogger(ChunkProcessor.class);

    private final CampaignRepository campaigns;
    private final CampaignRecipientRepository recipients;
    private final MessageTemplateRepository templates;
    private final ChannelDispatcher dispatcher;
    private final DispatchPublisher publisher;
    private final CampaignProgressService progress;
    private final DispatchProperties props;
    private final TransactionTemplate tx;

    public ChunkProcessor(CampaignRepository campaigns, CampaignRecipientRepository recipients,
                          MessageTemplateRepository templates, ChannelDispatcher dispatcher,
                          DispatchPublisher publisher, CampaignProgressService progress,
                          DispatchProperties props, PlatformTransactionManager txManager) {
        this.campaigns = campaigns;
        this.recipients = recipients;
        this.templates = templates;
        this.dispatcher = dispatcher;
        this.publisher = publisher;
        this.progress = progress;
        this.props = props;
        this.tx = new TransactionTemplate(txManager);
    }

    /**
     * @param reclaimStale when true (reaper path) rows left in SENDING by a dead consumer are reset to
     *                     PENDING first so they get claimed and sent; rows already SENT/FAILED are untouched.
     */
    public void process(DispatchMessage message, boolean reclaimStale) {
        TenantContextHolder.runAs(message.tenantId(), () -> doProcess(message, reclaimStale));
    }

    private void doProcess(DispatchMessage message, boolean reclaimStale) {
        long tenantId = message.tenantId();
        long campaignId = message.campaignId();
        List<Long> ids = message.recipientIds();

        Campaign campaign = tx.execute(s -> campaigns.findScopedById(campaignId).orElse(null));
        if (campaign == null) {
            log.warn("Dropping chunk for unknown campaign {} (tenant {})", campaignId, tenantId);
            return;
        }
        if (!campaign.getStatus().isProcessing()) {
            int cancelled = tx.execute(s -> recipients.cancelPending(ids, tenantId));
            log.info("Campaign {} is {}; cancelled {} pending recipient(s) of this chunk",
                    campaignId, campaign.getStatus(), cancelled);
            progress.onChunkDone(campaignId);
            return;
        }

        List<CampaignRecipient> claimed = tx.execute(s -> {
            if (reclaimStale) {
                int reset = recipients.resetSending(ids, tenantId);
                if (reset > 0) {
                    log.info("Reaper reset {} SENDING row(s) of campaign {} back to PENDING", reset, campaignId);
                }
            }
            int n = recipients.claimPending(ids, tenantId);
            if (n == 0) {
                return List.of();
            }
            return recipients.findByIdIn(ids).stream().filter(r -> r.getStatus() == RecipientStatus.SENDING).toList();
        });
        if (claimed.isEmpty()) {
            progress.onChunkDone(campaignId);
            return;
        }

        MessageTemplate template = tx.execute(s -> templates.findScopedById(campaign.getTemplateId()).orElse(null));
        Map<Long, SendOutcome> outcomes;
        if (template == null) {
            DispatchError error = DispatchError.terminal("TEMPLATE_MISSING", "Campaign template no longer exists");
            outcomes = new java.util.HashMap<>();
            claimed.forEach(r -> outcomes.put(r.getId(), new SendOutcome.Failed(error)));
        } else {
            outcomes = dispatcher.send(tenantId, campaign.getChannelType(), template, claimed);
        }

        List<Long> retryIds = new ArrayList<>();
        tx.executeWithoutResult(s -> {
            OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
            List<CampaignRecipient> rows = recipients.findByIdIn(claimed.stream().map(CampaignRecipient::getId).toList());
            for (CampaignRecipient row : rows) {
                SendOutcome outcome = outcomes.get(row.getId());
                if (outcome instanceof SendOutcome.Sent sent) {
                    row.setStatus(RecipientStatus.SENT);
                    row.setSentAt(now);
                    row.setExternalMessageId(sent.externalMessageId());
                    row.setErrorCode(null);
                    row.setErrorMessage(null);
                } else if (outcome instanceof SendOutcome.Failed failed) {
                    DispatchError error = failed.error();
                    boolean canRetry = error.isTransient() && message.attempt() + 1 < props.maxAttempts();
                    if (canRetry) {
                        row.setStatus(RecipientStatus.PENDING);
                        row.setErrorCode(error.code());
                        row.setErrorMessage(error.message());
                        retryIds.add(row.getId());
                    } else {
                        row.setStatus(RecipientStatus.FAILED);
                        row.setErrorCode(error.code());
                        row.setErrorMessage(error.isTransient()
                                ? "Gave up after " + props.maxAttempts() + " attempts: " + error.message()
                                : error.message());
                    }
                } else {
                    row.setStatus(RecipientStatus.FAILED);
                    row.setErrorCode("NO_OUTCOME");
                    row.setErrorMessage("Dispatcher produced no result for this recipient");
                }
            }
            recipients.saveAll(rows);
        });

        if (!retryIds.isEmpty()) {
            publisher.enqueueRetry(message.retry(retryIds), props.backoff(message.attempt()));
        }
        progress.onChunkDone(campaignId);
    }
}
