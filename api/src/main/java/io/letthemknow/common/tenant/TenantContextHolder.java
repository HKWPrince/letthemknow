package io.letthemknow.common.tenant;

import java.util.Optional;
import java.util.function.Supplier;

/**
 * Thread-local tenant context. Set by {@code TenantContextFilter} for HTTP requests and explicitly by
 * workers before touching JPA; always cleared in a {@code finally}. The system scope (no tenant filter)
 * is only reachable through {@link SystemTenantScope}.
 */
public final class TenantContextHolder {

    private static final ThreadLocal<TenantContext> CONTEXT = new ThreadLocal<>();

    private TenantContextHolder() {}

    public static void set(long tenantId) {
        CONTEXT.set(TenantContext.ofTenant(tenantId));
    }

    public static Optional<Long> get() {
        TenantContext ctx = CONTEXT.get();
        return ctx == null || ctx.system() ? Optional.empty() : Optional.of(ctx.tenantId());
    }

    public static long require() {
        return get().orElseThrow(() -> new TenantContextMissingException("No tenant context bound to the current thread"));
    }

    public static boolean isSystem() {
        TenantContext ctx = CONTEXT.get();
        return ctx != null && ctx.system();
    }

    public static boolean isBound() {
        return CONTEXT.get() != null;
    }

    public static void clear() {
        CONTEXT.remove();
    }

    /** Runs {@code action} with the given tenant bound, restoring the previous context afterwards. */
    public static <T> T runAs(long tenantId, Supplier<T> action) {
        return runWith(TenantContext.ofTenant(tenantId), action);
    }

    public static void runAs(long tenantId, Runnable action) {
        runWith(TenantContext.ofTenant(tenantId), () -> {
            action.run();
            return null;
        });
    }

    static <T> T runWith(TenantContext context, Supplier<T> action) {
        TenantContext previous = CONTEXT.get();
        CONTEXT.set(context);
        try {
            return action.get();
        } finally {
            if (previous == null) {
                CONTEXT.remove();
            } else {
                CONTEXT.set(previous);
            }
        }
    }

    record TenantContext(Long tenantId, boolean system) {
        static TenantContext ofTenant(long tenantId) {
            return new TenantContext(tenantId, false);
        }

        static TenantContext ofSystem() {
            return new TenantContext(null, true);
        }
    }
}
