package io.letthemknow.common.tenant;

import jakarta.persistence.EntityManager;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.hibernate.Session;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Enables the Hibernate {@code tenantFilter} on the transactional session around every Spring Data
 * repository call. If no transaction is active one is opened so that the filter is applied to the same
 * session the repository will use. In {@link SystemTenantScope} the filter is disabled instead.
 */
@Aspect
@Component
class TenantFilterAspect {

    private final EntityManager entityManager;
    private final TransactionTemplate transactionTemplate;

    TenantFilterAspect(EntityManager entityManager, PlatformTransactionManager transactionManager) {
        this.entityManager = entityManager;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Around("execution(* org.springframework.data.repository.Repository+.*(..))")
    Object applyTenantFilter(ProceedingJoinPoint pjp) throws Throwable {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            configureFilter();
            return pjp.proceed();
        }
        return transactionTemplate.execute(status -> {
            configureFilter();
            try {
                return pjp.proceed();
            } catch (RuntimeException | Error e) {
                throw e;
            } catch (Throwable t) {
                throw new IllegalStateException(t);
            }
        });
    }

    private void configureFilter() {
        Session session = entityManager.unwrap(Session.class);
        if (TenantContextHolder.isSystem()) {
            if (session.getEnabledFilter(TenantAwareEntity.TENANT_FILTER) != null) {
                session.disableFilter(TenantAwareEntity.TENANT_FILTER);
            }
            return;
        }
        long tenantId = TenantContextHolder.require();
        session.enableFilter(TenantAwareEntity.TENANT_FILTER).setParameter("tenantId", tenantId);
    }
}
