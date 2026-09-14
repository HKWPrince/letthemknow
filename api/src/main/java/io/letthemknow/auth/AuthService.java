package io.letthemknow.auth;

import io.letthemknow.auth.dto.LoginRequest;
import io.letthemknow.auth.dto.LoginResponse;
import io.letthemknow.auth.dto.MeResponse;
import io.letthemknow.common.ApiException;
import io.letthemknow.common.ErrorCode;
import io.letthemknow.common.tenant.SystemTenantScope;
import io.letthemknow.tenant.Tenant;
import io.letthemknow.tenant.TenantMapper;
import io.letthemknow.tenant.TenantRepository;
import io.letthemknow.tenant.TenantStatus;
import io.letthemknow.tenant.User;
import io.letthemknow.tenant.UserMapper;
import io.letthemknow.tenant.UserRepository;
import io.letthemknow.tenant.UserStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class AuthService {

    private static final String INVALID_CREDENTIALS = "Invalid email or password";

    private final UserRepository userRepository;
    private final TenantRepository tenantRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final UserMapper userMapper;
    private final TenantMapper tenantMapper;

    public AuthService(UserRepository userRepository, TenantRepository tenantRepository,
                       PasswordEncoder passwordEncoder, JwtService jwtService,
                       UserMapper userMapper, TenantMapper tenantMapper) {
        this.userRepository = userRepository;
        this.tenantRepository = tenantRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.userMapper = userMapper;
        this.tenantMapper = tenantMapper;
    }

    /** No tenant context exists yet, so the lookup runs in system scope. */
    @Transactional(readOnly = true)
    public LoginResponse login(LoginRequest request) {
        return SystemTenantScope.runAsSystem(() -> {
            List<User> candidates = userRepository.findAllByEmailIgnoreCase(request.email().trim());
            if (request.tenant() != null && !request.tenant().isBlank()) {
                Tenant tenant = tenantRepository.findByName(request.tenant().trim())
                        .orElseThrow(() -> new ApiException(ErrorCode.UNAUTHORIZED, INVALID_CREDENTIALS));
                candidates = candidates.stream().filter(u -> u.getTenantId().equals(tenant.getId())).toList();
            }
            if (candidates.isEmpty()) {
                // Burn a hash comparison so timing does not reveal whether the email exists.
                passwordEncoder.matches(request.password(), "$2a$10$7EqJtq98hPqEX7fNZaFWoOhi5XkKDeIzGw1V7YUrvYpuG3H5UBl8W");
                throw new ApiException(ErrorCode.UNAUTHORIZED, INVALID_CREDENTIALS);
            }
            if (candidates.size() > 1) {
                throw new ApiException(ErrorCode.BAD_REQUEST,
                        "This email belongs to several tenants; specify the tenant name");
            }
            User user = candidates.get(0);
            if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
                throw new ApiException(ErrorCode.UNAUTHORIZED, INVALID_CREDENTIALS);
            }
            if (user.getStatus() != UserStatus.ACTIVE) {
                throw new ApiException(ErrorCode.UNAUTHORIZED, "User is disabled");
            }
            Tenant tenant = tenantRepository.findById(user.getTenantId())
                    .orElseThrow(() -> new ApiException(ErrorCode.UNAUTHORIZED, INVALID_CREDENTIALS));
            if (tenant.getStatus() != TenantStatus.ACTIVE) {
                throw new ApiException(ErrorCode.FORBIDDEN, "Tenant is suspended");
            }
            JwtService.IssuedToken issued = jwtService.issue(user);
            return new LoginResponse(issued.token(), issued.expiresAt(),
                    userMapper.toDto(user), tenantMapper.toDto(tenant));
        });
    }

    /** Runs inside the request's tenant context (bound by TenantContextFilter). */
    @Transactional(readOnly = true)
    public MeResponse me(LtkPrincipal principal) {
        if (principal.authType() != AuthType.JWT) {
            throw new ApiException(ErrorCode.FORBIDDEN, "Not a user session");
        }
        User user = userRepository.findScopedById(principal.userId())
                .orElseThrow(() -> new ApiException(ErrorCode.UNAUTHORIZED, "User no longer exists"));
        Tenant tenant = tenantRepository.findById(principal.tenantId())
                .orElseThrow(() -> new ApiException(ErrorCode.UNAUTHORIZED, "Tenant no longer exists"));
        return new MeResponse(userMapper.toDto(user), tenantMapper.toDto(tenant));
    }
}
