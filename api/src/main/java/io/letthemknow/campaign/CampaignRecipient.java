package io.letthemknow.campaign;

import io.letthemknow.common.tenant.TenantAwareEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.Filter;

import java.time.OffsetDateTime;

/**
 * One row per (campaign, recipient). Bulk inserts happen via JDBC batch in {@code RecipientImportService};
 * this entity serves reads and per-row status updates.
 */
@Entity
@Table(name = "campaign_recipients")
@Filter(name = TenantAwareEntity.TENANT_FILTER, condition = TenantAwareEntity.TENANT_FILTER_CONDITION)
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CampaignRecipient extends TenantAwareEntity {

    @Column(name = "campaign_id", nullable = false, updatable = false)
    private Long campaignId;

    @Column(name = "recipient_identifier", nullable = false, updatable = false, length = 255)
    private String recipientIdentifier;

    /** JSON object of template params from the CSV columns. */
    @Column(name = "payload_params")
    private String payloadParams;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private RecipientStatus status = RecipientStatus.PENDING;

    @Column(name = "error_code", length = 100)
    private String errorCode;

    @Column(name = "error_message")
    private String errorMessage;

    @Column(name = "retry_count", nullable = false)
    private int retryCount;

    @Column(name = "external_message_id", length = 255)
    private String externalMessageId;

    @Column(name = "sent_at")
    private OffsetDateTime sentAt;

    public CampaignRecipient(Long tenantId, Long campaignId, String recipientIdentifier, String payloadParams) {
        super(tenantId);
        this.campaignId = campaignId;
        this.recipientIdentifier = recipientIdentifier;
        this.payloadParams = payloadParams;
    }
}
