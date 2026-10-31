package com.servibox.backend.config;

import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;

import java.nio.charset.StandardCharsets;

@Configuration
@Profile("prod")
public class ProductionConfiguration {

    @Bean
    static BeanFactoryPostProcessor productionJwtValidation(Environment environment) {
        return beanFactory -> {
            String secret = environment.getProperty("servibox.jwt.secret", "");
            if (secret.isBlank()
                    || secret.trim().equals("dev-secret-cambiar-en-produccion-minimo-32-caracteres")
                    || secret.getBytes(StandardCharsets.UTF_8).length < 32) {
                throw new IllegalStateException(
                        "JWT_SECRET es obligatorio en prod, debe tener al menos 32 bytes y no puede ser el secreto de desarrollo");
            }
            String platformSecret = environment.getProperty("servibox.platform.jwt.secret", "");
            if (platformSecret.isBlank() || platformSecret.getBytes(StandardCharsets.UTF_8).length < 32
                    || platformSecret.equals(secret) || platformSecret.startsWith("dev-") || platformSecret.startsWith("test-")) {
                throw new IllegalStateException(
                        "PLATFORM_JWT_SECRET es obligatorio en prod, requiere al menos 32 bytes, debe ser exclusivo y no de desarrollo o pruebas");
            }
            String outboxKey = environment.getProperty("servibox.mail.outbox.encryption-key", "");
            if (outboxKey.isBlank() || outboxKey.getBytes(StandardCharsets.UTF_8).length < 32
                    || outboxKey.equals(secret) || outboxKey.equals(platformSecret)
                    || outboxKey.startsWith("dev-") || outboxKey.startsWith("test-")) {
                throw new IllegalStateException(
                        "MAIL_OUTBOX_ENCRYPTION_KEY es obligatoria en prod, requiere al menos 32 bytes, debe ser exclusiva y no de desarrollo o pruebas");
            }
            if (!environment.getProperty("servibox.activation.base-url", "").startsWith("https://")) {
                throw new IllegalStateException("ACTIVATION_BASE_URL es obligatoria en prod y debe usar https");
            }
        };
    }
}
