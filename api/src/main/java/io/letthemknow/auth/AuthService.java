package io.letthemknow.auth;

import io.letthemknow.auth.dto.LoginRequest;
import io.letthemknow.auth.dto.LoginResponse;
import io.letthemknow.auth.dto.MeResponse;
import io.letthemknow.auth.dto.SignupRequest;
import io.letthemknow.common.ApiException;
import io.letthemknow.common.ErrorCode;
import io.letthemknow.common.tenant.SystemTenantScope;
import io.letthemknow.config.LtkSecurityProperties;
import io.letthemknow.tenant.Tenant;
import io.letthemknow.tenant.TenantMapper;
import io.letthemknow.tenant.TenantProvisioningService;
import io.letthemknow.tenant.TenantRepository;
import io.letthemknow.tenant.TenantStatus;
import io.letthemknow.tenant.User;
import io.letthemknow.tenant.UserMapper;
import io.letthemknow.tenant.UserRepository;
import io.letthemknow.tenant.UserStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

@Service
public class AuthService {

    private static final String INVALID_CREDENTIALS = "Invalid email or password";

    /**
     * One message for both "signup is switched off" and "that code is wrong". Telling them apart would
     * let anyone probe whether signup exists here, and confirm when a guessed code was the only thing
     * missing. The caller learns nothing either way.
     */
    private static final String SIGNUP_REFUSED = "Signup is not available with that code";

    private final UserRepository userRepository;
    private final TenantRepository tenantRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final UserMapper userMapper;
    private final TenantMapper tenantMapper;
    private final TenantProvisioningService provisioningService;
    private final LtkSecurityProperties securityProperties;

    public AuthService(UserRepository userRepository, TenantRepository tenantRepository,
                       PasswordEncoder passwordEncoder, JwtService jwtService,
                       UserMapper userMapper, TenantMapper tenantMapper,
                       TenantProvisioningService provisioningService,
                       LtkSecurityProperties securityProperties) {
        this.userRepository = userRepository;
        this.tenantRepository = tenantRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.userMapper = userMapper;
        this.tenantMapper = tenantMapper;
        this.provisioningService = provisioningService;
        this.securityProperties = securityProperties;
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

    /**
     * Creates a tenant with its first ADMIN and signs them straight in.
     *
     * <p>Gated by the shared signup code. Provisioning itself is
     * {@link TenantProvisioningService#provision}, the same path the CLI uses, so the rules about
     * validation, duplicate names and system scope live in exactly one place.
     */
    @Transactional
    public LoginResponse signup(SignupRequest request) {
        requireValidSignupCode(request.code());
        TenantProvisioningService.ProvisionedTenant provisioned =
                provisioningService.provision(request.tenantName().trim(), request.email().trim(), request.password());
        JwtService.IssuedToken issued = jwtService.issue(provisioned.admin());
        return new LoginResponse(issued.token(), issued.expiresAt(),
                userMapper.toDto(provisioned.admin()), tenantMapper.toDto(provisioned.tenant()));
    }

    /**
     * Constant-time comparison, matching {@code ApiKeyService}. A plain {@code equals} returns as soon as
     * two bytes differ, so response time would leak how much of a guessed code was correct.
     */
    private void requireValidSignupCode(String presented) {
        byte[] expected = securityProperties.signupEnabled()
                ? securityProperties.signupCode().getBytes(StandardCharsets.UTF_8)
                : new byte[0];
        byte[] actual = presented == null ? new byte[0] : presented.getBytes(StandardCharsets.UTF_8);
        // Compare even when signup is off, so a disabled deployment answers no faster than a wrong code.
        boolean matches = MessageDigest.isEqual(expected, actual);
        if (!securityProperties.signupEnabled() || !matches) {
            throw new ApiException(ErrorCode.FORBIDDEN, SIGNUP_REFUSED);
        }
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
