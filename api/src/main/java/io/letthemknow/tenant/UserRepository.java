package io.letthemknow.tenant;

import io.letthemknow.common.tenant.TenantScopedRepository;

import java.util.List;
import java.util.Optional;

public interface UserRepository extends TenantScopedRepository<User> {

    /** Login lookup across tenants; call inside {@code SystemTenantScope}. */
    List<User> findAllByEmailIgnoreCase(String email);

    Optional<User> findByTenantIdAndEmailIgnoreCase(Long tenantId, String email);

    boolean existsByTenantIdAndEmailIgnoreCase(Long tenantId, String email);
}
