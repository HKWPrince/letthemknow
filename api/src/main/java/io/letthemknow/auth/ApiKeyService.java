package io.letthemknow.auth;

import io.letthemknow.auth.dto.ApiKeyCreatedDto;
import io.letthemknow.auth.dto.ApiKeyDto;
import io.letthemknow.common.ApiException;
import io.letthemknow.common.ErrorCode;
import io.letthemknow.common.tenant.SystemTenantScope;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * API keys: {@code ltk_<8 char prefix>_<32 char random>}. Only the SHA-256 hash is stored; lookup by
 * prefix, then constant-time compare. The plaintext is returned exactly once, on creation.
 */
@Service
public class ApiKeyService {

    static final String KEY_PREFIX = "ltk_";
    private static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
    private static final int PREFIX_LENGTH = 8;
    private static final int SECRET_LENGTH = 32;
    private static final Pattern KEY_PATTERN = Pattern.compile("^ltk_([A-Za-z0-9]{8})_([A-Za-z0-9]{32})$");
    private static final Duration LAST_USED_GRANULARITY = Duration.ofSeconds(60);

    private final ApiKeyRepository repository;
    private final ApiKeyMapper mapper;
    private final SecureRandom random = new SecureRandom();

    public ApiKeyService(ApiKeyRepository repository, ApiKeyMapper mapper) {
        this.repository = repository;
        this.mapper = mapper;
    }

    @Transactional
    public ApiKeyCreatedDto create(String name) {
        String prefix = randomString(PREFIX_LENGTH);
        String secret = randomString(SECRET_LENGTH);
        String rawKey = KEY_PREFIX + prefix + "_" + secret;
        ApiKey saved = repository.save(new ApiKey(name, prefix, sha256Hex(rawKey)));
        return new ApiKeyCreatedDto(mapper.toDto(saved), rawKey);
    }

    @Transactional(readOnly = true)
    public List<ApiKeyDto> list() {
        return repository.findAllByOrderByCreatedAtDesc().stream().map(mapper::toDto).toList();
    }

    @Transactional
    public ApiKeyDto revoke(Long id) {
        ApiKey apiKey = repository.findScopedById(id)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "API key not found"));
        if (apiKey.isActive()) {
            apiKey.revoke();
        }
        return mapper.toDto(repository.save(apiKey));
    }

    /**
     * Resolves a raw key to a principal. Runs in system scope because no tenant is known yet;
     * touches {@code last_used_at} at most once per minute.
     */
    public Optional<LtkPrincipal> authenticate(String rawKey) {
        Matcher m = KEY_PATTERN.matcher(rawKey);
        if (!m.matches()) {
            return Optional.empty();
        }
        String prefix = m.group(1);
        byte[] presented = sha256Hex(rawKey).getBytes(StandardCharsets.UTF_8);
        return SystemTenantScope.runAsSystem(() -> repository
                .findByApiKeyPrefixAndStatus(prefix, ApiKeyStatus.ACTIVE).stream()
                .filter(k -> MessageDigest.isEqual(k.getApiKeyHash().getBytes(StandardCharsets.UTF_8), presented))
                .findFirst()
                .map(this::touch)
                .map(k -> LtkPrincipal.ofApiKey(k.getTenantId(), k.getId())));
    }

    private ApiKey touch(ApiKey key) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        if (key.getLastUsedAt() == null || key.getLastUsedAt().plus(LAST_USED_GRANULARITY).isBefore(now)) {
            key.setLastUsedAt(now);
            return repository.save(key);
        }
        return key;
    }

    static String sha256Hex(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private String randomString(int length) {
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        }
        return sb.toString();
    }
}
