package com.servibox.backend.platform;

import com.jayway.jsonpath.JsonPath;
import com.servibox.backend.auth.entity.Role;
import com.servibox.backend.auth.entity.User;
import com.servibox.backend.auth.repository.UserRepository;
import com.servibox.backend.tenant.Tenant;
import com.servibox.backend.tenant.TenantContext;
import com.servibox.backend.tenant.TenantRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.RequestBuilder;

import java.nio.charset.StandardCharsets;
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
 * Carreras sobre el estado de un tenant con hilos reales retenidos por un CountDownLatch,
 * como RegistrationRequestConcurrencyTest: el bloqueo pesimista mas expectedVersion dejan un
 * solo ganador.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PlatformTenantConcurrencyTest {

    @Autowired MockMvc mvc;
    @Autowired PlatformBootstrapService bootstrap;
    @Autowired PlatformAdminRepository admins;
    @Autowired PlatformJwtService platformJwt;
    @Autowired PlatformAuditRepository audits;
    @Autowired TenantRepository tenants;
    @Autowired UserRepository users;

    private String platformToken;
    private final List<Long> tenantIds = new ArrayList<>();

    @BeforeEach
    void seed() {
        bootstrap.create("carreras@example.invalid", "ephemeral-test-password".toCharArray());
        platformToken = platformJwt.generateToken(admins.findById(1L).orElseThrow());
    }

    @AfterEach
    void cleanup() {
        for (Long id : tenantIds) {
            audits.deleteAll(audits.findByTenantIdOrderByIdAsc(id));
            users.deleteAll(users.findByTenantId(id, Pageable.unpaged()).getContent());
            tenants.deleteById(id);
        }
        admins.deleteAll();
    }

    @Test
    void dosSuspensionesSimultaneasDelMismoTenantSoloUnaGana() throws Exception {
        for (int ronda = 0; ronda < 5; ronda++) {
            Tenant tenant = tenant("pt5a-carrera-" + ronda);
            List<MvcResult> resultados = simultaneas(2, i -> suspender(tenant.getId(), "Motivo " + i));

            assertThat(codigos(resultados)).containsExactlyInAnyOrder(200, 409);
            MvcResult perdedor = resultados.stream().filter(r -> r.getResponse().getStatus() == 409).findFirst()
                    .orElseThrow();
            assertThat((String) JsonPath.read(perdedor.getResponse().getContentAsString(StandardCharsets.UTF_8),
                    "$.error")).isIn("El recurso cambio; actualiza e intenta de nuevo",
                    "El recurso esta siendo modificado por otra operacion, intenta de nuevo");
            Tenant final_ = tenants.findById(tenant.getId()).orElseThrow();
            assertThat(final_.getActive()).isFalse();
            assertThat(final_.getVersion()).isEqualTo(1);
            assertThat(audits.findByTenantIdOrderByIdAsc(tenant.getId())).hasSize(1);
        }
    }

    @Test
    void diezSuspensionesMezcladasConRevocacionesDejanUnSoloCambioDeEstado() throws Exception {
        Tenant tenant = tenant("pt5a-carrera-mixta");
        TenantContext.setTenantId(tenant.getId());
        try {
            User u = new User();
            u.setUsername("cajero");
            u.setPasswordHash("unused");
            u.setRole(Role.CAJERO);
            users.saveAndFlush(u);
        } finally {
            TenantContext.clear();
        }

        List<MvcResult> resultados = simultaneas(10, i -> i % 2 == 0
                ? suspender(tenant.getId(), "Motivo " + i)
                : post("/api/platform/tenants/" + tenant.getId() + "/revoke-sessions")
                        .header("Authorization", "Bearer " + platformToken));

        List<Integer> suspensiones = new ArrayList<>();
        List<Integer> revocaciones = new ArrayList<>();
        for (int i = 0; i < resultados.size(); i++) {
            (i % 2 == 0 ? suspensiones : revocaciones).add(resultados.get(i).getResponse().getStatus());
        }
        assertThat(suspensiones).filteredOn(c -> c == 200).hasSize(1);
        assertThat(suspensiones).filteredOn(c -> c != 200).containsOnly(409);
        assertThat(revocaciones).containsOnly(200);
        Tenant final_ = tenants.findById(tenant.getId()).orElseThrow();
        assertThat(final_.getVersion()).isEqualTo(1);
        // Cada revocacion y la suspension subieron la version del unico usuario.
        assertThat(users.findByTenantId(tenant.getId(), Pageable.unpaged()).getContent().get(0).getAuthVersion())
                .isEqualTo(6);
        assertThat(audits.findByTenantIdOrderByIdAsc(tenant.getId())).hasSize(6);
    }

    private Tenant tenant(String slug) {
        Tenant t = new Tenant();
        t.setName("Taller " + slug);
        t.setSlug(slug);
        t.setActive(true);
        t = tenants.saveAndFlush(t);
        tenantIds.add(t.getId());
        return t;
    }

    private RequestBuilder suspender(Long id, String motivo) {
        return post("/api/platform/tenants/" + id + "/suspend")
                .header("Authorization", "Bearer " + platformToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedVersion\":0,\"reason\":\"" + motivo + "\"}");
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
