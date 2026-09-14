package io.letthemknow.dispatch;

import io.letthemknow.campaign.Campaign;
import io.letthemknow.campaign.CampaignRecipientRepository;
import io.letthemknow.campaign.CampaignRepository;
import io.letthemknow.campaign.CampaignStateMachine;
import io.letthemknow.campaign.RecipientStatus;
import io.letthemknow.common.tenant.TenantContextHolder;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * After each chunk: recompute the campaign counters with one aggregate UPDATE, and when nothing is
 * PENDING or SENDING any more, finalise once under the Redisson lock {@code ltk:campaign:finalise:{id}}.
 */
@Service
public class CampaignProgressService {

    private static final Logger log = LoggerFactory.getLogger(CampaignProgressService.class);
    private static final List<RecipientStatus> IN_FLIGHT = List.of(RecipientStatus.PENDING, RecipientStatus.SENDING);

    private final CampaignRepository campaigns;
    private final CampaignRecipientRepository recipients;
    private final CampaignStateMachine stateMachine;
    private final RedissonClient redisson;
    private final TransactionTemplate tx;
    private final TransactionTemplate freshTx;

    public CampaignProgressService(CampaignRepository campaigns, CampaignRecipientRepository recipients,
                                   CampaignStateMachine stateMachine, RedissonClient redisson,
                                   PlatformTransactionManager txManager) {
        this.campaigns = campaigns;
        this.recipients = recipients;
        this.stateMachine = stateMachine;
        this.redisson = redisson;
        this.tx = new TransactionTemplate(txManager);
        this.freshTx = new TransactionTemplate(txManager);
        this.freshTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** Must run inside a tenant context. */
    public void onChunkDone(long campaignId) {
        long tenantId = TenantContextHolder.require();
        tx.executeWithoutResult(s -> campaigns.refreshCounts(campaignId, tenantId));
        long inFlight = tx.execute(s -> recipients.countByCampaignIdAndStatusIn(campaignId, IN_FLIGHT));
        if (inFlight == 0) {
            finalise(campaignId);
        }
    }

    /** Completion rule: {@code failed_count == 0 ? COMPLETED : AWAITING_RESOLUTION}. Idempotent. */
    public void finalise(long campaignId) {
        long tenantId = TenantContextHolder.require();
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
            freshTx.executeWithoutResult(s -> {
                campaigns.refreshCounts(campaignId, tenantId);
                Campaign campaign = campaigns.findScopedById(campaignId).orElse(null);
                if (campaign == null || !campaign.getStatus().isProcessing()) {
                    return;
                }
                if (recipients.countByCampaignIdAndStatusIn(campaignId, IN_FLIGHT) > 0) {
                    return;
                }
                var event = CampaignStateMachine.completionEvent(campaign.getFailedCount());
                var to = stateMachine.transition(campaign, event);
                campaigns.save(campaign);
                log.info("Campaign {} finalised → {} (sent={}, failed={})", campaignId, to,
                        campaign.getSuccessCount(), campaign.getFailedCount());
            });
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }
}
