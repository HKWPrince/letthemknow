package io.letthemknow.auth;

import io.letthemknow.auth.dto.LoginRequest;
import io.letthemknow.auth.dto.LoginResponse;
import io.letthemknow.auth.dto.MeResponse;
import io.letthemknow.auth.dto.SignupRequest;
import io.letthemknow.common.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
class AuthController {

    private final AuthService authService;

    AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/login")
    ApiResponse<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
        return ApiResponse.ok(authService.login(request));
    }

    /** Self-service tenant creation, gated by the shared signup code. Returns a session, as login does. */
    @PostMapping("/signup")
    ApiResponse<LoginResponse> signup(@Valid @RequestBody SignupRequest request) {
        return ApiResponse.ok(authService.signup(request));
    }

    @GetMapping("/me")
    ApiResponse<MeResponse> me() {
        return ApiResponse.ok(authService.me(CurrentPrincipal.require()));
    }
}
