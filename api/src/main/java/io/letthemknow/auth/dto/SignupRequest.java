package io.letthemknow.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Self-service tenant creation. {@code code} is the shared signup code from {@code LTK_SIGNUP_CODE};
 * signup is disabled entirely when that is unset, so a deployment is closed until an operator opens it.
 *
 * <p>Bounds mirror {@code TenantProvisioningService.validate} so a bad request is rejected at the edge
 * with a field error rather than surfacing from the service layer.
 */
public record SignupRequest(
        @NotBlank @Size(max = 100) String tenantName,
        @NotBlank @Email @Size(max = 255) String email,
        @NotBlank @Size(min = 8, max = 200) String password,
        @NotBlank @Size(max = 200) String code) {
}
