package io.letthemknow.common.crypto;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

/**
 * AES-256-GCM with a random 12-byte IV and 128-bit tag.
 * Output format: {@code v1:<base64(iv || ciphertext || tag)>}. Never logs plaintext.
 */
public final class AesGcmEncryptor {

    static final String VERSION_PREFIX = "v1:";
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final int KEY_BYTES = 32;

    private final SecretKey key;
    private final SecureRandom random = new SecureRandom();

    public AesGcmEncryptor(byte[] masterKey) {
        if (masterKey == null || masterKey.length != KEY_BYTES) {
            throw new IllegalArgumentException("Master key must be exactly 32 bytes (AES-256)");
        }
        this.key = new SecretKeySpec(masterKey, "AES");
    }

    public static AesGcmEncryptor fromBase64(String base64Key) {
        if (base64Key == null || base64Key.isBlank()) {
            throw new IllegalArgumentException("LTK_MASTER_KEY is not set (base64 of 32 random bytes)");
        }
        byte[] raw;
        try {
            raw = Base64.getDecoder().decode(base64Key.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("LTK_MASTER_KEY is not valid base64", e);
        }
        return new AesGcmEncryptor(raw);
    }

    public String encrypt(String plaintext) {
        if (plaintext == null) {
            return null;
        }
        try {
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] ctAndTag = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[iv.length + ctAndTag.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(ctAndTag, 0, out, iv.length, ctAndTag.length);
            return VERSION_PREFIX + Base64.getEncoder().encodeToString(out);
        } catch (GeneralSecurityException e) {
            throw new CryptoException("Encryption failed", e);
        }
    }

    public String decrypt(String token) {
        if (token == null) {
            return null;
        }
        if (!token.startsWith(VERSION_PREFIX)) {
            throw new CryptoException("Unsupported ciphertext format");
        }
        byte[] data;
        try {
            data = Base64.getDecoder().decode(token.substring(VERSION_PREFIX.length()));
        } catch (IllegalArgumentException e) {
            throw new CryptoException("Ciphertext is not valid base64", e);
        }
        if (data.length < IV_BYTES + TAG_BITS / 8) {
            throw new CryptoException("Ciphertext too short");
        }
        try {
            byte[] iv = Arrays.copyOfRange(data, 0, IV_BYTES);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] plain = cipher.doFinal(data, IV_BYTES, data.length - IV_BYTES);
            return new String(plain, StandardCharsets.UTF_8);
        } catch (GeneralSecurityException e) {
            // AEADBadTagException: tampered ciphertext or wrong key
            throw new CryptoException("Decryption failed: ciphertext rejected", e);
        }
    }

    public static boolean isEncrypted(String value) {
        return value != null && value.startsWith(VERSION_PREFIX);
    }
}
