package io.letthemknow.channel;

import io.letthemknow.channel.dto.ChannelConfigDto;
import io.letthemknow.channel.dto.LineChannelRequest;
import io.letthemknow.channel.dto.SmtpChannelRequest;
import io.letthemknow.common.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/channels")
class ChannelConfigController {

    private final ChannelConfigService service;

    ChannelConfigController(ChannelConfigService service) {
        this.service = service;
    }

    @GetMapping
    ApiResponse<List<ChannelConfigDto>> list() {
        return ApiResponse.ok(service.list());
    }

    @PostMapping("/line")
    @PreAuthorize("hasRole('ADMIN')")
    ApiResponse<ChannelConfigDto> upsertLine(@Valid @RequestBody LineChannelRequest request) {
        return ApiResponse.ok("Saved", service.upsertLine(request));
    }

    @PostMapping("/smtp")
    @PreAuthorize("hasRole('ADMIN')")
    ApiResponse<ChannelConfigDto> upsertSmtp(@Valid @RequestBody SmtpChannelRequest request) {
        return ApiResponse.ok("Saved; test email delivered", service.upsertSmtp(request));
    }
}
