package io.letthemknow.template;

import io.letthemknow.channel.ChannelType;
import io.letthemknow.common.ApiResponse;
import io.letthemknow.common.PageResponse;
import io.letthemknow.template.dto.MessageTemplateDto;
import io.letthemknow.template.dto.MessageTemplateRequest;
import io.letthemknow.template.dto.TemplatePreviewDto;
import io.letthemknow.template.dto.TemplatePreviewRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/templates")
class MessageTemplateController {

    private final MessageTemplateService service;

    MessageTemplateController(MessageTemplateService service) {
        this.service = service;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('ADMIN')")
    ApiResponse<MessageTemplateDto> create(@Valid @RequestBody MessageTemplateRequest request) {
        return ApiResponse.created(service.create(request));
    }

    @GetMapping
    ApiResponse<PageResponse<MessageTemplateDto>> list(@RequestParam(defaultValue = "0") int page,
                                                       @RequestParam(defaultValue = "20") int size,
                                                       @RequestParam(required = false) ChannelType channel) {
        return ApiResponse.ok(service.list(page, Math.min(Math.max(size, 1), 200), channel));
    }

    @GetMapping("/{id}")
    ApiResponse<MessageTemplateDto> get(@PathVariable Long id) {
        return ApiResponse.ok(service.get(id));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    ApiResponse<MessageTemplateDto> update(@PathVariable Long id, @Valid @RequestBody MessageTemplateRequest request) {
        return ApiResponse.ok(service.update(id, request));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    ApiResponse<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return ApiResponse.ok("Deleted", null);
    }

    @PostMapping("/{id}/preview")
    ApiResponse<TemplatePreviewDto> preview(@PathVariable Long id, @RequestBody(required = false) TemplatePreviewRequest request) {
        Map<String, String> params = request == null || request.params() == null ? Map.of() : request.params();
        return ApiResponse.ok(service.preview(id, params));
    }
}
