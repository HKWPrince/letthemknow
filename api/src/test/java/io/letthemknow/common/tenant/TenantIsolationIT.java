package io.letthemknow.common.tenant;

import io.letthemknow.channel.ChannelType;
import io.letthemknow.support.IntegrationTestBase;
import io.letthemknow.support.TestTenants;
import io.letthemknow.template.MessageTemplate;
import io.letthemknow.template.MessageTemplateRepository;
import io.letthemknow.template.TemplateType;
import io.letthemknow.tenant.TenantProvisioningService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TenantIsolationIT extends IntegrationTestBase {

    @Autowired
    TestTenants tenants;

    @Autowired
    MessageTemplateRepository templates;

    long tenantA;
    long tenantB;

    @BeforeEach
    void provisionTenants() {
        TenantProvisioningService.ProvisionedTenant a = tenants.provision("iso-a");
        TenantProvisioningService.ProvisionedTenant b = tenants.provision("iso-b");
        tenantA = a.tenant().getId();
        tenantB = b.tenant().getId();
        TenantContextHolder.clear();
    }

    private MessageTemplate saveTemplateAs(long tenantId, String name) {
        return TenantContextHolder.runAs(tenantId, () -> templates.save(
                new MessageTemplate(name, ChannelType.EMAIL, TemplateType.EMAIL_HTML, "Hi {{name}}",
                        "{\"html\":\"<p>{{name}}</p>\",\"text\":\"{{name}}\"}")));
    }

    @Test
    void persistStampsBoundTenant() {
        MessageTemplate saved = saveTemplateAs(tenantA, "welcome");

        assertThat(saved.getTenantId()).isEqualTo(tenantA);
    }

    @Test
    void queriesFromAnotherTenantReturnNothing() {
        MessageTemplate saved = saveTemplateAs(tenantA, "welcome");

        TenantContextHolder.runAs(tenantB, () -> {
            assertThat(templates.findAll()).isEmpty();
            assertThat(templates.findByName("welcome")).isEmpty();
            assertThat(templates.existsByName("welcome")).isFalse();
            assertThat(templates.findScopedById(saved.getId())).isEmpty();
            assertThat(templates.count()).isZero();
        });

        TenantContextHolder.runAs(tenantA, () -> {
            assertThat(templates.findAll()).hasSize(1);
            assertThat(templates.findScopedById(saved.getId())).isPresent();
        });
    }

    @Test
    void primaryKeyLoadFromAnotherTenantIsRejected() {
        MessageTemplate saved = saveTemplateAs(tenantA, "welcome");

        assertThatThrownBy(() -> TenantContextHolder.runAs(tenantB, () -> templates.findById(saved.getId())))
                .satisfies(t -> assertThat(hasCause(t, TenantAccessException.class)).isTrue());
    }

    @Test
    void sameNameCanExistInBothTenants() {
        saveTemplateAs(tenantA, "shared-name");
        saveTemplateAs(tenantB, "shared-name");

        TenantContextHolder.runAs(tenantA, () -> assertThat(templates.findAll()).hasSize(1));
        TenantContextHolder.runAs(tenantB, () -> assertThat(templates.findAll()).hasSize(1));
    }

    @Test
    void accessWithoutContextIsRefused() {
        assertThatThrownBy(() -> templates.findAll()).isInstanceOf(TenantContextMissingException.class);
    }

    @Test
    void systemScopeSeesEveryTenant() {
        MessageTemplate a = saveTemplateAs(tenantA, "sys-a");
        MessageTemplate b = saveTemplateAs(tenantB, "sys-b");

        SystemTenantScope.runAsSystem(() -> {
            assertThat(templates.findScopedById(a.getId())).isPresent();
            assertThat(templates.findScopedById(b.getId())).isPresent();
            assertThat(templates.findById(a.getId())).isPresent();
        });
    }

    @Test
    void nestedScopesRestorePreviousContext() {
        TenantContextHolder.runAs(tenantA, () -> {
            SystemTenantScope.runAsSystem(() -> assertThat(TenantContextHolder.isSystem()).isTrue());
            assertThat(TenantContextHolder.require()).isEqualTo(tenantA);
        });
        assertThat(TenantContextHolder.isBound()).isFalse();
    }

    private static boolean hasCause(Throwable t, Class<? extends Throwable> type) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (type.isInstance(c)) {
                return true;
            }
            if (c.getCause() == c) {
                break;
            }
        }
        return false;
    }
}
