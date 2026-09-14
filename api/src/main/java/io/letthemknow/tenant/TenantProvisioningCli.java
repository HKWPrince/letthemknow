package io.letthemknow.tenant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * {@code java -jar app.jar --provision-tenant --name=X --admin-email=Y --admin-password=Z}
 * Creates the tenant and its admin, prints the result and exits. Add {@code --server.port=0} when an
 * API instance is already listening on the same host.
 */
@Component
class TenantProvisioningCli implements ApplicationListener<ApplicationReadyEvent> {

    static final String FLAG = "provision-tenant";
    private static final Logger log = LoggerFactory.getLogger(TenantProvisioningCli.class);

    private final ApplicationArguments args;
    private final TenantProvisioningService provisioningService;

    TenantProvisioningCli(ApplicationArguments args, TenantProvisioningService provisioningService) {
        this.args = args;
        this.provisioningService = provisioningService;
    }

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        if (!args.containsOption(FLAG)) {
            return;
        }
        int exitCode;
        try {
            TenantProvisioningService.ProvisionedTenant result = provisioningService.provision(
                    option("name"), option("admin-email"), option("admin-password"));
            System.out.printf("Provisioned tenant '%s' (id=%d) with admin %s (id=%d)%n",
                    result.tenant().getName(), result.tenant().getId(),
                    result.admin().getEmail(), result.admin().getId());
            exitCode = 0;
        } catch (RuntimeException e) {
            log.error("Tenant provisioning failed: {}", e.getMessage());
            System.err.println("Tenant provisioning failed: " + e.getMessage());
            exitCode = 1;
        }
        ConfigurableApplicationContext ctx = event.getApplicationContext();
        int code = exitCode;
        System.exit(SpringApplication.exit(ctx, () -> code));
    }

    private String option(String name) {
        List<String> values = args.getOptionValues(name);
        if (values == null || values.isEmpty() || values.get(0).isBlank()) {
            throw new IllegalArgumentException("--" + name + " is required");
        }
        return values.get(0).trim();
    }
}
