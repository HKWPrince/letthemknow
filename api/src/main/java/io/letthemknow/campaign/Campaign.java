package io.letthemknow.campaign;

import io.letthemknow.channel.ChannelType;
import io.letthemknow.common.tenant.TenantAwareEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.Filter;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/**
 * Campaign aggregate. {@code status} must only change through {@code CampaignStateMachine};
 * the setter is package-private for that reason.
 */
@Entity
@Table(name = "campaigns")
@Filter(name = TenantAwareEntity.TENANT_FILTER, condition = TenantAwareEntity.TENANT_FILTER_CONDITION)
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Campaign extends TenantAwareEntity {

    @Column(nullable = false, length = 150)
    private String title;

    @Enumerated(EnumType.STRING)
    @Column(name = "channel_type", nullable = false, length = 20)
    private ChannelType channelType;

    @Column(name = "template_id")
    private Long templateId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    @Setter(AccessLevel.PACKAGE)
    private CampaignStatus status = CampaignStatus.DRAFT;

    @Column(name = "scheduled_at")
    private OffsetDateTime scheduledAt;

    @Column(name = "started_at")
    private OffsetDateTime startedAt;

    @Column(name = "finished_at")
    private OffsetDateTime finishedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "target_audience_type", nullable = false, length = 50)
    private AudienceType targetAudienceType;

    /** JSON, e.g. {@code {"audienceGroupId": 123}} for LINE_AUDIENCE_GROUP. */
    @Column(name = "target_audience_meta")
    private String targetAudienceMeta;

    @Column(name = "total_count", nullable = false)
    private int totalCount;

    @Column(name = "success_count", nullable = false)
    private int successCount;

    @Column(name = "failed_count", nullable = false)
    private int failedCount;

    @Enumerated(EnumType.STRING)
    @Column(name = "import_status", nullable = false, length = 20)
    private ImportStatus importStatus = ImportStatus.NONE;

    @Column(name = "import_error")
    private String importError;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    public Campaign(String title, ChannelType channelType, Long templateId, AudienceType targetAudienceType,
                    String targetAudienceMeta) {
        this.title = title;
        this.channelType = channelType;
        this.templateId = templateId;
        this.targetAudienceType = targetAudienceType;
        this.targetAudienceMeta = targetAudienceMeta;
    }

    @PrePersist
    void onCreate() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = OffsetDateTime.now(ZoneOffset.UTC);
    }
}
