package com.servibox.backend.mail;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Purga del outbox (tarea 51) con reloj fijo: sin sleeps. Corre en H2 y, con
 * spring.datasource.* por propiedades de sistema, sobre una PostgreSQL 16 nueva.
 */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {"MAIL_OUTBOX_RETENTION_SENT_DAYS=7", "MAIL_OUTBOX_RETENTION_FAILED_DAYS=14"})
class OutboxPurgerTest {

    private static final Instant AHORA = Instant.parse("2026-10-31T12:00:00Z");
    private static final LocalDateTime NOW = LocalDateTime.ofInstant(AHORA, ZoneOffset.UTC);
    private static final String SOLICITUD = "00000000-0000-0000-0000-00000000d051";

    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager txManager;
    @Autowired OutboxPurger contextPurger;

    private long activacion;

    @BeforeEach
    void seed() {
        jdbc.update("INSERT INTO solicitudes_registro (id_solicitud, nombre_comercial, nit, telefono, nombre_responsable,"
                + " correo_responsable, correo_normalizado, slug_solicitado, slug_reservado, estado, hash_cuerpo,"
                + " idempotencia_expira_en, fecha_creacion, reserva_expira_en, nombre_comercial_busqueda, nombre_responsable_busqueda) VALUES (?, 'Taller Purga', '900123456-7',"
                + " '3001234567', 'Ana', 'ana@example.invalid', 'ana@example.invalid', 'taller-purga', 'taller-purga',"
                + " 'APPROVED', ?, ?, ?, ?, 'taller purga', 'ana')", SOLICITUD, "0".repeat(64), NOW, NOW.minusDays(200), NOW.plusDays(1));
        jdbc.update("INSERT INTO activaciones_registro (id_solicitud, generacion, digest_token, fecha_creacion, expira_en)"
                + " VALUES (?, 1, ?, ?, ?)", SOLICITUD, "a".repeat(64), NOW.minusDays(200), NOW.minusDays(199));
        activacion = jdbc.queryForObject("SELECT id_activacion FROM activaciones_registro", Long.class);
    }

    @AfterEach
    void cleanup() {
        jdbc.update("DELETE FROM correos_salientes");
        jdbc.update("DELETE FROM activaciones_registro");
        jdbc.update("DELETE FROM solicitudes_registro");
    }

    private OutboxPurger purger(boolean enabled) {
        return new OutboxPurger(jdbc, txManager, Clock.fixed(AHORA, ZoneOffset.UTC), enabled, 30, 90);
    }

    /** estado, fecha_envio (solo SENT), expira_en, reclamado_hasta. Devuelve el id. */
    private long correo(String estado, LocalDateTime enviado, LocalDateTime expira, LocalDateTime lease, String marca) {
        jdbc.update("INSERT INTO correos_salientes (id_activacion, destinatario, plantilla, payload_cifrado, estado,"
                + " intentos, proximo_intento, reclamado_hasta, expira_en, ultimo_error, fecha_creacion, fecha_envio)"
                + " VALUES (?, ?, 'ACTIVATION', ?, ?, 1, ?, ?, ?, NULL, ?, ?)", activacion, marca + "@example.invalid",
                estado.equals("PENDING") ? "cifrado" : null, estado, NOW.minusDays(400), lease, expira,
                NOW.minusDays(400), enviado);
        return jdbc.queryForObject("SELECT id_correo FROM correos_salientes WHERE destinatario = ?", Long.class,
                marca + "@example.invalid");
    }

    private List<String> quedan() {
        return jdbc.queryForList("SELECT destinatario FROM correos_salientes ORDER BY destinatario", String.class)
                .stream().map(d -> d.substring(0, d.indexOf('@'))).toList();
    }

    @Test
    void borraSoloTerminalesViejosYNuncaPendientesNiConLeaseVigente() {
        correo("SENT", NOW.minusDays(31), NOW.minusDays(30), null, "sent-31");
        correo("SENT", NOW.minusDays(30).plusSeconds(1), NOW.minusDays(29), null, "sent-casi-30");
        correo("SENT", NOW.minusDays(1), NOW.plusDays(1), null, "sent-1");
        correo("FAILED", null, NOW.minusDays(91), null, "failed-91");
        correo("FAILED", null, NOW.minusDays(90).plusSeconds(1), null, "failed-casi-90");
        correo("FAILED", null, NOW.minusDays(91), NOW.plusMinutes(3), "failed-91-lease");
        correo("FAILED", null, NOW.minusDays(91), NOW.minusMinutes(3), "failed-91-lease-vencido");
        correo("PENDING", null, NOW.minusDays(300), null, "pending-300");

        assertThat(purger(true).purge()).isEqualTo(3);

        assertThat(quedan()).containsExactly("failed-91-lease", "failed-casi-90", "pending-300", "sent-1",
                "sent-casi-30");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM activaciones_registro", Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM solicitudes_registro", Long.class)).isEqualTo(1);
        // Repetir no borra nada mas.
        assertThat(purger(true).purge()).isZero();
    }

    @Test
    void borraPorLotesHastaTerminar() {
        List<Object[]> filas = new ArrayList<>();
        int total = OutboxPurger.LOTE * 2 + 3;
        for (int i = 0; i < total; i++) {
            filas.add(new Object[]{activacion, "lote-" + i + "@example.invalid", NOW.minusDays(400), NOW.minusDays(300),
                    NOW.minusDays(400), NOW.minusDays(60)});
        }
        jdbc.batchUpdate("INSERT INTO correos_salientes (id_activacion, destinatario, plantilla, estado, intentos,"
                + " proximo_intento, expira_en, fecha_creacion, fecha_envio) VALUES (?, ?, 'ACTIVATION', 'SENT', 1, ?, ?, ?, ?)",
                filas);
        correo("PENDING", null, NOW.minusDays(300), null, "pending");

        assertThat(purger(true).purge()).isEqualTo(total);
        assertThat(quedan()).containsExactly("pending");
    }

    @Test
    void apagadaNoBorraNada() {
        correo("SENT", NOW.minusDays(100), NOW.minusDays(99), null, "sent-100");
        correo("FAILED", null, NOW.minusDays(200), null, "failed-200");

        assertThat(purger(false).purge()).isZero();
        assertThat(quedan()).hasSize(2);
    }

    @Test
    void variasInstanciasALaVezBorranCadaFilaUnaSolaVez() throws Exception {
        List<Object[]> filas = new ArrayList<>();
        int total = 1200;
        for (int i = 0; i < total; i++) {
            filas.add(new Object[]{activacion, "multi-" + i + "@example.invalid", NOW.minusDays(400), NOW.minusDays(300),
                    NOW.minusDays(400), NOW.minusDays(60)});
        }
        jdbc.batchUpdate("INSERT INTO correos_salientes (id_activacion, destinatario, plantilla, estado, intentos,"
                + " proximo_intento, expira_en, fecha_creacion, fecha_envio) VALUES (?, ?, 'ACTIVATION', 'SENT', 1, ?, ?, ?, ?)",
                filas);

        int instancias = 4;
        ExecutorService pool = Executors.newFixedThreadPool(instancias);
        CountDownLatch salida = new CountDownLatch(1);
        List<Future<Integer>> resultados = new ArrayList<>();
        for (int i = 0; i < instancias; i++) {
            OutboxPurger p = purger(true);
            Callable<Integer> tarea = () -> {
                salida.await();
                return p.purge();
            };
            resultados.add(pool.submit(tarea));
        }
        salida.countDown();
        int borradas = 0;
        for (Future<Integer> f : resultados) {
            borradas += f.get();
        }
        pool.shutdown();

        assertThat(borradas).isEqualTo(total);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM correos_salientes", Long.class)).isZero();
    }

    @Test
    void retencionSaleDeLasVariablesDeEntornoYSeValida() {
        assertThat(ReflectionTestUtils.getField(contextPurger, "sentDays")).isEqualTo(7);
        assertThat(ReflectionTestUtils.getField(contextPurger, "failedDays")).isEqualTo(14);
        assertThat(ReflectionTestUtils.getField(contextPurger, "enabled")).isEqualTo(true);
        Clock reloj = Clock.fixed(AHORA, ZoneOffset.UTC);
        assertThatThrownBy(() -> new OutboxPurger(jdbc, txManager, reloj, true, 0, 90))
                .hasMessage("Configuracion del outbox de correo invalida");
        assertThatThrownBy(() -> new OutboxPurger(jdbc, txManager, reloj, true, 30, -1))
                .hasMessage("Configuracion del outbox de correo invalida");
    }
}
