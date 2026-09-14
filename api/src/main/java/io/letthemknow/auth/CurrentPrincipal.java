package io.letthemknow.auth;

import io.letthemknow.common.ApiException;
import io.letthemknow.common.ErrorCode;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Optional;

/** Access to the authenticated {@link LtkPrincipal} of the current request. */
public final class CurrentPrincipal {

    private CurrentPrincipal() {}

    public static Optional<LtkPrincipal> find() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof LtkPrincipal principal) {
            return Optional.of(principal);
        }
        return Optional.empty();
    }

    public static LtkPrincipal require() {
        return find().orElseThrow(() -> new ApiException(ErrorCode.UNAUTHORIZED, "Authentication required"));
    }
}
