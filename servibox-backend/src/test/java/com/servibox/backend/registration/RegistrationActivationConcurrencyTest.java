package com.servibox.backend.registration;

import com.jayway.jsonpath.JsonPath;
import com.servibox.backend.mail.FakeMailTransport;
import com.servibox.backend.mail.OutboxWorker;
import com.servibox.backend.platform.PlatformAdminRepository;
import com.servibox.backend.platform.PlatformBootstrapService;
import com.servibox.backend.platform.PlatformJwtService;
import com.servibox.backend.tenant.TenantRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.RequestBuilder;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.IntFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** Carreras de 4B con hilos reales retenidos por un CountDownLatch, como RegistrationRequestConcurrencyTest. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(FakeMailTransport.class)
@TestPropertySource(properties = "servibox.registration-requests.enabled=true")
class RegistrationActivationConcurrencyTest {

    private static final int HILOS = 10;
    private static final Pattern TOKEN = Pattern.compile("/activar#token=([A-Za-z0-9_-]{43})");

    @Autowired MockMvc mvc;
    @Autowired RegistrationRequestRepository requests;
    @Autowired RegistrationActivationRepository activations;
    @Autowired TenantRepository tenants;
    @Autowired PlatformAdminRepository admins;
    @Autowired PlatformBootstrapService bootstrap;
    @Autowired PlatformJwtService platformJwt;
    @Autowired OutboxWorker worker;
    @Autowired FakeMailTransport transport;
    @Autowired JdbcTemplate jdbc;

    private String platformToken;

    @BeforeEach
    void seed() {
        transport.reset();
        bootstrap.create("revisor@example.invalid", "ephemeral-test-password".toCharArray());
        platformToken = platformJwt.generateToken(admins.findById(1L).orElseThrow());
    }

    @AfterEach
    void cleanup() {
        transport.reset();
        RegistrationActivationTest.limpiar(jdbc, requests, admins);
    }

    private String pendiente(String slug) throws Exception {
        MvcResult r = mvc.perform(post(RegistrationRequestTest.RUTA).header("Idempotency-Key", "clave-" + slug)
                .contentType(MediaType.APPLICATION_JSON).content(RegistrationRequestTest.cuerpo(slug))).andReturn();
        assertThat(r.getResponse().getStatus()).isEqualTo(202);
        return JsonPath.read(r.getResponse().getContentAsString(), "$.requestId");
    }

    private RequestBuilder plataforma(String id, String accion, String body) {
        var builder = post(RegistrationRequestTest.PLATAFORMA + "/" + id + "/" + accion)
                .header("Authorization", "Bearer " + platformToken);
        if (body != null) {
            builder.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        return builder;
    }

    private RequestBuilder activar(String token, String username) {
        return post(RegistrationActivationTest.ACTIVAR).contentType(MediaType.APPLICATION_JSON)
                .content(RegistrationActivationTest.activacion(token, username, "clave-segura-1"));
    }

    private String aprobadaConToken(String slug) throws Exception {
        String id = pendiente(slug);
        assertThat(mvc.perform(plataforma(id, "approve", "{\"expectedVersion\":0}")).andReturn()
                .getResponse().getStatus()).isEqualTo(200);
        worker.processBatch();
        Matcher m = TOKEN.matcher(transport.sent().getLast().body());
        assertThat(m.find()).isTrue();
        return m.group(1);
    }

    @Test
    void dobleActivacionConElMismoTokenTieneUnSoloGanador() throws Exception {
        String token = aprobadaConToken("taller-carrera-act");
        long tenantsAntes = tenants.count();

        List<MvcResult> resultados = simultaneas(HILOS, i -> mvcCall(activar(token, "dueno" + i)));

        List<Integer> codigos = codigos(resultados);
        assertThat(codigos.stream().filter(c -> c == 200)).hasSize(1);
        // Perdedoras: token ya usado (409) o bloqueo no obtenido (409 generico), nunca 500.
        assertThat(codigos).containsOnly(200, 409);
        assertThat(tenants.count()).isEqualTo(tenantsAntes + 1);
        Long tenantId = tenants.findBySlug("taller-carrera-act").orElseThrow().getId();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM usuarios WHERE id_tenant = ?", Long.class, tenantId))
                .isEqualTo(1);
        assertThat(requests.findAll()).singleElement().satisfies(r -> {
            assertThat(r.getStatus()).isEqualTo(RegistrationRequestStatus.ACTIVATED);
            assertThat(r.getActivatedTenantId()).isEqualTo(tenantId);
        });
    }

    @Test
    void activarContraReenviarNuncaDejaUnTokenRevocadoConsumido() throws Exception {
        String token = aprobadaConToken("taller-act-reenvio");
        String id = requests.findAll().getFirst().getId();
        jdbc.update("UPDATE activaciones_registro SET fecha_creacion = ?",
                LocalDateTime.now(ZoneOffset.UTC).minusMinutes(2));
        long tenantsAntes = tenants.count();

        List<MvcResult> resultados = simultaneas(HILOS, i -> i % 2 == 0
                ? mvcCall(activar(token, "dueno" + i))
                : mvcCall(plataforma(id, "resend-activation", null)));

        RegistrationRequest r = requests.findById(id).orElseThrow();
        RegistrationActivation original = activations.findByRequestIdOrderByGenerationDesc(id).getLast();
        assertThat(original.getConsumedAt() != null && original.getRevokedAt() != null).isFalse();
        List<Integer> activaciones = new ArrayList<>();
        List<Integer> reenvios = new ArrayList<>();
        for (int i = 0; i < HILOS; i++) {
            (i % 2 == 0 ? activaciones : reenvios).add(resultados.get(i).getResponse().getStatus());
        }
        assertThat(activaciones).isSubsetOf(200, 409, 410);
        assertThat(reenvios).isSubsetOf(202, 409, 429);
        // Como mucho un reenvio pasa el intervalo; como mucho una activacion crea tenant.
        assertThat(reenvios.stream().filter(c -> c == 202).count()).isLessThanOrEqualTo(1);
        if (r.getStatus() == RegistrationRequestStatus.ACTIVATED) {
            assertThat(activaciones.stream().filter(c -> c == 200)).hasSize(1);
            assertThat(original.getConsumedAt()).isNotNull();
            assertThat(tenants.count()).isEqualTo(tenantsAntes + 1);
        } else {
            assertThat(activaciones).doesNotContain(200);
            assertThat(original.getRevokedAt()).isNotNull();
            assertThat(tenants.count()).isEqualTo(tenantsAntes);
        }
    }

    @Test
    void aprobacionesSimultaneasEmitenUnSoloEnlaceYUnSoloCorreo() throws Exception {
        String id = pendiente("taller-aprob-doble");

        List<MvcResult> resultados = simultaneas(HILOS, i -> mvcCall(plataforma(id, "approve", "{\"expectedVersion\":0}")));

        assertThat(codigos(resultados)).isSubsetOf(200, 409).contains(200);
        assertThat(activations.findByRequestIdOrderByGenerationDesc(id)).hasSize(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM correos_salientes", Long.class)).isEqualTo(1);
    }

    @Test
    void variosWorkersSimultaneosNoEnvianDosVecesElMismoCorreo() throws Exception {
        int mensajes = 15;
        for (int i = 0; i < mensajes; i++) {
            String id = pendiente("taller-worker-" + i);
            assertThat(mvc.perform(plataforma(id, "approve", "{\"expectedVersion\":0}")).andReturn()
                    .getResponse().getStatus()).isEqualTo(200);
        }
        transport.delay(30);

        simultaneas(4, i -> () -> {
            int total = 0;
            int n;
            while ((n = worker.processBatch()) > 0) {
                total += n;
            }
            return total;
        });
        worker.processBatch();

        assertThat(transport.calls()).isEqualTo(mensajes);
        assertThat(transport.sent().stream().map(FakeMailTransport.Sent::body).distinct()).hasSize(mensajes);
        assertThat(jdbc.queryForList("SELECT estado, intentos FROM correos_salientes"))
                .hasSize(mensajes).allSatisfy(f -> {
                    assertThat(f.get("ESTADO")).isEqualTo("SENT");
                    assertThat(f.get("INTENTOS")).isEqualTo(1);
                });
    }

    private Callable<MvcResult> mvcCall(RequestBuilder builder) {
        return () -> mvc.perform(builder).andReturn();
    }

    private <T> List<T> simultaneas(int n, IntFunction<Callable<T>> tarea) throws Exception {
        CountDownLatch salida = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(n);
        try {
            List<Future<T>> futuros = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                Callable<T> c = tarea.apply(i);
                futuros.add(pool.submit(() -> {
                    salida.await();
                    return c.call();
                }));
            }
            salida.countDown();
            List<T> resultados = new ArrayList<>();
            for (Future<T> futuro : futuros) {
                resultados.add(futuro.get());
            }
            return resultados;
        } finally {
            pool.shutdownNow();
        }
    }

    private static List<Integer> codigos(List<MvcResult> resultados) {
        return resultados.stream().map(r -> r.getResponse().getStatus()).toList();
    }
}
