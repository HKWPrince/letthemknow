package io.letthemknow.channel;

import io.letthemknow.common.crypto.EncryptedStringConverter;
import io.letthemknow.common.tenant.TenantAwareEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
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
import lombok.ToString;
import org.hibernate.annotations.Filter;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/**
 * One row per (tenant, channel). Secret fields are plaintext in memory and AES-GCM ciphertext in
 * the {@code *_enc} columns via {@link EncryptedStringConverter}; they are excluded from toString.
 */
@Entity
@Table(name = "channel_configs")
@Filter(name = TenantAwareEntity.TENANT_FILTER, condition = TenantAwareEntity.TENANT_FILTER_CONDITION)
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@ToString(exclude = {"lineChannelSecret", "lineChannelToken", "smtpPassword"})
public class ChannelConfig extends TenantAwareEntity {

    @Enumerated(EnumType.STRING)
    @Column(name = "channel_type", nullable = false, updatable = false, length = 20)
    private ChannelType channelType;

    @Column(name = "line_channel_id", length = 100)
    private String lineChannelId;

    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "line_channel_secret_enc")
    private String lineChannelSecret;

    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "line_channel_token_enc")
    private String lineChannelToken;

    @Column(name = "smtp_host", length = 255)
    private String smtpHost;

    @Column(name = "smtp_port")
    private Integer smtpPort;

    @Column(name = "smtp_username", length = 255)
    private String smtpUsername;

    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "smtp_password_enc")
    private String smtpPassword;

    @Column(name = "smtp_from_email", length = 255)
    private String smtpFromEmail;

    @Column(name = "smtp_from_name", length = 100)
    private String smtpFromName;

    @Column(name = "smtp_ssl_enabled", nullable = false)
    private boolean smtpSslEnabled = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    public ChannelConfig(ChannelType channelType) {
        this.channelType = channelType;
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
