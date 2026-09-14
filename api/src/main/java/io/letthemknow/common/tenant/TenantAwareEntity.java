package io.letthemknow.common.tenant;

import jakarta.persistence.Column;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PrePersist;
import lombok.Getter;
import org.hibernate.annotations.FilterDef;
import org.hibernate.annotations.ParamDef;

/**
 * Base for every tenant-scoped entity. Subclasses must declare
 * {@code @Filter(name = TenantAwareEntity.TENANT_FILTER, condition = "tenant_id = :tenantId")}.
 * <p>
 * Defence in depth: the Hibernate filter restricts queries; {@link #assignTenantOnPersist()} stamps
 * new rows with the bound tenant; {@link #verifyTenantOnLoad()} rejects primary-key loads of rows
 * that belong to another tenant (filters do not apply to {@code em.find}).
 */
@MappedSuperclass
@FilterDef(name = TenantAwareEntity.TENANT_FILTER, parameters = @ParamDef(name = "tenantId", type = Long.class))
@Getter
public abstract class TenantAwareEntity {

    public static final String TENANT_FILTER = "tenantFilter";
    public static final String TENANT_FILTER_CONDITION = "tenant_id = :tenantId";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private Long tenantId;

    protected TenantAwareEntity() {}

    protected TenantAwareEntity(Long tenantId) {
        this.tenantId = tenantId;
    }

    @PrePersist
    void assignTenantOnPersist() {
        if (tenantId == null) {
            if (TenantContextHolder.isSystem()) {
                throw new IllegalStateException(
                        getClass().getSimpleName() + " persisted in system scope without an explicit tenantId");
            }
            tenantId = TenantContextHolder.require();
        }
    }

    @PostLoad
    void verifyTenantOnLoad() {
        TenantContextHolder.get().ifPresent(bound -> {
            if (!bound.equals(tenantId)) {
                throw new TenantAccessException();
            }
        });
    }
}
