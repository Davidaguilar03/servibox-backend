package com.servibox.backend.auth;

import com.servibox.backend.auth.entity.Role;
import com.servibox.backend.auth.entity.User;
import com.servibox.backend.auth.repository.UserRepository;
import com.servibox.backend.platform.PlatformAdminRepository;
import com.servibox.backend.platform.PlatformBootstrapService;
import com.servibox.backend.registration.PublicRateLimiter;
import com.servibox.backend.tenant.Tenant;
import com.servibox.backend.tenant.TenantContext;
import com.servibox.backend.tenant.TenantRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Proteccion contra fuerza bruta de ambos logins, sin confiar en cabeceras de proxy.
 * Limites de prueba: par 3, cuenta 5, IP 8 en 15 minutos. El reloj solo avanza antes de cada
 * prueba, una ventana completa, para que ninguna herede contadores de otra.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "servibox.login-throttle.enabled=true",
        "servibox.login-throttle.window=15m",
        "servibox.login-throttle.ip-account-limit=3",
        "servibox.login-throttle.account-limit=5",
        "servibox.login-throttle.ip-limit=8"})
class LoginThrottleTest {

    static final String MENSAJE_429 = "Demasiados intentos de inicio de sesion, espera unos minutos e intenta de nuevo";
    static final String TENANT_LOGIN = "/api/auth/login";
    static final String PLATFORM_LOGIN = "/api/platform/auth/login";
    static final String PASSWORD = "clave-correcta-1";
    static final String PLATFORM_EMAIL = "ops@example.invalid";
    static final String PLATFORM_PASSWORD = "ephemeral-test-password";

    static final LoginThrottleUnitTest.TestClock CLOCK = new LoginThrottleUnitTest.TestClock();

    @TestConfiguration
    static class RelojControlado {
        @Bean
        @Primary
        LoginThrottle throttleConReloj(PublicRateLimiter ips,
                @Value("${servibox.login-throttle.window}") Duration window,
                @Value("${servibox.login-throttle.ip-account-limit}") int pair,
                @Value("${servibox.login-throttle.account-limit}") int account,
                @Value("${servibox.login-throttle.ip-limit}") int ip,
                @Value("${servibox.login-throttle.max-entries}") int maxEntries) {
            return new LoginThrottle(true, window, pair, account, ip, maxEntries, ips, CLOCK);
        }
    }

    @Autowired MockMvc mvc;
    @Autowired TenantRepository tenants;
    @Autowired UserRepository users;
    @Autowired PasswordEncoder encoder;
    @Autowired PlatformBootstrapService bootstrap;
    @Autowired PlatformAdminRepository admins;

    private final List<Long> tenantIds = new ArrayList<>();

    @BeforeEach
    void seed() {
        CLOCK.advance(Duration.ofMinutes(15));
        Long activo = tenant("taller-bf", true);
        user(activo, "cajero", true);
        user(activo, "inactivo", false);
        user(tenant("taller-suspendido", false), "admin", true);
        bootstrap.create(PLATFORM_EMAIL, PLATFORM_PASSWORD.toCharArray());
    }

    @AfterEach
    void cleanup() {
        TenantContext.clear();
        users.deleteAll();
        tenantIds.forEach(tenants::deleteById);
        admins.deleteAll();
    }

    private Long tenant(String slug, boolean active) {
        Tenant t = new Tenant();
        t.setName("Fixture " + slug);
        t.setSlug(slug);
        t.setActive(active);
        Long id = tenants.save(t).getId();
        tenantIds.add(id);
        return id;
    }

    private void user(Long tenantId, String username, boolean active) {
        TenantContext.setTenantId(tenantId);
        try {
            User u = new User();
            u.setUsername(username);
            u.setPasswordHash(encoder.encode(PASSWORD));
            u.setActive(active);
            u.setRole(Role.ADMIN);
            users.save(u);
        } finally {
            TenantContext.clear();
        }
    }

    static MockHttpServletRequestBuilder desde(MockHttpServletRequestBuilder b, String ip) {
        return b.with(r -> {
            r.setRemoteAddr(ip);
            return r;
        });
    }

    ResultActions tenantLogin(String ip, String slug, String username, String password, String... headers) throws Exception {
        MockHttpServletRequestBuilder b = desde(post(TENANT_LOGIN), ip).contentType(MediaType.APPLICATION_JSON)
                .content("{\"tenantSlug\":\"%s\",\"username\":\"%s\",\"password\":\"%s\"}".formatted(slug, username, password));
        for (int i = 0; i < headers.length; i += 2) {
            b.header(headers[i], headers[i + 1]);
        }
        return mvc.perform(b);
    }

    ResultActions platformLogin(String ip, String email, String password) throws Exception {
        return mvc.perform(desde(post(PLATFORM_LOGIN), ip).contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, password)));
    }

    static void es401(ResultActions r) throws Exception {
        r.andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error").value("Credenciales invalidas"))
                .andExpect(jsonPath("$.status").value(401));
    }

    static void es429(ResultActions r) throws Exception {
        r.andExpect(status().isTooManyRequests()).andExpect(header().string("Retry-After", "900"))
                .andExpect(jsonPath("$.error").value(MENSAJE_429)).andExpect(jsonPath("$.status").value(429));
    }

    @Test
    void loginTenantTrasTresFallosResponde429AunqueLaContrasenaSeaCorrecta() throws Exception {
        for (int i = 0; i < 3; i++) {
            es401(tenantLogin("10.3.0.1", "taller-bf", "cajero", "mala-" + i));
        }
        es429(tenantLogin("10.3.0.1", "taller-bf", "cajero", PASSWORD));
        // Misma cuenta escrita con mayusculas y espacios: misma clave.
        es429(tenantLogin("10.3.0.1", " TALLER-BF", "Cajero ", PASSWORD));
    }

    @Test
    void loginPlataformaTrasTresFallosResponde429AunqueLaContrasenaSeaCorrecta() throws Exception {
        for (int i = 0; i < 3; i++) {
            es401(platformLogin("10.3.0.2", PLATFORM_EMAIL, "mala-clave-" + i));
        }
        es429(platformLogin("10.3.0.2", PLATFORM_EMAIL, PLATFORM_PASSWORD));
        es429(platformLogin("10.3.0.2", " OPS@Example.invalid", PLATFORM_PASSWORD));
    }

    @Test
    void elExitoAntesDelLimiteFuncionaYLimpiaElPar() throws Exception {
        es401(tenantLogin("10.3.0.3", "taller-bf", "cajero", "mala"));
        es401(tenantLogin("10.3.0.3", "taller-bf", "cajero", "mala"));
        tenantLogin("10.3.0.3", "taller-bf", "cajero", PASSWORD).andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isNotEmpty());
        for (int i = 0; i < 3; i++) {
            es401(tenantLogin("10.3.0.3", "taller-bf", "cajero", "mala"));
        }
        es429(tenantLogin("10.3.0.3", "taller-bf", "cajero", PASSWORD));

        es401(platformLogin("10.3.0.4", PLATFORM_EMAIL, "mala-clave"));
        es401(platformLogin("10.3.0.4", PLATFORM_EMAIL, "mala-clave"));
        platformLogin("10.3.0.4", PLATFORM_EMAIL, PLATFORM_PASSWORD).andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isNotEmpty());
        es401(platformLogin("10.3.0.4", PLATFORM_EMAIL, "mala-clave"));
    }

    @Test
    void otraIpNoCompartelaCuotaDelPar() throws Exception {
        for (int i = 0; i < 3; i++) {
            es401(platformLogin("10.3.0.5", PLATFORM_EMAIL, "mala-clave"));
        }
        es429(platformLogin("10.3.0.5", PLATFORM_EMAIL, PLATFORM_PASSWORD));
        es401(platformLogin("10.3.0.6", PLATFORM_EMAIL, "mala-clave"));
        platformLogin("10.3.0.6", PLATFORM_EMAIL, PLATFORM_PASSWORD).andExpect(status().isOk());
        platformLogin("2001:db8::7", PLATFORM_EMAIL, PLATFORM_PASSWORD).andExpect(status().isOk());
    }

    @Test
    void laCuentaSeBloqueaDesdeCualquierIp() throws Exception {
        for (int i = 1; i <= 5; i++) {
            es401(tenantLogin("10.4.0." + i, "taller-bf", "cajero", "mala"));
        }
        es429(tenantLogin("10.4.0.99", "taller-bf", "cajero", PASSWORD));
    }

    @Test
    void cuentaInexistenteSeComportaIgualQueUnaExistente() throws Exception {
        for (int i = 0; i < 3; i++) {
            es401(tenantLogin("10.3.0.7", "taller-bf", "fantasma", PASSWORD));
            es401(tenantLogin("10.3.0.8", "no-existe", "cajero", PASSWORD));
            es401(platformLogin("10.3.0.9", "nadie@example.invalid", PLATFORM_PASSWORD));
        }
        es429(tenantLogin("10.3.0.7", "taller-bf", "fantasma", PASSWORD));
        es429(tenantLogin("10.3.0.8", "no-existe", "cajero", PASSWORD));
        es429(platformLogin("10.3.0.9", "nadie@example.invalid", PLATFORM_PASSWORD));
    }

    @Test
    void usuarioInactivoYTenantSuspendidoSiguenEn401YCuentanIgual() throws Exception {
        for (int i = 0; i < 3; i++) {
            es401(tenantLogin("10.3.0.10", "taller-bf", "inactivo", PASSWORD));
            es401(tenantLogin("10.3.0.11", "taller-suspendido", "admin", PASSWORD));
        }
        es429(tenantLogin("10.3.0.10", "taller-bf", "inactivo", PASSWORD));
        es429(tenantLogin("10.3.0.11", "taller-suspendido", "admin", PASSWORD));
    }

    @Test
    void laIpSeBloqueaEnTotalConCuentasDistintas() throws Exception {
        for (int i = 0; i < 8; i++) {
            es401(tenantLogin("10.3.0.12", "taller-bf", "usuario-" + i, "mala"));
        }
        es429(tenantLogin("10.3.0.12", "taller-bf", "cajero", PASSWORD));
        es429(platformLogin("10.3.0.12", PLATFORM_EMAIL, PLATFORM_PASSWORD));
        tenantLogin("10.3.0.13", "taller-bf", "cajero", PASSWORD).andExpect(status().isOk());
    }

    @Test
    void cabecerasDeProxyFalsasSinConfianzaNoCambianLaCuota() throws Exception {
        for (int i = 0; i < 3; i++) {
            es401(tenantLogin("10.3.0.14", "taller-bf", "cajero", "mala",
                    "X-Forwarded-For", "198.51.100." + i, "X-Real-IP", "203.0.113." + i));
        }
        es429(tenantLogin("10.3.0.14", "taller-bf", "cajero", PASSWORD,
                "X-Forwarded-For", "198.51.100.77", "X-Real-IP", "203.0.113.77"));
    }

    @Test
    void endpointsNoRelacionadosQuedanIntactosDesdeUnaIpBloqueada() throws Exception {
        for (int i = 0; i < 8; i++) {
            es401(platformLogin("10.3.0.15", "x" + i + "@example.invalid", "mala-clave"));
        }
        es429(platformLogin("10.3.0.15", PLATFORM_EMAIL, PLATFORM_PASSWORD));
        mvc.perform(desde(get("/api/health"), "10.3.0.15")).andExpect(status().isOk());
        mvc.perform(desde(get("/api/platform/auth/me"), "10.3.0.15")).andExpect(status().isUnauthorized());
        mvc.perform(desde(get("/api/inventory/products"), "10.3.0.15")).andExpect(status().isUnauthorized());
        mvc.perform(desde(post("/api/registration-requests"), "10.3.0.15").header("Idempotency-Key", "k-bf-1")
                .contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isNotFound());
        // Un cuerpo invalido (400) no llega al login y no cuenta como fallo.
        for (int i = 0; i < 5; i++) {
            tenantLogin("10.3.0.16", "taller-bf", "cajero", "").andExpect(status().isBadRequest());
        }
        tenantLogin("10.3.0.16", "taller-bf", "cajero", PASSWORD).andExpect(status().isOk());
    }

    @Test
    void fallosSimultaneosNoPasanDelLimite() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(10);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            results.add(pool.submit(() -> {
                start.await();
                return tenantLogin("10.3.0.17", "taller-bf", "cajero", "mala").andReturn().getResponse().getStatus();
            }));
        }
        start.countDown();
        int unauthorized = 0;
        int limited = 0;
        for (Future<Integer> f : results) {
            int s = f.get(60, TimeUnit.SECONDS);
            if (s == 401) {
                unauthorized++;
            } else if (s == 429) {
                limited++;
            }
        }
        pool.shutdown();
        assertThat(unauthorized).isEqualTo(3);
        assertThat(limited).isEqualTo(27);
    }
}
