package io.letthemknow.common.tenant;

import java.util.function.Supplier;

/**
 * The only way to run JPA access without the tenant filter. Use exclusively for the scheduler poller,
 * authentication (before a tenant is known) and the tenant-provisioning CLI.
 */
public final class SystemTenantScope {

    private SystemTenantScope() {}

    public static <T> T runAsSystem(Supplier<T> action) {
        return TenantContextHolder.runWith(TenantContextHolder.TenantContext.ofSystem(), action);
    }

    public static void runAsSystem(Runnable action) {
        TenantContextHolder.runWith(TenantContextHolder.TenantContext.ofSystem(), () -> {
            action.run();
            return null;
        });
    }
}
