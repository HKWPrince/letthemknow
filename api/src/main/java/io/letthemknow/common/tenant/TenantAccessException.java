package io.letthemknow.common.tenant;

import io.letthemknow.common.ApiException;
import io.letthemknow.common.ErrorCode;

/**
 * Raised when an entity loaded by primary key belongs to a different tenant than the bound context.
 * Mapped to 404 so that existence of other tenants' rows is never leaked.
 */
public class TenantAccessException extends ApiException {

    public TenantAccessException() {
        super(ErrorCode.NOT_FOUND, "Resource not found");
    }
}
