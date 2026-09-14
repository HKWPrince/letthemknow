package io.letthemknow.common.crypto;

import org.junit.jupiter.api.Test;

import java.security.SecureRandom;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AesGcmEncryptorTest {

    private static byte[] randomKey() {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        return key;
    }

    private final AesGcmEncryptor encryptor = new AesGcmEncryptor(randomKey());

    @Test
    void roundTripsUnicodeText() {
        String plain = "s3cret-密碼-🔐";
        String token = encryptor.encrypt(plain);

        assertThat(token).startsWith("v1:").doesNotContain(plain);
        assertThat(encryptor.decrypt(token)).isEqualTo(plain);
    }

    @Test
    void usesFreshIvPerEncryption() {
        String a = encryptor.encrypt("same");
        String b = encryptor.encrypt("same");

        assertThat(a).isNotEqualTo(b);
        assertThat(encryptor.decrypt(a)).isEqualTo("same");
        assertThat(encryptor.decrypt(b)).isEqualTo("same");
    }

    @Test
    void rejectsTamperedCiphertext() {
        String token = encryptor.encrypt("do not touch");
        byte[] raw = Base64.getDecoder().decode(token.substring(3));
        raw[raw.length / 2] ^= 0x01;
        String tampered = "v1:" + Base64.getEncoder().encodeToString(raw);

        assertThatThrownBy(() -> encryptor.decrypt(tampered))
                .isInstanceOf(CryptoException.class)
                .hasMessageContaining("rejected");
    }

    @Test
    void rejectsTamperedTag() {
        String token = encryptor.encrypt("tag check");
        byte[] raw = Base64.getDecoder().decode(token.substring(3));
        raw[raw.length - 1] ^= 0x80;
        String tampered = "v1:" + Base64.getEncoder().encodeToString(raw);

        assertThatThrownBy(() -> encryptor.decrypt(tampered)).isInstanceOf(CryptoException.class);
    }

    @Test
    void rejectsWrongKey() {
        String token = encryptor.encrypt("other key");
        AesGcmEncryptor other = new AesGcmEncryptor(randomKey());

        assertThatThrownBy(() -> other.decrypt(token)).isInstanceOf(CryptoException.class);
    }

    @Test
    void rejectsUnknownFormatAndGarbage() {
        assertThatThrownBy(() -> encryptor.decrypt("plaintext")).isInstanceOf(CryptoException.class);
        assertThatThrownBy(() -> encryptor.decrypt("v1:not-base64!")).isInstanceOf(CryptoException.class);
        assertThatThrownBy(() -> encryptor.decrypt("v1:AAAA")).isInstanceOf(CryptoException.class);
    }

    @Test
    void requiresThirtyTwoByteKey() {
        assertThatThrownBy(() -> new AesGcmEncryptor(new byte[16])).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AesGcmEncryptor.fromBase64("")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AesGcmEncryptor.fromBase64("***")).isInstanceOf(IllegalArgumentException.class);
        assertThat(AesGcmEncryptor.fromBase64(Base64.getEncoder().encodeToString(randomKey()))).isNotNull();
    }

    @Test
    void nullPassesThrough() {
        assertThat(encryptor.encrypt(null)).isNull();
        assertThat(encryptor.decrypt(null)).isNull();
    }
}
