package io.letthemknow.config;

import io.letthemknow.channel.line.LineProperties;
import io.letthemknow.dispatch.DispatchProperties;
import io.letthemknow.integration.IntegrationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Single place where every {@code ltk.*} properties record is bound. */
@Configuration
@EnableConfigurationProperties({
        LtkSecurityProperties.class,
        DispatchProperties.class,
        LineProperties.class,
        IntegrationProperties.class})
class LtkPropertiesConfig {
}
