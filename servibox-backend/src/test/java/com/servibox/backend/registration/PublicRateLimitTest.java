package com.servibox.backend.registration;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Limite de tasa por IP en las rutas publicas, con la compuerta abierta y sin confiar en
 * cabeceras de proxy. Cada prueba usa su propia IP; el reloj solo avanza cuando se le pide.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "servibox.registration-requests.enabled=true",
        "servibox.rate-limit.enabled=true",
        "servibox.rate-limit.registration-requests.limit=3",
        "servibox.rate-limit.registration-requests.window=1h",
        "servibox.rate-limit.activation.limit=4",
        "servibox.rate-limit.activation.window=15m"})
class PublicRateLimitTest {

    static final String SOLICITUDES_429 = "Demasiadas solicitudes desde esta conexion, intenta de nuevo mas tarde";
    static final String ACTIVACION_429 = "Demasiados intentos de activacion, espera unos minutos e intenta de nuevo";
    static final String TOKEN_FALSO = "A".repeat(43);

    /** No es bean: un segundo Clock en el contexto chocaria con el reloj de negocio inyectado por tipo. */
    static final MutableClock CLOCK = new MutableClock(Instant.parse("2026-10-31T12:00:00Z"));

    @TestConfiguration
    static class RelojControlado {
        @Bean
        @Primary
        PublicRateLimiter limiterConReloj(
                @Value("${servibox.rate-limit.client-ip-header}") String header,
                @Value("${servibox.rate-limit.max-entries}") int maxEntries,
                @Value("${servibox.rate-limit.registration-requests.limit}") int limit,
                @Value("${servibox.rate-limit.registration-requests.window}") Duration window,
                @Value("${servibox.rate-limit.activation.limit}") int activationLimit,
                @Value("${servibox.rate-limit.activation.window}") Duration activationWindow) {
            return new PublicRateLimiter(true, header, maxEntries, limit, window, activationLimit, activationWindow, CLOCK);
        }
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    private final MutableClock clock = CLOCK;
    @Autowired RegistrationRequestRepository requests;

    private final AtomicInteger secuencia = new AtomicInteger();

    @AfterEach
    void cleanup() {
        jdbc.update("DELETE FROM correos_salientes");
        jdbc.update("DELETE FROM activaciones_registro");
        requests.deleteAll();
    }

    static MockHttpServletRequestBuilder desde(MockHttpServletRequestBuilder b, String ip) {
        return b.with(r -> {
            r.setRemoteAddr(ip);
            return r;
        });
    }

    private ResultActions solicitar(String ip, String clave, String slug) throws Exception {
        return mvc.perform(desde(post(RegistrationRequestTest.RUTA), ip).header("Idempotency-Key", clave)
                .contentType(MediaType.APPLICATION_JSON).content(RegistrationRequestTest.cuerpo(slug)));
    }

    private ResultActions solicitarNueva(String ip) throws Exception {
        int n = secuencia.incrementAndGet();
        return solicitar(ip, "clave-rl-" + n, "taller-rl-" + n);
    }

    private ResultActions activar(String ip) throws Exception {
        return mvc.perform(desde(post(RegistrationActivationTest.ACTIVAR), ip).contentType(MediaType.APPLICATION_JSON)
                .content(RegistrationActivationTest.activacion(TOKEN_FALSO, "dueno", "clave-segura-1")));
    }

    @Test
    void crearSolicitudLlegaA429ConRetryAfterYMensajeExacto() throws Exception {
        for (int i = 0; i < 3; i++) {
            solicitarNueva("10.1.0.1").andExpect(status().isAccepted());
        }
        long antes = requests.count();
        solicitarNueva("10.1.0.1").andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "3600"))
                .andExpect(jsonPath("$.error").value(SOLICITUDES_429))
                .andExpect(jsonPath("$.status").value(429));
        assertThat(requests.count()).as("el 429 no crea nada").isEqualTo(antes);
    }

    @Test
    void activarLlegaA429ConRetryAfterYMensajeExacto() throws Exception {
        for (int i = 0; i < 4; i++) {
            activar("10.2.0.1").andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("El enlace de activacion no es valido"));
        }
        activar("10.2.0.1").andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "900"))
                .andExpect(jsonPath("$.error").value(ACTIVACION_429))
                .andExpect(jsonPath("$.status").value(429));
    }

    @Test
    void elLimiteDeUnEndpointNoAfectaAlOtro() throws Exception {
        for (int i = 0; i < 3; i++) {
            solicitarNueva("10.3.0.1").andExpect(status().isAccepted());
        }
        solicitarNueva("10.3.0.1").andExpect(status().isTooManyRequests());
        activar("10.3.0.1").andExpect(status().isBadRequest());

        for (int i = 0; i < 4; i++) {
            activar("10.3.0.2");
        }
        activar("10.3.0.2").andExpect(status().isTooManyRequests());
        solicitarNueva("10.3.0.2").andExpect(status().isAccepted());
    }

    @Test
    void otraIpNoComparteCuota() throws Exception {
        for (int i = 0; i < 3; i++) {
            solicitarNueva("10.4.0.1").andExpect(status().isAccepted());
        }
        solicitarNueva("10.4.0.1").andExpect(status().isTooManyRequests());
        solicitarNueva("10.4.0.2").andExpect(status().isAccepted());
        solicitarNueva("2001:db8::4").andExpect(status().isAccepted());
    }

    @Test
    void cabecerasDeProxyFalsificadasNoCambianLaCuotaSinConfianzaConfigurada() throws Exception {
        for (int i = 0; i < 4; i++) {
            activar("10.5.0.1");
        }
        for (String falsa : new String[]{"1.1.1.1", "2.2.2.2, 3.3.3.3", "10.5.0.99"}) {
            int n = secuencia.incrementAndGet();
            mvc.perform(desde(post(RegistrationActivationTest.ACTIVAR), "10.5.0.1")
                            .header("X-Forwarded-For", falsa).header("X-Real-IP", falsa)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(RegistrationActivationTest.activacion(TOKEN_FALSO, "dueno" + n, "clave-segura-1")))
                    .andExpect(status().isTooManyRequests())
                    .andExpect(jsonPath("$.error").value(ACTIVACION_429));
        }
    }

    @Test
    void reintentoConLaMismaClaveCuentaComoUnaPeticionNormal() throws Exception {
        String id1 = JsonPath.read(solicitar("10.6.0.1", "clave-reintento-1", "taller-reintento")
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString(), "$.requestId");
        String id2 = JsonPath.read(solicitar("10.6.0.1", "clave-reintento-1", "taller-reintento")
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString(), "$.requestId");
        assertThat(id2).isEqualTo(id1);
        assertThat(requests.count()).isEqualTo(1);
        // Dos peticiones gastaron dos unidades, ni mas ni menos: queda una.
        solicitarNueva("10.6.0.1").andExpect(status().isAccepted());
        solicitar("10.6.0.1", "clave-reintento-1", "taller-reintento").andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error").value(SOLICITUDES_429));
    }

    @Test
    void laCuotaSeRecuperaAlPasarLaVentana() throws Exception {
        for (int i = 0; i < 4; i++) {
            activar("10.7.0.1");
        }
        clock.advance(Duration.ofMinutes(10));
        activar("10.7.0.1").andExpect(status().isTooManyRequests()).andExpect(header().string("Retry-After", "300"));
        clock.advance(Duration.ofMinutes(5));
        activar("10.7.0.1").andExpect(status().isBadRequest());

        for (int i = 0; i < 3; i++) {
            solicitarNueva("10.7.0.2").andExpect(status().isAccepted());
        }
        solicitarNueva("10.7.0.2").andExpect(status().isTooManyRequests());
        clock.advance(Duration.ofHours(1));
        solicitarNueva("10.7.0.2").andExpect(status().isAccepted());
    }

    @Test
    void endpointsNoLimitadosSiguenIntactosDesdeUnaIpAgotada() throws Exception {
        for (int i = 0; i < 4; i++) {
            solicitarNueva("10.8.0.1");
            activar("10.8.0.1");
        }
        solicitarNueva("10.8.0.1").andExpect(status().isTooManyRequests());
        activar("10.8.0.1").andExpect(status().isTooManyRequests());
        for (int i = 0; i < 12; i++) {
            mvc.perform(desde(post("/api/auth/login"), "10.8.0.1").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"tenantSlug\":\"no-existe\",\"username\":\"x\",\"password\":\"y\"}"))
                    .andExpect(status().isUnauthorized());
            mvc.perform(desde(post("/api/platform/auth/login"), "10.8.0.1").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"email\":\"nadie@example.invalid\",\"password\":\"no-es-valida\"}"))
                    .andExpect(status().isUnauthorized());
            mvc.perform(desde(get("/api/health"), "10.8.0.1")).andExpect(status().isOk());
            mvc.perform(desde(get(RegistrationRequestTest.PLATAFORMA), "10.8.0.1")).andExpect(status().isUnauthorized());
            mvc.perform(desde(get("/api/tenant"), "10.8.0.1")).andExpect(status().isUnauthorized());
            // Con solicitudes abiertas el registro inmediato es 403, no 429.
            mvc.perform(desde(post("/api/auth/register"), "10.8.0.1").contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isForbidden());
        }
    }

    @Test
    void peticionesSimultaneasDesdeLaMismaIpNoSuperanElLimite() throws Exception {
        AtomicInteger admitidas = new AtomicInteger();
        AtomicInteger limitadas = new AtomicInteger();
        FixedWindowRateLimiterTest.correrEnParalelo(40, i -> {
            int codigo = activar("10.9.0.1").andReturn().getResponse().getStatus();
            if (codigo == 429) {
                limitadas.incrementAndGet();
            } else if (codigo == 400) {
                admitidas.incrementAndGet();
            }
        });
        assertThat(admitidas.get()).isEqualTo(4);
        assertThat(limitadas.get()).isEqualTo(36);
    }
}
