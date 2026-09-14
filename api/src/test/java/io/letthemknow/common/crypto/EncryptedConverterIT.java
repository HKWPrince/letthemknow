package io.letthemknow.common.crypto;

import io.letthemknow.channel.ChannelConfig;
import io.letthemknow.channel.ChannelConfigRepository;
import io.letthemknow.channel.ChannelType;
import io.letthemknow.common.tenant.TenantContextHolder;
import io.letthemknow.support.IntegrationTestBase;
import io.letthemknow.support.TestTenants;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

class EncryptedConverterIT extends IntegrationTestBase {

    @Autowired
    TestTenants tenants;

    @Autowired
    ChannelConfigRepository configs;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    AesGcmEncryptor encryptor;

    @Test
    void secretsAreCiphertextInDbAndPlaintextInEntity() {
        long tenantId = tenants.provision("enc").tenant().getId();

        Long id = TenantContextHolder.runAs(tenantId, () -> {
            ChannelConfig config = new ChannelConfig(ChannelType.EMAIL);
            config.setSmtpHost("smtp.example.com");
            config.setSmtpPort(587);
            config.setSmtpUsername("mailer");
            config.setSmtpPassword("sm7p-P@ss");
            config.setSmtpFromEmail("noreply@example.com");
            return configs.save(config).getId();
        });

        String stored = jdbc.queryForObject(
                "SELECT smtp_password_enc FROM channel_configs WHERE id = ?", String.class, id);
        assertThat(stored).startsWith("v1:").doesNotContain("sm7p-P@ss");
        assertThat(encryptor.decrypt(stored)).isEqualTo("sm7p-P@ss");

        TenantContextHolder.runAs(tenantId, () -> {
            ChannelConfig loaded = configs.findByChannelType(ChannelType.EMAIL).orElseThrow();
            assertThat(loaded.getSmtpPassword()).isEqualTo("sm7p-P@ss");
            assertThat(loaded.getLineChannelSecret()).isNull();
            assertThat(loaded.toString()).doesNotContain("sm7p-P@ss");
        });
    }
}
