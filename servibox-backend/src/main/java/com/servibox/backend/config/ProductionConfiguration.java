package com.servibox.backend.config;

import com.servibox.backend.registration.RegistrationRequestService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;

import java.nio.charset.StandardCharsets;

@Slf4j
@Configuration
@Profile("prod")
public class ProductionConfiguration {

    static final String CLIENT_IP_HEADER_WARNING = "PUBLIC_RATE_LIMIT_CLIENT_IP_HEADER esta vacia: los limites por IP"
            + " (rutas publicas de solicitudes y logins) usan la IP del socket. Detras de un proxy (Caddy) esa IP es"
            + " la del proxy y todos los visitantes compartirian una sola cuota: un atacante bloquearia el login de"
            + " todos. Con deploy/docker-compose.prod.yml queda fijada en X-Real-IP; comprueba con un X-Real-IP"
            + " falso que la cuota no cambia. Ver docs/deploy/VPS.md";

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
            String previousKey = environment.getProperty("servibox.mail.outbox.encryption-key-previous", "");
            if (!previousKey.isEmpty() && (previousKey.isBlank() || previousKey.getBytes(StandardCharsets.UTF_8).length < 32
                    || previousKey.equals(outboxKey) || previousKey.equals(secret) || previousKey.equals(platformSecret)
                    || previousKey.startsWith("dev-") || previousKey.startsWith("test-"))) {
                throw new IllegalStateException(
                        "MAIL_OUTBOX_ENCRYPTION_KEY_PREVIOUS es opcional en prod; si se define requiere al menos 32 bytes, debe ser distinta de la clave actual y de las claves JWT y no de desarrollo o pruebas");
            }
            if (!environment.getProperty("servibox.activation.base-url", "").startsWith("https://")) {
                throw new IllegalStateException("ACTIVATION_BASE_URL es obligatoria en prod y debe usar https");
            }
            if (!RegistrationRequestService.esVersionDePolitica(
                    environment.getProperty("servibox.registration-requests.policy-version", ""))) {
                throw new IllegalStateException("REGISTRATION_POLICY_VERSION debe ser una fecha valida con formato AAAA-MM-DD");
            }
            for (String origin : SecurityConfig.allowedOrigins(environment)) {
                if (!origin.matches("https://[A-Za-z0-9.-]+(:[0-9]+)?")) {
                    throw new IllegalStateException(
                            "CORS_ALLOWED_ORIGINS acepta solo origenes https exactos separados por coma, sin comodines ni rutas");
                }
            }
            boolean limitsOn = environment.getProperty("servibox.rate-limit.enabled", Boolean.class, true)
                    || environment.getProperty("servibox.login-throttle.enabled", Boolean.class, true);
            if (limitsOn && environment.getProperty("servibox.rate-limit.client-ip-header", "").isBlank()) {
                log.warn(CLIENT_IP_HEADER_WARNING);
            }
        };
    }
}
