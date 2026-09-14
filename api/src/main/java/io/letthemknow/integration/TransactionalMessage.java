package io.letthemknow.integration;

import io.letthemknow.channel.ChannelType;
import io.letthemknow.common.tenant.TenantAwareEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.Filter;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/** Single push sent through the integration API. No campaign row. */
@Entity
@Table(name = "transactional_messages")
@Filter(name = TenantAwareEntity.TENANT_FILTER, condition = TenantAwareEntity.TENANT_FILTER_CONDITION)
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TransactionalMessage extends TenantAwareEntity {

    @Enumerated(EnumType.STRING)
    @Column(name = "channel_type", nullable = false, length = 20)
    private ChannelType channelType;

    @Column(name = "template_id", nullable = false)
    private Long templateId;

    @Column(name = "recipient_identifier", nullable = false, length = 255)
    private String recipientIdentifier;

    @Column(name = "payload_params")
    private String payloadParams;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TransactionalStatus status = TransactionalStatus.PENDING;

    @Column(name = "error_code", length = 100)
    private String errorCode;

    @Column(name = "error_message")
    private String errorMessage;

    @Column(name = "external_message_id", length = 255)
    private String externalMessageId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "sent_at")
    private OffsetDateTime sentAt;

    public TransactionalMessage(ChannelType channelType, Long templateId, String recipientIdentifier,
                                String payloadParams) {
        this.channelType = channelType;
        this.templateId = templateId;
        this.recipientIdentifier = recipientIdentifier;
        this.payloadParams = payloadParams;
    }

    @PrePersist
    void onCreate() {
        createdAt = OffsetDateTime.now(ZoneOffset.UTC);
    }
}
