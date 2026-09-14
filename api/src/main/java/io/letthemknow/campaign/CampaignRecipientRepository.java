package io.letthemknow.campaign;

import io.letthemknow.common.tenant.TenantScopedRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface CampaignRecipientRepository extends TenantScopedRepository<CampaignRecipient> {

    /** Projection for the per-status counts of one campaign. */
    interface StatusCount {
        RecipientStatus getStatus();

        long getCount();
    }

    Page<CampaignRecipient> findByCampaignIdOrderByIdAsc(Long campaignId, Pageable pageable);

    Page<CampaignRecipient> findByCampaignIdAndStatusOrderByIdAsc(Long campaignId, RecipientStatus status,
                                                                  Pageable pageable);

    List<CampaignRecipient> findByCampaignIdAndStatus(Long campaignId, RecipientStatus status);

    List<CampaignRecipient> findByIdIn(Collection<Long> ids);

    long countByCampaignId(Long campaignId);

    long countByCampaignIdAndStatusIn(Long campaignId, Collection<RecipientStatus> statuses);

    @Query("select r.status as status, count(r) as count from CampaignRecipient r "
            + "where r.campaignId = :campaignId group by r.status")
    List<StatusCount> countByStatusForCampaign(@Param("campaignId") Long campaignId);

    /** Failure histogram by error code, most frequent first. */
    interface ErrorCodeCount {
        String getErrorCode();

        long getCount();
    }

    @Query("select coalesce(r.errorCode, 'UNKNOWN') as errorCode, count(r) as count from CampaignRecipient r "
            + "where r.campaignId = :campaignId and r.status = io.letthemknow.campaign.RecipientStatus.FAILED "
            + "group by r.errorCode order by count(r) desc")
    List<ErrorCodeCount> countFailuresByErrorCode(@Param("campaignId") Long campaignId);

    Page<CampaignRecipient> findByCampaignIdAndStatusAndErrorCodeOrderByIdAsc(Long campaignId, RecipientStatus status,
                                                                             String errorCode, Pageable pageable);

    @Query("select r.id from CampaignRecipient r where r.campaignId = :campaignId and r.status = :status order by r.id")
    List<Long> findIdsByCampaignIdAndStatus(@Param("campaignId") Long campaignId, @Param("status") RecipientStatus status);

    /**
     * Claims PENDING rows for sending. Returns the number actually claimed; callers must re-read the
     * claimed rows (status = SENDING) and only send those, giving at-least-once without double-send.
     * Bulk statements bypass the entity filter, hence the explicit tenant predicate.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update CampaignRecipient r set r.status = io.letthemknow.campaign.RecipientStatus.SENDING "
            + "where r.id in :ids and r.status = io.letthemknow.campaign.RecipientStatus.PENDING "
            + "and r.tenantId = :tenantId")
    int claimPending(@Param("ids") Collection<Long> ids, @Param("tenantId") Long tenantId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update CampaignRecipient r set r.status = :to where r.campaignId = :campaignId "
            + "and r.status = :from and r.tenantId = :tenantId")
    int transitionAll(@Param("campaignId") Long campaignId, @Param("from") RecipientStatus from,
                      @Param("to") RecipientStatus to, @Param("tenantId") Long tenantId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from CampaignRecipient r where r.campaignId = :campaignId and r.tenantId = :tenantId")
    int deleteByCampaign(@Param("campaignId") Long campaignId, @Param("tenantId") Long tenantId);

    /** Abort path for one chunk: PENDING rows of these ids → CANCELLED. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update CampaignRecipient r set r.status = io.letthemknow.campaign.RecipientStatus.CANCELLED "
            + "where r.id in :ids and r.status = io.letthemknow.campaign.RecipientStatus.PENDING and r.tenantId = :tenantId")
    int cancelPending(@Param("ids") Collection<Long> ids, @Param("tenantId") Long tenantId);

    /** Reaper path: rows a dead consumer left in SENDING → PENDING so they can be claimed again. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update CampaignRecipient r set r.status = io.letthemknow.campaign.RecipientStatus.PENDING "
            + "where r.id in :ids and r.status = io.letthemknow.campaign.RecipientStatus.SENDING and r.tenantId = :tenantId")
    int resetSending(@Param("ids") Collection<Long> ids, @Param("tenantId") Long tenantId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update CampaignRecipient r set r.status = io.letthemknow.campaign.RecipientStatus.PENDING, "
            + "r.retryCount = r.retryCount + 1, r.errorCode = null, r.errorMessage = null "
            + "where r.campaignId = :campaignId and r.status = io.letthemknow.campaign.RecipientStatus.FAILED "
            + "and r.tenantId = :tenantId")
    int resetFailedToPending(@Param("campaignId") Long campaignId, @Param("tenantId") Long tenantId);
}
