package io.letthemknow.auth;

import io.letthemknow.auth.dto.ApiKeyCreatedDto;
import io.letthemknow.auth.dto.ApiKeyDto;
import io.letthemknow.auth.dto.CreateApiKeyRequest;
import io.letthemknow.common.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/api-keys")
class ApiKeyController {

    private final ApiKeyService apiKeyService;

    ApiKeyController(ApiKeyService apiKeyService) {
        this.apiKeyService = apiKeyService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('ADMIN')")
    ApiResponse<ApiKeyCreatedDto> create(@Valid @RequestBody CreateApiKeyRequest request) {
        return ApiResponse.created(apiKeyService.create(request.name().trim()));
    }

    @GetMapping
    ApiResponse<List<ApiKeyDto>> list() {
        return ApiResponse.ok(apiKeyService.list());
    }

    /** Revokes (does not delete) the key so audit history is kept. */
    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    ApiResponse<ApiKeyDto> revoke(@PathVariable Long id) {
        return ApiResponse.ok("Revoked", apiKeyService.revoke(id));
    }
}
