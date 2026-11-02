package com.servibox.backend.mail;

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
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Filas PENDING ya cifradas antes de la plantilla de marca: se insertan con SQL como las dejo la
 * version anterior. Salen con su texto, el mismo token y sin reprocesarse; lo que no se reconoce
 * sale tal cual, solo en texto. Ningun log lleva el token.
 */
@SpringBootTest
@ActiveProfiles("test")
@ExtendWith(OutputCaptureExtension.class)
class OutboxLegacyFormatTest {

    private static final String SOLICITUD = "00000000-0000-0000-0000-00000000p057";
    private static final String TOKEN = "TokenDeActivacionAntiguo57abcdefghijklmnopq";
    private static final String CUERPO_ANTERIOR = """
            Hola Ana Rojas:

            La solicitud de Serviteca Uno para usar ServiBox fue aprobada. Para crear el usuario \
            administrador y su contraseña, abre este enlace:

            https://app.example.invalid/activar#token=%s

            El enlace sirve una sola vez y vence el 02/11/2026 a las 20:10 (America/Bogota). Si vence, pide a ServiBox que lo reenvíe.

            Si no solicitaste esta cuenta, ignora este mensaje.
            """.formatted(TOKEN);

    @TestConfiguration
    static class Transporte {
        @Bean
        FakeMailTransport fakeMailTransport() {
            return new FakeMailTransport();
        }
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired OutboxWorker worker;
    @Autowired OutboxCipher cipher;
    @Autowired FakeMailTransport transport;

    @AfterEach
    void cleanup() {
        transport.reset();
        jdbc.update("DELETE FROM correos_salientes");
        jdbc.update("DELETE FROM activaciones_registro");
        jdbc.update("DELETE FROM solicitudes_registro");
    }

    private void pendiente(String cuerpo) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        jdbc.update("INSERT INTO solicitudes_registro (id_solicitud, nombre_comercial, nit, telefono, nombre_responsable,"
                + " correo_responsable, correo_normalizado, slug_solicitado, slug_reservado, estado, hash_cuerpo,"
                + " idempotencia_expira_en, fecha_creacion, reserva_expira_en, nombre_comercial_busqueda, nombre_responsable_busqueda) VALUES (?, 'Serviteca Uno', '900123456-7',"
                + " '3001234567', 'Ana Rojas', 'ana@uno.co', 'ana@uno.co', 'taller-57', 'taller-57', 'APPROVED', ?, ?, ?, ?, 'serviteca uno', 'ana rojas')", SOLICITUD,
                "0".repeat(64), now.plusDays(1), now, now.plusDays(1));
        jdbc.update("INSERT INTO activaciones_registro (id_solicitud, generacion, digest_token, fecha_creacion, expira_en)"
                + " VALUES (?, 1, ?, ?, ?)", SOLICITUD, "d".repeat(64), now, now.plusDays(1));
        long activacion = jdbc.queryForObject("SELECT id_activacion FROM activaciones_registro", Long.class);
        jdbc.update("INSERT INTO correos_salientes (id_activacion, destinatario, plantilla, payload_cifrado, estado,"
                + " intentos, proximo_intento, expira_en, fecha_creacion) VALUES (?, 'ana@uno.co', 'ACTIVATION', ?, 'PENDING', 0, ?, ?, ?)",
                activacion, cipher.encrypt(cuerpo), now.minusSeconds(1), now.plusDays(1), now);
    }

    @Test
    void filaPendienteDelFormatoAnteriorSaleConMarcaUnaSolaVez(CapturedOutput output) {
        pendiente(CUERPO_ANTERIOR);

        assertThat(worker.processBatch()).isEqualTo(1);
        assertThat(worker.processBatch()).isZero();

        assertThat(transport.sent()).hasSize(1);
        FakeMailTransport.Sent correo = transport.sent().getFirst();
        assertThat(correo.subject()).isEqualTo("Activa tu cuenta de ServiBox");
        assertThat(correo.body()).startsWith(CUERPO_ANTERIOR.stripTrailing());
        assertThat(correo.html()).contains("<a href=\"https://app.example.invalid/activar#token=" + TOKEN + "\"")
                .contains("Hola Ana Rojas:");
        assertThat(jdbc.queryForObject("SELECT estado FROM correos_salientes", String.class)).isEqualTo("SENT");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM correos_salientes WHERE payload_cifrado IS NOT NULL", Long.class))
                .isZero();
        assertThat(output.getAll()).doesNotContain(TOKEN).doesNotContain("ana@uno.co");
    }

    @Test
    void cuerpoNoReconocidoSaleTalCualSoloEnTexto(CapturedOutput output) {
        String raro = "Mensaje antiguo con enlace en linea https://app.example.invalid/activar#token=" + TOKEN;
        pendiente(raro);

        assertThat(worker.processBatch()).isEqualTo(1);

        FakeMailTransport.Sent correo = transport.sent().getFirst();
        assertThat(correo.body()).isEqualTo(raro);
        assertThat(correo.html()).isNull();
        assertThat(jdbc.queryForObject("SELECT estado FROM correos_salientes", String.class)).isEqualTo("SENT");
        assertThat(output.getAll()).contains("sin enlace reconocible").doesNotContain(TOKEN);
    }
}
