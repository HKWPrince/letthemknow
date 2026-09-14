package io.letthemknow.integration;

import io.letthemknow.auth.CurrentPrincipal;
import io.letthemknow.auth.LtkPrincipal;
import io.letthemknow.common.ApiResponse;
import io.letthemknow.integration.dto.SinglePushRequest;
import io.letthemknow.integration.dto.SinglePushResponse;
import io.letthemknow.integration.dto.TransactionalMessageDto;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** External API: authenticated with {@code X-API-KEY} only, rate limited per key. */
@RestController
@RequestMapping("/api/v1/integration")
@Tag(name = "Integration", description = "External API for ERP systems (X-API-KEY)")
@SecurityRequirement(name = "apiKey")
class IntegrationController {

    private final SinglePushService pushService;

    IntegrationController(SinglePushService pushService) {
        this.pushService = pushService;
    }

    record PingResponse(Long tenantId, Long apiKeyId) {}

    @Operation(summary = "Verify an API key")
    @GetMapping("/ping")
    ApiResponse<PingResponse> ping() {
        LtkPrincipal principal = CurrentPrincipal.require();
        return ApiResponse.ok(new PingResponse(principal.tenantId(), principal.apiKeyId()));
    }

    @Operation(summary = "Send one message immediately",
            description = """
                    Renders the template for this recipient, sends it synchronously through your
                    configured channel, and stores a transactional message you can look up later.

                    A **delivery** failure is data, not an HTTP error: you get 200 with
                    `data.status = FAILED` and an error code. Reserve your error handling for 400,
                    401, 404 and 429.""")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200",
                    description = "Accepted and recorded. Check `data.status` for SENT or FAILED."),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400",
                    description = "Malformed body, both or neither of templateId/templateName, or the template's channel does not match.",
                    content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401",
                    description = "Missing, malformed or revoked X-API-KEY.", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404",
                    description = "No such template in your tenant.", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "429",
                    description = "Rate limit exceeded (60 requests per minute per key). Carries Retry-After.",
                    content = @Content),
    })
    @PostMapping("/push/single")
    ApiResponse<SinglePushResponse> pushSingle(@Valid @RequestBody SinglePushRequest request) {
        return ApiResponse.ok(pushService.push(request));
    }

    @Operation(summary = "Look up a previously sent message",
            description = "Returns the stored message, scoped to your tenant. An id from another tenant answers 404.")
    @GetMapping("/messages/{id}")
    ApiResponse<TransactionalMessageDto> message(@PathVariable Long id) {
        return ApiResponse.ok(pushService.get(id));
    }
}
