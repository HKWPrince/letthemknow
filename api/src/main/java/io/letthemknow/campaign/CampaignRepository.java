package io.letthemknow.campaign;

import io.letthemknow.common.tenant.TenantScopedRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;

public interface CampaignRepository extends TenantScopedRepository<Campaign> {

    Page<Campaign> findAllByOrderByCreatedAtDesc(Pageable pageable);

    Page<Campaign> findAllByStatusOrderByCreatedAtDesc(CampaignStatus status, Pageable pageable);

    List<Campaign> findTop10ByOrderByCreatedAtDesc();

    long countByStatus(CampaignStatus status);

    /** Cross-tenant pollers (call inside {@code SystemTenantScope}). */
    List<Campaign> findByStatusAndScheduledAtBefore(CampaignStatus status, OffsetDateTime before);

    List<Campaign> findByStatusInAndTargetAudienceType(Collection<CampaignStatus> statuses, AudienceType audienceType);

    /**
     * Recomputes the counters from campaign_recipients in one statement (used after each dispatched chunk).
     * Explicit tenant predicate because bulk statements do not go through the entity filter.
     */
    @Modifying
    @Query(value = """
            UPDATE campaigns c
            LEFT JOIN (
                SELECT campaign_id,
                       SUM(CASE WHEN status = 'SENT'   THEN 1 ELSE 0 END) AS sent,
                       SUM(CASE WHEN status = 'FAILED' THEN 1 ELSE 0 END) AS failed
                FROM campaign_recipients
                WHERE campaign_id = :campaignId AND tenant_id = :tenantId
                GROUP BY campaign_id
            ) s ON s.campaign_id = c.id
            SET c.success_count = COALESCE(s.sent, 0),
                c.failed_count  = COALESCE(s.failed, 0),
                c.updated_at    = CURRENT_TIMESTAMP(6)
            WHERE c.id = :campaignId AND c.tenant_id = :tenantId
            """, nativeQuery = true)
    int refreshCounts(@Param("campaignId") Long campaignId, @Param("tenantId") Long tenantId);
}
