package io.letthemknow.common;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

@RestController
@RequestMapping("/api/v1/health")
class HealthController {

    record HealthResponse(String status, OffsetDateTime time) {}

    @GetMapping
    ApiResponse<HealthResponse> health() {
        return ApiResponse.ok(new HealthResponse("UP", OffsetDateTime.now(ZoneOffset.UTC)));
    }
}
