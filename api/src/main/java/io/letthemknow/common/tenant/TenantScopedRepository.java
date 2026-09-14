package io.letthemknow.common.tenant;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.NoRepositoryBean;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

/**
 * Base repository for tenant-scoped entities. Prefer {@link #findScopedById(Long)} over
 * {@code findById}: it goes through HQL so the tenant filter applies and cross-tenant IDs simply
 * return empty (404), whereas {@code findById} bypasses filters and only fails via the
 * {@code @PostLoad} guard.
 */
@NoRepositoryBean
public interface TenantScopedRepository<T extends TenantAwareEntity> extends JpaRepository<T, Long> {

    @Query("select e from #{#entityName} e where e.id = :id")
    Optional<T> findScopedById(@Param("id") Long id);
}
