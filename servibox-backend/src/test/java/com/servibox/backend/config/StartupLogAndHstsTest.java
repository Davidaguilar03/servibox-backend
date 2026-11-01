package com.servibox.backend.config;

import com.servibox.backend.ServiboxApplication;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.security.core.userdetails.UserDetailsService;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tarea 52. Arranca la app completa (servidor en puerto aleatorio) con los perfiles dev y prod
 * sobre H2 y captura el log de arranque: sin "Using generated security password" ni ningun
 * UserDetailsService. Por HTTP local: Strict-Transport-Security de 180 dias sin includeSubDomains
 * ni preload solo en prod (tambien en un 401 de cada cadena), ausente en dev. Claves ficticias.
 */
@ExtendWith(OutputCaptureExtension.class)
class StartupLogAndHstsTest {

    private static final String GENERATED = "Using generated security password";
    private static final String HSTS = "max-age=15552000";

    private static ConfigurableApplicationContext arrancar(String perfiles, String... extra) {
        String[] base = {
                "server.port=0",
                "spring.datasource.url=jdbc:h2:mem:startup-" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
                "spring.datasource.username=sa", "spring.datasource.password=",
                "spring.datasource.driver-class-name=org.h2.Driver",
                "servibox.dev.demo-data=false",
                "servibox.mail.outbox.scheduler-enabled=false"};
        // Como argumentos de linea de comandos: mandan sobre application-prod.properties.
        String[] args = new String[base.length + extra.length];
        for (int i = 0; i < args.length; i++) {
            args[i] = "--" + (i < base.length ? base[i] : extra[i - base.length]);
        }
        return new SpringApplicationBuilder(ServiboxApplication.class).web(WebApplicationType.SERVLET)
                .profiles(perfiles.split(",")).run(args);
    }

    private static HttpResponse<String> get(ConfigurableApplicationContext ctx, String ruta) throws Exception {
        String port = ctx.getEnvironment().getProperty("local.server.port");
        try (HttpClient client = HttpClient.newHttpClient()) {
            return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + ruta)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
        }
    }

    @Test
    void devSinClaveGeneradaNiHsts(CapturedOutput output) throws Exception {
        try (ConfigurableApplicationContext ctx = arrancar("dev")) {
            assertThat(ctx.getBeanNamesForType(UserDetailsService.class)).isEmpty();
            HttpResponse<String> salud = get(ctx, "/api/health");
            assertThat(salud.statusCode()).isEqualTo(200);
            assertThat(salud.headers().firstValue("Strict-Transport-Security")).isEmpty();
        }
        assertThat(output.getAll()).contains("Started ").doesNotContain(GENERATED)
                .doesNotContainIgnoringCase("security password");
    }

    @Test
    void prodSinClaveGeneradaYConHstsEnTodasLasRespuestas(CapturedOutput output) throws Exception {
        try (ConfigurableApplicationContext ctx = arrancar("prod",
                "servibox.jwt.secret=unit-startup-tenant-key-2026-xxxxxxxxxxx",
                "servibox.platform.jwt.secret=unit-startup-platform-key-2026-xxxxxxxxx",
                "servibox.mail.outbox.encryption-key=unit-startup-outbox-key-2026-xxxxxxxxxx",
                "servibox.activation.base-url=https://app.example.invalid")) {
            assertThat(ctx.getBeanNamesForType(UserDetailsService.class)).isEmpty();
            for (String ruta : new String[]{"/api/health", "/api/tenant", "/api/platform/auth/me", "/no-existe"}) {
                HttpResponse<String> r = get(ctx, ruta);
                assertThat(r.headers().firstValue("Strict-Transport-Security")).as(ruta).contains(HSTS);
            }
            assertThat(get(ctx, "/api/tenant").statusCode()).isEqualTo(401);
        }
        assertThat(output.getAll()).contains("Started ").doesNotContain(GENERATED)
                .doesNotContainIgnoringCase("security password");
    }
}
