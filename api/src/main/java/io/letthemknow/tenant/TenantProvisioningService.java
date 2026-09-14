package io.letthemknow.tenant;

import io.letthemknow.common.ApiException;
import io.letthemknow.common.ErrorCode;
import io.letthemknow.common.tenant.SystemTenantScope;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Creates a tenant with its first ADMIN user. Used by the CLI and by tests. */
@Service
public class TenantProvisioningService {

    public record ProvisionedTenant(Tenant tenant, User admin) {}

    private final TenantRepository tenantRepository;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public TenantProvisioningService(TenantRepository tenantRepository, UserRepository userRepository,
                                     PasswordEncoder passwordEncoder) {
        this.tenantRepository = tenantRepository;
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Transactional
    public ProvisionedTenant provision(String name, String adminEmail, String adminPassword) {
        validate(name, adminEmail, adminPassword);
        return SystemTenantScope.runAsSystem(() -> {
            if (tenantRepository.existsByName(name)) {
                throw new ApiException(ErrorCode.CONFLICT, "Tenant '" + name + "' already exists");
            }
            Tenant tenant = tenantRepository.save(new Tenant(name));
            User admin = userRepository.save(
                    new User(tenant.getId(), adminEmail, passwordEncoder.encode(adminPassword), UserRole.ADMIN));
            return new ProvisionedTenant(tenant, admin);
        });
    }

    private static void validate(String name, String adminEmail, String adminPassword) {
        if (name == null || name.isBlank() || name.length() > 100) {
            throw new ApiException(ErrorCode.BAD_REQUEST, "Tenant name is required (max 100 chars)");
        }
        if (adminEmail == null || !adminEmail.contains("@") || adminEmail.length() > 255) {
            throw new ApiException(ErrorCode.BAD_REQUEST, "A valid admin email is required");
        }
        if (adminPassword == null || adminPassword.length() < 8) {
            throw new ApiException(ErrorCode.BAD_REQUEST, "Admin password must be at least 8 characters");
        }
    }
}
