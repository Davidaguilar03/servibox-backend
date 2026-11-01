package com.servibox.backend.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(OutputCaptureExtension.class)
class ProductionConfigurationTest {

    private static final String PLATFORM_TEST_KEY = "PLATFORM_JWT_SECRET=unit-config-platform-only-key-2026";
    private static final String OUTBOX_TEST_KEY = "MAIL_OUTBOX_ENCRYPTION_KEY=unit-config-outbox-only-key-2026-xx";
    private static final String ACTIVATION_TEST_URL = "ACTIVATION_BASE_URL=https://app.example.invalid";

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withUserConfiguration(ProductionConfiguration.class)
            .withPropertyValues("spring.profiles.active=prod");

    @Test
    void rechazaSecretoAusente() {
        runner.run(context -> assertThat(context).hasFailed());
    }

    @Test
    void rechazaSecretoVacio() {
        runner.withPropertyValues("JWT_SECRET=").run(context ->
                assertThat(context.getStartupFailure()).hasMessageContaining(
                        "JWT_SECRET es obligatorio en prod, debe tener al menos 32 bytes y no puede ser el secreto de desarrollo"));
    }

    @Test
    void rechazaSecretoEnBlanco() {
        runner.withPropertyValues("JWT_SECRET=   ").run(context -> assertThat(context).hasFailed());
    }

    @Test
    void rechazaSecretoDeDesarrollo() {
        runner.withPropertyValues("JWT_SECRET=dev-secret-cambiar-en-produccion-minimo-32-caracteres")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void rechazaSecretoCorto() {
        runner.withPropertyValues("JWT_SECRET=short").run(context -> assertThat(context).hasFailed());
    }

    @Test
    void aceptaSecretoYResuelveConfiguracionProd() {
        runner.withPropertyValues(PLATFORM_TEST_KEY, OUTBOX_TEST_KEY, ACTIVATION_TEST_URL, "JWT_SECRET=" + "x".repeat(32), "PORT=9123",
                "DB_URL=jdbc:postgresql://db:5432/servibox", "DB_USERNAME=owner", "DB_PASSWORD=ephemeral")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    var env = context.getEnvironment();
                    assertThat(env.getProperty("server.port")).isEqualTo("9123");
                    assertThat(env.getProperty("spring.datasource.url")).isEqualTo("jdbc:postgresql://db:5432/servibox");
                    assertThat(env.getProperty("spring.datasource.username")).isEqualTo("owner");
                    assertThat(env.getProperty("spring.datasource.password")).isEqualTo("ephemeral");
                    assertThat(env.getProperty("spring.datasource.driver-class-name")).isEqualTo("org.postgresql.Driver");
                    assertThat(env.getProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo("validate");
                    assertThat(env.getProperty("spring.flyway.enabled")).isEqualTo("true");
                    assertThat(env.getProperty("spring.jpa.open-in-view")).isEqualTo("false");
                });
    }

    @Test
    void puertoLocalPorDefecto() {
        runner.withPropertyValues(PLATFORM_TEST_KEY, OUTBOX_TEST_KEY, ACTIVATION_TEST_URL, "JWT_SECRET=" + "x".repeat(32))
                .run(context -> assertThat(context.getEnvironment().getProperty("server.port")).isEqualTo("8080"));
    }

    @Test
    void serverPortDelEntornoTienePrecedenciaSobrePort() {
        runner.withPropertyValues(PLATFORM_TEST_KEY, OUTBOX_TEST_KEY, ACTIVATION_TEST_URL, "JWT_SECRET=" + "x".repeat(32), "PORT=9123")
                .withInitializer(context -> context.getEnvironment().getPropertySources().addFirst(
                        new org.springframework.core.env.SystemEnvironmentPropertySource(
                                "provider", java.util.Map.of("SERVER_PORT", "9234"))))
                .run(context -> assertThat(context.getEnvironment().getProperty("server.port")).isEqualTo("9234"));
    }

    @Test
    void desarrolloConservaSecretoLocal() {
        runner.withPropertyValues("spring.profiles.active=dev").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getEnvironment().getProperty("servibox.jwt.secret"))
                    .isEqualTo("dev-secret-cambiar-en-produccion-minimo-32-caracteres");
        });
    }

    @Test
    void plataformaRechazaAusenteVaciaCortaBlancaCompartidaYDefaults() {
        runner.withPropertyValues("JWT_SECRET=" + "x".repeat(32))
                .run(context -> assertThat(context).hasFailed());
        for (String secret : java.util.List.of("", " ", "short", "x".repeat(32),
                "dev-only-platform-secret-never-for-production-2026",
                "test-only-platform-secret-never-for-production-2026")) {
            runner.withPropertyValues("JWT_SECRET=" + "x".repeat(32), "PLATFORM_JWT_SECRET=" + secret)
                    .run(context -> {
                        assertThat(context).hasFailed();
                        assertThat(context.getStartupFailure()).hasMessageContaining("PLATFORM_JWT_SECRET es obligatorio");
                    });
        }
    }

    @Test
    void outboxRechazaClaveAusenteCortaCompartidaODeDesarrolloYUrlSinHttps() {
        String jwt = "JWT_SECRET=" + "x".repeat(32);
        for (String key : java.util.List.of("", "short", "x".repeat(32), "unit-config-platform-only-key-2026",
                "dev-only-outbox-key-never-for-production-2026", "test-only-outbox-key-never-for-production-2026")) {
            runner.withPropertyValues(jwt, PLATFORM_TEST_KEY, ACTIVATION_TEST_URL, "MAIL_OUTBOX_ENCRYPTION_KEY=" + key)
                    .run(context -> assertThat(context.getStartupFailure())
                            .hasMessageContaining("MAIL_OUTBOX_ENCRYPTION_KEY es obligatoria en prod"));
        }
        for (String url : java.util.List.of("", "http://app.example.invalid")) {
            runner.withPropertyValues(jwt, PLATFORM_TEST_KEY, OUTBOX_TEST_KEY, "ACTIVATION_BASE_URL=" + url)
                    .run(context -> assertThat(context.getStartupFailure())
                            .hasMessageContaining("ACTIVATION_BASE_URL es obligatoria en prod y debe usar https"));
        }
    }

    @Test
    void claveAnteriorDelOutboxOpcionalPeroValidadaEnProd() {
        String jwt = "JWT_SECRET=" + "x".repeat(32);
        runner.withPropertyValues(jwt, PLATFORM_TEST_KEY, OUTBOX_TEST_KEY, ACTIVATION_TEST_URL,
                        "MAIL_OUTBOX_ENCRYPTION_KEY_PREVIOUS=unit-config-outbox-previous-key-2026-x")
                .run(context -> assertThat(context).hasNotFailed());
        for (String previa : java.util.List.of("short", "x".repeat(32), "unit-config-platform-only-key-2026",
                "unit-config-outbox-only-key-2026-xx", "dev-only-outbox-key-never-for-production-2026",
                "test-only-outbox-key-never-for-production-2026")) {
            runner.withPropertyValues(jwt, PLATFORM_TEST_KEY, OUTBOX_TEST_KEY, ACTIVATION_TEST_URL,
                            "MAIL_OUTBOX_ENCRYPTION_KEY_PREVIOUS=" + previa)
                    .run(context -> assertThat(context.getStartupFailure())
                            .hasMessageContaining("MAIL_OUTBOX_ENCRYPTION_KEY_PREVIOUS es opcional en prod"));
        }
    }

    @Test
    void avisaSinCabeceraDeIpDelClienteSinFallarElArranque(CapturedOutput output) {
        runner.withPropertyValues(PLATFORM_TEST_KEY, OUTBOX_TEST_KEY, ACTIVATION_TEST_URL, "JWT_SECRET=" + "x".repeat(32))
                .run(context -> assertThat(context).hasNotFailed());
        assertThat(output).contains("WARN").contains(ProductionConfiguration.CLIENT_IP_HEADER_WARNING);
    }

    @Test
    void noAvisaConCabeceraDeIpDelClienteConfigurada(CapturedOutput output) {
        runner.withPropertyValues(PLATFORM_TEST_KEY, OUTBOX_TEST_KEY, ACTIVATION_TEST_URL, "JWT_SECRET=" + "x".repeat(32),
                        "PUBLIC_RATE_LIMIT_CLIENT_IP_HEADER=X-Real-IP")
                .run(context -> assertThat(context).hasNotFailed());
        assertThat(output).doesNotContain(ProductionConfiguration.CLIENT_IP_HEADER_WARNING);
    }

    @Test
    void corsAceptaOrigenesHttpsExactos() {
        runner.withPropertyValues(PLATFORM_TEST_KEY, OUTBOX_TEST_KEY, ACTIVATION_TEST_URL, "JWT_SECRET=" + "x".repeat(32),
                        "CORS_ALLOWED_ORIGINS=https://servibox.co, https://www.servibox.co")
                .run(context -> assertThat(context).hasNotFailed());
        runner.withPropertyValues(PLATFORM_TEST_KEY, OUTBOX_TEST_KEY, ACTIVATION_TEST_URL, "JWT_SECRET=" + "x".repeat(32))
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void corsRechazaComodinesHttpYRutas() {
        for (String origen : new String[] {"*", "https://*.servibox.co", "http://servibox.co", "https://servibox.co/",
                "https://servibox.co/app", "servibox.co"}) {
            runner.withPropertyValues(PLATFORM_TEST_KEY, OUTBOX_TEST_KEY, ACTIVATION_TEST_URL, "JWT_SECRET=" + "x".repeat(32),
                            "CORS_ALLOWED_ORIGINS=https://servibox.co," + origen)
                    .run(context -> assertThat(context.getStartupFailure()).hasMessageContaining(
                            "CORS_ALLOWED_ORIGINS acepta solo origenes https exactos separados por coma, sin comodines ni rutas"));
        }
    }
}
