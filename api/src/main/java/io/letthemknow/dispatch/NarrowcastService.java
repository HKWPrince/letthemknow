package io.letthemknow.dispatch;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.letthemknow.campaign.Campaign;
import io.letthemknow.campaign.CampaignEvent;
import io.letthemknow.campaign.CampaignRepository;
import io.letthemknow.campaign.CampaignStateMachine;
import io.letthemknow.channel.line.LineMessages;
import io.letthemknow.channel.line.LineSender;
import io.letthemknow.common.tenant.TenantContextHolder;
import io.letthemknow.template.MessageTemplate;
import io.letthemknow.template.MessageTemplateRepository;
import io.letthemknow.template.MessageTemplateService;
import io.letthemknow.template.RenderedMessage;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * LINE_AUDIENCE_GROUP campaigns: one narrowcast request; the request id and any error are kept in
 * {@code target_audience_meta} ({@code narrowcastRequestId}, {@code narrowcastError}). Progress is
 * applied by {@link NarrowcastProgressPoller}.
 */
@Service
public class NarrowcastService {

    public static final String META_REQUEST_ID = "narrowcastRequestId";
    public static final String META_ERROR = "narrowcastError";
    private static final Logger log = LoggerFactory.getLogger(NarrowcastService.class);

    private final CampaignRepository campaigns;
    private final MessageTemplateRepository templates;
    private final MessageTemplateService templateService;
    private final LineSender lineSender;
    private final CampaignStateMachine stateMachine;
    private final DispatchErrorClassifier classifier;
    private final RedissonClient redisson;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate tx;

    public NarrowcastService(CampaignRepository campaigns, MessageTemplateRepository templates,
                             MessageTemplateService templateService, LineSender lineSender,
                             CampaignStateMachine stateMachine, DispatchErrorClassifier classifier,
                             RedissonClient redisson, ObjectMapper objectMapper, PlatformTransactionManager txManager) {
        this.campaigns = campaigns;
        this.templates = templates;
        this.templateService = templateService;
        this.lineSender = lineSender;
        this.stateMachine = stateMachine;
        this.classifier = classifier;
        this.redisson = redisson;
        this.objectMapper = objectMapper;
        this.tx = new TransactionTemplate(txManager);
    }

    /** Issues the narrowcast for a campaign already in PROCESSING/RETRYING. Runs in the tenant context. */
    public void launch(long campaignId) {
        long tenantId = TenantContextHolder.require();
        Campaign campaign = tx.execute(s -> campaigns.findScopedById(campaignId).orElseThrow());
        ObjectNode meta = meta(campaign);
        long audienceGroupId = meta.path("audienceGroupId").asLong();
        try {
            MessageTemplate template = tx.execute(s -> templates.findScopedById(campaign.getTemplateId()).orElseThrow(
                    () -> new IllegalStateException("Campaign template no longer exists")));
            RenderedMessage.Line line = (RenderedMessage.Line) templateService.render(template, Map.of());
            LineMessages.SendResult result = lineSender.narrowcast(tenantId, audienceGroupId, line.messages(),
                    UUID.randomUUID().toString());
            meta.put(META_REQUEST_ID, result.requestId());
            meta.remove(META_ERROR);
            tx.executeWithoutResult(s -> {
                Campaign c = campaigns.findScopedById(campaignId).orElseThrow();
                c.setTargetAudienceMeta(meta.toString());
                campaigns.save(c);
            });
            log.info("Narrowcast issued for campaign {} (request {})", campaignId, result.requestId());
        } catch (Exception e) {
            DispatchError error = classifier.classify(e);
            log.warn("Narrowcast for campaign {} failed: {} {}", campaignId, error.code(), error.message());
            fail(campaignId, meta, error.code() + ": " + error.message());
        }
    }

    /** Applies a finished progress report and finalises under the campaign lock. */
    public void applyProgress(long campaignId, LineMessages.NarrowcastProgress progress) {
        RLock lock = redisson.getLock("ltk:campaign:finalise:" + campaignId);
        boolean locked;
        try {
            locked = lock.tryLock(0, 30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
        if (!locked) {
            return;
        }
        try {
            tx.executeWithoutResult(s -> {
                Campaign campaign = campaigns.findScopedById(campaignId).orElse(null);
                if (campaign == null || !campaign.getStatus().isProcessing()) {
                    return;
                }
                int target = nz(progress.targetCount());
                int success = nz(progress.successCount());
                int failure = progress.isFailed() ? Math.max(nz(progress.failureCount()), target - success) : nz(progress.failureCount());
                campaign.setTotalCount(target);
                campaign.setSuccessCount(success);
                campaign.setFailedCount(failure);
                if (progress.isFailed()) {
                    ObjectNode meta = meta(campaign);
                    meta.put(META_ERROR, progress.failedDescription() == null
                            ? "LINE reported narrowcast failure (code " + progress.errorCode() + ")"
                            : progress.failedDescription());
                    campaign.setTargetAudienceMeta(meta.toString());
                }
                CampaignEvent event = progress.isFailed() ? CampaignEvent.NEED_RESOLUTION
                        : CampaignStateMachine.completionEvent(failure);
                stateMachine.transition(campaign, event);
                campaigns.save(campaign);
                log.info("Narrowcast campaign {} finalised → {} (target={}, success={}, failed={})",
                        campaignId, campaign.getStatus(), target, success, failure);
            });
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    private void fail(long campaignId, ObjectNode meta, String message) {
        meta.put(META_ERROR, message);
        meta.remove(META_REQUEST_ID);
        tx.executeWithoutResult(s -> {
            Campaign c = campaigns.findScopedById(campaignId).orElseThrow();
            c.setTargetAudienceMeta(meta.toString());
            if (c.getStatus().isProcessing()) {
                stateMachine.transition(c, CampaignEvent.NEED_RESOLUTION);
            }
            campaigns.save(c);
        });
    }

    public ObjectNode meta(Campaign campaign) {
        try {
            String raw = campaign.getTargetAudienceMeta();
            JsonNode node = raw == null || raw.isBlank() ? null : objectMapper.readTree(raw);
            return node instanceof ObjectNode on ? on : objectMapper.createObjectNode();
        } catch (JsonProcessingException e) {
            return objectMapper.createObjectNode();
        }
    }

    private static int nz(Integer value) {
        return value == null ? 0 : value;
    }
}
