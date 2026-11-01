package com.servibox.backend.mail;

import com.servibox.backend.shared.SafeLog;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.MailSendException;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tarea 52: un rechazo SMTP deja en el log el id del correo, el intento, la clase y el codigo
 * de respuesta del servidor, con el correo enmascarado; nunca el destinatario completo ni el
 * enlace con el token del cuerpo. El transporte de prueba falla como lo haria JavaMail, con el
 * destinatario y el cuerpo dentro del mensaje de la excepcion.
 */
@SpringBootTest
@ActiveProfiles("test")
@ExtendWith(OutputCaptureExtension.class)
class OutboxLogPrivacyTest {

    private static final String DESTINATARIO = "juana.perez.privada@cliente-secreto.co";
    private static final String TOKEN = "TokenSecretoDeActivacion52abcdefghijklmnopq";
    private static final String SOLICITUD = "00000000-0000-0000-0000-00000000p052";

    @TestConfiguration
    static class TransporteQueRechaza {
        @Bean
        MailTransport rechazaConDatos() {
            return (to, subject, body) -> {
                throw new MailTransportException(true, new MailSendException("Failed messages",
                        new IllegalStateException("550 5.1.1 <" + to + "> User unknown; cuerpo: " + body)));
            };
        }
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired OutboxWorker worker;
    @Autowired MailOutbox outbox;

    @AfterEach
    void cleanup() {
        jdbc.update("DELETE FROM correos_salientes");
        jdbc.update("DELETE FROM activaciones_registro");
        jdbc.update("DELETE FROM solicitudes_registro");
    }

    @Test
    void rechazoSmtpQuedaUtilSinCorreoNiToken(CapturedOutput output) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        jdbc.update("INSERT INTO solicitudes_registro (id_solicitud, nombre_comercial, nit, telefono, nombre_responsable,"
                + " correo_responsable, correo_normalizado, slug_solicitado, slug_reservado, estado, hash_cuerpo,"
                + " idempotencia_expira_en, fecha_creacion, reserva_expira_en, nombre_comercial_busqueda, nombre_responsable_busqueda) VALUES (?, 'Taller Log', '900123456-7',"
                + " '3001234567', 'Juana', ?, ?, 'taller-log', 'taller-log', 'APPROVED', ?, ?, ?, ?, 'taller log', 'juana')", SOLICITUD,
                DESTINATARIO, DESTINATARIO, "0".repeat(64), now.plusDays(1), now, now.plusDays(1));
        jdbc.update("INSERT INTO activaciones_registro (id_solicitud, generacion, digest_token, fecha_creacion, expira_en)"
                + " VALUES (?, 1, ?, ?, ?)", SOLICITUD, "c".repeat(64), now, now.plusDays(1));
        long activacion = jdbc.queryForObject("SELECT id_activacion FROM activaciones_registro", Long.class);
        outbox.enqueue(activacion, DESTINATARIO, OutboxTemplate.ACTIVATION,
                "Activa en https://app.example.invalid/activar#token=" + TOKEN, now.minusSeconds(1), now.plusDays(1));
        long id = jdbc.queryForObject("SELECT id_correo FROM correos_salientes", Long.class);

        assertThat(worker.processBatch()).isEqualTo(1);

        assertThat(jdbc.queryForObject("SELECT ultimo_error FROM correos_salientes", String.class))
                .isEqualTo("TRANSPORTE_PERMANENTE");
        assertThat(output.getAll())
                .contains("Outbox: correo " + id + " no aceptado por el transporte, intento 1, permanente true")
                .contains("IllegalStateException: 550 5.1.1 <j***@cliente-secreto.co> User unknown")
                .doesNotContain(DESTINATARIO).doesNotContain("juana.perez").doesNotContain(TOKEN);
    }

    @Test
    void enmascaraCorreosRecortaYUneLineas() {
        assertThat(SafeLog.mask("550 <ana.rojas@uno.co>\r\ny b@x.org")).isEqualTo("550 <a***@uno.co> y b***@x.org");
        assertThat(SafeLog.mask(null)).isEmpty();
        assertThat(SafeLog.mask("a b ".repeat(200))).hasSize(300);
        assertThat(SafeLog.mask("token=" + TOKEN + " fin")).isEqualTo("token=[oculto] fin");
    }
}
