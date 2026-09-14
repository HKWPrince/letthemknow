package io.letthemknow.integration;

import io.letthemknow.common.tenant.TenantScopedRepository;

public interface TransactionalMessageRepository extends TenantScopedRepository<TransactionalMessage> {
}
