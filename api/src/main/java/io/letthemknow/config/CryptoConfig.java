package io.letthemknow.config;

import io.letthemknow.common.crypto.AesGcmEncryptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

@Configuration
class CryptoConfig {

    /** Fails fast at startup when LTK_MASTER_KEY is missing or malformed. */
    @Bean
    AesGcmEncryptor aesGcmEncryptor(LtkSecurityProperties props) {
        return AesGcmEncryptor.fromBase64(props.masterKey());
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
