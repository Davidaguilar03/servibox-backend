package com.servibox.backend.mail;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Rotacion con el worker real (tarea 51): con MAIL_OUTBOX_ENCRYPTION_KEY_PREVIOUS el correo
 * pendiente cifrado con la clave vieja se entrega; uno cifrado con una tercera clave queda
 * FAILED (PAYLOAD_INVALIDO); lo encolado despues usa la actual. Claves ficticias.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(FakeMailTransport.class)
@TestPropertySource(properties = {
        "servibox.mail.outbox.encryption-key=unit-worker-new-outbox-key-2026-xxxxxxxx",
        "MAIL_OUTBOX_ENCRYPTION_KEY_PREVIOUS=unit-worker-old-outbox-key-2026-xxxxxxxx"})
class OutboxKeyRotationWorkerTest {

    private static final String NUEVA = "unit-worker-new-outbox-key-2026-xxxxxxxx";
    private static final String VIEJA = "unit-worker-old-outbox-key-2026-xxxxxxxx";
    private static final String SOLICITUD = "00000000-0000-0000-0000-00000000r051";

    @Autowired JdbcTemplate jdbc;
    @Autowired OutboxWorker worker;
    @Autowired MailOutbox outbox;
    @Autowired FakeMailTransport transport;

    @AfterEach
    void cleanup() {
        jdbc.update("DELETE FROM correos_salientes");
        jdbc.update("DELETE FROM activaciones_registro");
        jdbc.update("DELETE FROM solicitudes_registro");
        transport.reset();
    }

    @Test
    void entregaLoPendienteDeLaClaveViejaYCifraLoNuevoConLaActual() throws Exception {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        jdbc.update("INSERT INTO solicitudes_registro (id_solicitud, nombre_comercial, nit, telefono, nombre_responsable,"
                + " correo_responsable, correo_normalizado, slug_solicitado, slug_reservado, estado, hash_cuerpo,"
                + " idempotencia_expira_en, fecha_creacion, reserva_expira_en) VALUES (?, 'Taller Rotacion', '900123456-7',"
                + " '3001234567', 'Ana', 'ana@example.invalid', 'ana@example.invalid', 'taller-rotacion', 'taller-rotacion',"
                + " 'APPROVED', ?, ?, ?, ?)", SOLICITUD, "0".repeat(64), now.plusDays(1), now, now.plusDays(1));
        jdbc.update("INSERT INTO activaciones_registro (id_solicitud, generacion, digest_token, fecha_creacion, expira_en)"
                + " VALUES (?, 1, ?, ?, ?)", SOLICITUD, "b".repeat(64), now, now.plusDays(1));
        long activacion = jdbc.queryForObject("SELECT id_activacion FROM activaciones_registro", Long.class);
        String insert = "INSERT INTO correos_salientes (id_activacion, destinatario, plantilla, payload_cifrado, estado,"
                + " intentos, proximo_intento, expira_en, fecha_creacion) VALUES (?, ?, 'ACTIVATION', ?, 'PENDING', 0, ?, ?, ?)";
        jdbc.update(insert, activacion, "vieja@example.invalid", new OutboxCipher(VIEJA, "").encrypt("cuerpo viejo"),
                now.minusSeconds(1), now.plusDays(1), now);
        jdbc.update(insert, activacion, "tercera@example.invalid",
                new OutboxCipher("unit-worker-third-outbox-key-2026-xxxxxx", "").encrypt("cuerpo tercero"),
                now.minusSeconds(1), now.plusDays(1), now);
        outbox.enqueue(activacion, "nueva@example.invalid", OutboxTemplate.ACTIVATION, "cuerpo nuevo", now.minusSeconds(1),
                now.plusDays(1));
        String cifradoNuevo = jdbc.queryForObject("SELECT payload_cifrado FROM correos_salientes WHERE destinatario ="
                + " 'nueva@example.invalid'", String.class);
        assertThat(new OutboxCipher(NUEVA, "").decrypt(cifradoNuevo)).isEqualTo("cuerpo nuevo");

        worker.processBatch();

        assertThat(transport.sent()).extracting(FakeMailTransport.Sent::body)
                .containsExactlyInAnyOrder("cuerpo viejo", "cuerpo nuevo");
        assertThat(jdbc.queryForObject("SELECT estado || ':' || COALESCE(ultimo_error, '') FROM correos_salientes"
                + " WHERE destinatario = 'tercera@example.invalid'", String.class)).isEqualTo("FAILED:PAYLOAD_INVALIDO");
    }
}
