package io.letthemknow.support;

import io.letthemknow.common.tenant.SystemTenantScope;
import io.letthemknow.tenant.Tenant;
import io.letthemknow.tenant.TenantProvisioningService;
import io.letthemknow.tenant.TenantRepository;
import org.springframework.stereotype.Component;

import java.util.UUID;

/** Helpers for creating isolated tenants in integration tests. */
@Component
public class TestTenants {

    public static final String DEMO_ADMIN_EMAIL = "admin@demo.local";
    public static final String DEMO_ADMIN_PASSWORD = "Admin123!";
    public static final String DEMO_API_KEY = "ltk_demo1234_Kq7wL2xN9vB4mR6tY8uJ3hG5fD1sA0zC";

    private final TenantProvisioningService provisioningService;
    private final TenantRepository tenantRepository;

    public TestTenants(TenantProvisioningService provisioningService, TenantRepository tenantRepository) {
        this.provisioningService = provisioningService;
        this.tenantRepository = tenantRepository;
    }

    public TenantProvisioningService.ProvisionedTenant provision(String label) {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        return provisioningService.provision(label + "-" + suffix, label + "-" + suffix + "@test.local", "Passw0rd!");
    }

    public Tenant demo() {
        return SystemTenantScope.runAsSystem(() -> tenantRepository.findByName("demo").orElseThrow());
    }
}
