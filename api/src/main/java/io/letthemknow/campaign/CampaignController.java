package io.letthemknow.campaign;

import io.letthemknow.campaign.dto.CampaignDetailDto;
import io.letthemknow.campaign.dto.CampaignDto;
import io.letthemknow.campaign.dto.CampaignRecipientDto;
import io.letthemknow.campaign.dto.CampaignRequest;
import io.letthemknow.campaign.dto.CampaignStatsDto;
import io.letthemknow.campaign.dto.PublishRequest;
import io.letthemknow.common.ApiResponse;
import io.letthemknow.common.PageResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.time.OffsetDateTime;

@RestController
@RequestMapping("/api/v1/campaigns")
class CampaignController {

    private final CampaignService service;
    private final RecipientImportService importService;
    private final CampaignRunner runner;

    CampaignController(CampaignService service, RecipientImportService importService, CampaignRunner runner) {
        this.service = service;
        this.importService = importService;
        this.runner = runner;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('ADMIN')")
    ApiResponse<CampaignDto> create(@Valid @RequestBody CampaignRequest request) {
        return ApiResponse.created(service.create(request));
    }

    @GetMapping
    ApiResponse<PageResponse<CampaignDto>> list(@RequestParam(defaultValue = "0") int page,
                                                @RequestParam(defaultValue = "20") int size,
                                                @RequestParam(required = false) CampaignStatus status) {
        return ApiResponse.ok(service.list(page, clamp(size), status));
    }

    @GetMapping("/{id}")
    ApiResponse<CampaignDetailDto> get(@PathVariable Long id) {
        return ApiResponse.ok(service.get(id));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    ApiResponse<CampaignDto> update(@PathVariable Long id, @Valid @RequestBody CampaignRequest request) {
        return ApiResponse.ok(service.update(id, request));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    ApiResponse<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return ApiResponse.ok("Deleted", null);
    }

    @PostMapping(value = "/{id}/upload-recipients", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.ACCEPTED)
    @PreAuthorize("hasRole('ADMIN')")
    ApiResponse<CampaignDto> uploadRecipients(@PathVariable Long id, @RequestPart("file") MultipartFile file) {
        return new ApiResponse<>(202, "Import started", importService.start(id, file));
    }

    @GetMapping("/{id}/recipients")
    ApiResponse<PageResponse<CampaignRecipientDto>> recipients(@PathVariable Long id,
                                                               @RequestParam(required = false) RecipientStatus status,
                                                               @RequestParam(defaultValue = "0") int page,
                                                               @RequestParam(defaultValue = "50") int size) {
        return ApiResponse.ok(service.recipients(id, status, page, clamp(size)));
    }

    /** DRAFT/SCHEDULED → SCHEDULED (future time) or PROCESSING (now). 409 if the status forbids it. */
    @PostMapping("/{id}/publish")
    @PreAuthorize("hasRole('ADMIN')")
    ApiResponse<CampaignDto> publish(@PathVariable Long id,
                                     @RequestBody(required = false) PublishRequest request) {
        OffsetDateTime scheduledAt = request == null ? null : request.scheduledAt();
        return ApiResponse.ok("Published", runner.publish(id, scheduledAt));
    }

    /** AWAITING_RESOLUTION → RETRYING: FAILED recipients go back to PENDING and are re-dispatched. */
    @PostMapping("/{id}/retry-failed")
    @PreAuthorize("hasRole('ADMIN')")
    ApiResponse<CampaignDto> retryFailed(@PathVariable Long id) {
        return ApiResponse.ok("Retrying failed recipients", runner.retryFailed(id));
    }

    /** Any non-final status → TERMINATED; remaining PENDING recipients become CANCELLED. */
    @PostMapping("/{id}/abort")
    @PreAuthorize("hasRole('ADMIN')")
    ApiResponse<CampaignDto> abort(@PathVariable Long id) {
        return ApiResponse.ok("Terminated", runner.abort(id));
    }

    @GetMapping("/{id}/failures")
    ApiResponse<PageResponse<CampaignRecipientDto>> failures(@PathVariable Long id,
                                                             @RequestParam(required = false) String errorCode,
                                                             @RequestParam(defaultValue = "0") int page,
                                                             @RequestParam(defaultValue = "50") int size) {
        return ApiResponse.ok(service.failures(id, errorCode, page, clamp(size)));
    }

    @GetMapping("/{id}/stats")
    ApiResponse<CampaignStatsDto> stats(@PathVariable Long id) {
        return ApiResponse.ok(service.stats(id));
    }

    private static int clamp(int size) {
        return Math.min(Math.max(size, 1), 500);
    }
}
