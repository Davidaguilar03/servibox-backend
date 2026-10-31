package com.servibox.backend.registration;

import com.jayway.jsonpath.JsonPath;
import com.servibox.backend.platform.PlatformAdminRepository;
import com.servibox.backend.platform.PlatformBootstrapService;
import com.servibox.backend.platform.PlatformJwtService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.RequestBuilder;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.IntFunction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Carreras sobre solicitudes de registro con hilos reales retenidos por un CountDownLatch,
 * como ConcurrentOperationsTest: un solo ganador por slug y un solo cambio de estado.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "servibox.registration-requests.enabled=true")
class RegistrationRequestConcurrencyTest {

    private static final int HILOS = 10;

    @Autowired MockMvc mvc;
    @Autowired RegistrationRequestRepository requests;
    @Autowired PlatformAdminRepository admins;
    @Autowired PlatformBootstrapService bootstrap;
    @Autowired PlatformJwtService platformJwt;
    @Autowired JdbcTemplate jdbc;

    private String platformToken;

    @BeforeEach
    void seed() {
        bootstrap.create("revisor@example.invalid", "ephemeral-test-password".toCharArray());
        platformToken = platformJwt.generateToken(admins.findById(1L).orElseThrow());
    }

    @AfterEach
    void cleanup() {
        // Aprobar emite activacion y correo (4B): se borran antes por sus FK.
        jdbc.update("DELETE FROM correos_salientes");
        jdbc.update("DELETE FROM activaciones_registro");
        requests.deleteAll();
        admins.deleteAll();
    }

    private RequestBuilder solicitud(String clave, String body) {
        return post(RegistrationRequestTest.RUTA).header("Idempotency-Key", clave)
                .contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private RequestBuilder revision(String id, String accion, String body) {
        return post(RegistrationRequestTest.PLATAFORMA + "/" + id + "/" + accion)
                .header("Authorization", "Bearer " + platformToken)
                .contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private String crear(String slug) throws Exception {
        MvcResult r = mvc.perform(solicitud("clave-" + slug, RegistrationRequestTest.cuerpo(slug))).andReturn();
        assertThat(r.getResponse().getStatus()).isEqualTo(202);
        return JsonPath.read(r.getResponse().getContentAsString(), "$.requestId");
    }

    @Test
    void variasSolicitudesConElMismoSlugReservanUnaSola() throws Exception {
        List<MvcResult> resultados = simultaneas(HILOS, i -> solicitud("clave-carrera-" + i,
                RegistrationRequestTest.cuerpo(i % 2 == 0 ? "taller-carrera" : "Taller-Carrera")));

        assertThat(codigos(resultados)).containsOnly(202, 409);
        assertThat(codigos(resultados).stream().filter(c -> c == 202)).hasSize(1);
        assertThat(requests.count()).isEqualTo(1);
        assertThat(requests.findAll().getFirst().getReservedSlug()).isEqualTo("taller-carrera");
    }

    @Test
    void reintentosSimultaneosConLaMismaClaveNoDuplican() throws Exception {
        List<MvcResult> resultados = simultaneas(HILOS, i -> solicitud("clave-reintento",
                RegistrationRequestTest.cuerpo("taller-reintento")));

        assertThat(requests.count()).isEqualTo(1);
        String id = requests.findAll().getFirst().getId();
        for (MvcResult r : resultados) {
            int codigo = r.getResponse().getStatus();
            assertThat(codigo).as(r.getResponse().getContentAsString()).isIn(202, 409);
            if (codigo == 202) {
                assertThat(JsonPath.<String>read(r.getResponse().getContentAsString(), "$.requestId")).isEqualTo(id);
            }
        }
    }

    @Test
    void dobleAprobacionSimultaneaCambiaElEstadoUnaVez() throws Exception {
        String id = crear("taller-doble");
        List<MvcResult> resultados = simultaneas(HILOS, i -> revision(id, "approve", "{\"expectedVersion\":0}"));

        // Las perdedoras son reintentos del mismo revisor desde la misma version: 200 con el mismo resultado.
        assertThat(codigos(resultados)).isSubsetOf(200, 409).contains(200);
        RegistrationRequest r = requests.findById(id).orElseThrow();
        assertThat(r.getStatus()).isEqualTo(RegistrationRequestStatus.APPROVED);
        assertThat(r.getVersion()).isEqualTo(1);
        for (MvcResult resultado : resultados) {
            if (resultado.getResponse().getStatus() == 200) {
                assertThat(JsonPath.<String>read(resultado.getResponse().getContentAsString(), "$.reviewedAt"))
                        .isEqualTo(JsonPath.<String>read(resultados.stream()
                                .filter(x -> x.getResponse().getStatus() == 200).findFirst().orElseThrow()
                                .getResponse().getContentAsString(), "$.reviewedAt"));
            }
        }
    }

    @Test
    void aprobarContraRechazarTieneUnSoloGanador() throws Exception {
        String id = crear("taller-duelo");
        List<MvcResult> resultados = simultaneas(HILOS, i -> i % 2 == 0
                ? revision(id, "approve", "{\"expectedVersion\":0}")
                : revision(id, "reject", "{\"expectedVersion\":0,\"reason\":\"Rechazo " + i + "\"}"));

        RegistrationRequest r = requests.findById(id).orElseThrow();
        assertThat(r.getVersion()).isEqualTo(1);
        assertThat(r.getStatus()).isIn(RegistrationRequestStatus.APPROVED, RegistrationRequestStatus.REJECTED);
        assertThat(codigos(resultados)).containsOnly(200, 409);
        long exitos = codigos(resultados).stream().filter(c -> c == 200).count();
        if (r.getStatus() == RegistrationRequestStatus.REJECTED) {
            // Cada rechazo lleva otro motivo: solo el ganador recibe 200.
            assertThat(exitos).isEqualTo(1);
            assertThat(r.getReservedSlug()).isNull();
        } else {
            // Las aprobaciones perdedoras son reintentos del mismo revisor y reciben el mismo resultado.
            assertThat(exitos).isBetween(1L, (long) HILOS / 2);
            assertThat(r.getReservedSlug()).isEqualTo("taller-duelo");
        }
    }

    @Test
    void expectedVersionViejoEnCarreraNoGana() throws Exception {
        String id = crear("taller-viejo");
        mvc.perform(revision(id, "reject", "{\"expectedVersion\":0,\"reason\":\"Primero\"}")).andReturn();
        List<MvcResult> resultados = simultaneas(HILOS, i -> revision(id, "approve", "{\"expectedVersion\":0}"));

        assertThat(codigos(resultados)).containsOnly(409);
        RegistrationRequest r = requests.findById(id).orElseThrow();
        assertThat(r.getStatus()).isEqualTo(RegistrationRequestStatus.REJECTED);
        assertThat(r.getVersion()).isEqualTo(1);
    }

    private List<MvcResult> simultaneas(int n, IntFunction<RequestBuilder> peticion) throws Exception {
        CountDownLatch salida = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(n);
        try {
            List<Future<MvcResult>> futuros = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                RequestBuilder builder = peticion.apply(i);
                futuros.add(pool.submit(() -> {
                    salida.await();
                    return mvc.perform(builder).andReturn();
                }));
            }
            salida.countDown();
            List<MvcResult> resultados = new ArrayList<>();
            for (Future<MvcResult> futuro : futuros) {
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
