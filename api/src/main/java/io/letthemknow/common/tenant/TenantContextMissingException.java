package io.letthemknow.common.tenant;

import io.letthemknow.common.ApiException;
import io.letthemknow.common.ErrorCode;

/** Thrown when tenant-scoped data is accessed without a tenant (or system) context bound. */
public class TenantContextMissingException extends ApiException {

    public TenantContextMissingException(String message) {
        super(ErrorCode.UNAUTHORIZED, message);
    }
}
