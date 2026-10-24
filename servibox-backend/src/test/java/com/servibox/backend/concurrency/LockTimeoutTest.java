package com.servibox.backend.concurrency;

import com.jayway.jsonpath.JsonPath;
import com.servibox.backend.auth.entity.Role;
import com.servibox.backend.auth.entity.User;
import com.servibox.backend.auth.repository.UserRepository;
import com.servibox.backend.sales.SalesFixture;
import com.servibox.backend.tenant.Tenant;
import com.servibox.backend.tenant.TenantContext;
import com.servibox.backend.tenant.TenantRepository;
import com.servibox.backend.treasury.entity.Account;
import com.servibox.backend.treasury.repository.AccountRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Una transaccion retiene el bloqueo de una cuenta mas alla de
 * jakarta.persistence.lock.timeout (5 s); otra peticion sobre esa cuenta deja de esperar y
 * responde 409 con el mensaje de bloqueo, no 500 ni una espera indefinida.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class LockTimeoutTest {

    private static final String PASSWORD = "clave-correcta";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private SalesFixture fixture;

    @Autowired
    private TenantRepository tenantRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @AfterEach
    void cleanUp() {
        TenantContext.clear();
    }

    @Test
    void unBloqueoSostenidoHaceQueOtraPeticionRespondaConflictoAlAgotarLaEspera() throws Exception {
        String slug = "taller-bloqueo-" + System.nanoTime();
        Tenant tenant = new Tenant();
        tenant.setName(slug);
        tenant.setSlug(slug);
        tenant.setActive(Boolean.TRUE);
        Long tenantId = tenantRepository.save(tenant).getId();
        fixture.usarTenant(tenantId);
        User user = new User();
        user.setUsername("admin");
        user.setPasswordHash(passwordEncoder.encode(PASSWORD));
        user.setActive(true);
        user.setRole(Role.ADMIN);
        userRepository.save(user);
        Account caja = fixture.crearCuenta("Caja");
        TenantContext.clear();

        MvcResult login = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"tenantSlug":"%s","username":"admin","password":"%s"}
                                """.formatted(slug, PASSWORD)))
                .andExpect(status().isOk())
                .andReturn();
        String token = JsonPath.read(login.getResponse().getContentAsString(), "$.token");

        CountDownLatch bloqueada = new CountDownLatch(1);
        CountDownLatch soltar = new CountDownLatch(1);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<?> retenedor = pool.submit(() -> {
                TenantContext.setTenantId(tenantId);
                try {
                    new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
                        assertThat(accountRepository.findByIdAndTenantIdForUpdate(caja.getId(), tenantId)).isPresent();
                        bloqueada.countDown();
                        try {
                            soltar.await(30, TimeUnit.SECONDS);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    });
                } finally {
                    TenantContext.clear();
                }
                return null;
            });
            assertThat(bloqueada.await(10, TimeUnit.SECONDS)).isTrue();

            long inicio = System.nanoTime();
            MvcResult gasto = mockMvc.perform(post("/api/treasury/movements")
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"accountId":%d,"category":"GASTO_OPERATIVO","concept":"Aseo","amount":1000}
                                    """.formatted(caja.getId())))
                    .andReturn();
            long esperaMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - inicio);

            assertThat(gasto.getResponse().getStatus()).isEqualTo(409);
            assertThat(JsonPath.<String>read(gasto.getResponse().getContentAsString(), "$.error"))
                    .isEqualTo("El recurso esta siendo modificado por otra operacion, intenta de nuevo");
            assertThat(JsonPath.<Integer>read(gasto.getResponse().getContentAsString(), "$.status")).isEqualTo(409);
            // Espero el bloqueo (unos 5 s) y no indefinidamente.
            assertThat(esperaMs).isBetween(4000L, 15000L);

            soltar.countDown();
            retenedor.get(30, TimeUnit.SECONDS);
        } finally {
            soltar.countDown();
            pool.shutdownNow();
        }

        // Liberado el bloqueo, la misma peticion pasa.
        mockMvc.perform(post("/api/treasury/movements")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"accountId":%d,"category":"GASTO_OPERATIVO","concept":"Aseo","amount":1000}
                                """.formatted(caja.getId())))
                .andExpect(status().isCreated());
    }
}
