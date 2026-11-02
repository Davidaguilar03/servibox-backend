package com.servibox.backend.mail;

import com.servibox.backend.shared.SafeLog;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.security.GeneralSecurityException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * Envia el outbox. Reclamo seguro entre instancias en dos capas: SELECT ... FOR UPDATE SKIP
 * LOCKED (PostgreSQL 16 y H2 2.4 lo admiten igual) dentro de una transaccion corta, y un
 * UPDATE condicional que fija reclamado_hasta (lease) solo si nadie mas lo tiene. El envio
 * ocurre fuera de toda transaccion; el cierre solo aplica si el lease sigue siendo propio y
 * el mensaje sigue PENDING. Si una instancia cae tras enviar y antes de cerrar, el lease
 * vence y el mensaje se reintenta: puede duplicarse el correo, nunca otra cosa.
 */
@Component
@Slf4j
public class OutboxWorker {

    static final int LOTE = 10;
    static final Duration RECLAMO = Duration.ofMinutes(5);
    static final Duration ESPERA_MAXIMA = Duration.ofHours(1);

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final OutboxCipher cipher;
    private final ObjectProvider<MailTransport> transport;
    private final MailRenderer renderer;
    private final int maxAttempts;
    private final Duration backoff;

    record Claimed(long id, String recipient, OutboxTemplate template, String payload, int attempts) {
    }

    public OutboxWorker(JdbcTemplate jdbc, PlatformTransactionManager transactionManager, OutboxCipher cipher,
            ObjectProvider<MailTransport> transport, MailRenderer renderer,
            @Value("${servibox.mail.outbox.max-attempts}") int maxAttempts,
            @Value("${servibox.mail.outbox.retry-backoff}") Duration backoff) {
        if (maxAttempts < 1 || !backoff.isPositive()) {
            throw new IllegalStateException("Configuracion del outbox de correo invalida");
        }
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(transactionManager);
        this.cipher = cipher;
        this.transport = transport;
        this.renderer = renderer;
        this.maxAttempts = maxAttempts;
        this.backoff = backoff;
        if (transport.getIfAvailable() == null) {
            log.warn("Outbox: sin transporte de correo configurado (SMTP_HOST); los mensajes quedan PENDING");
        }
    }

    @Scheduled(fixedDelayString = "${servibox.mail.outbox.poll-interval}",
            initialDelayString = "${servibox.mail.outbox.poll-interval}")
    public void poll() {
        try {
            processBatch();
        } catch (RuntimeException ex) {
            log.warn("Outbox: lote no procesado ({})", ex.getClass().getSimpleName());
        }
    }

    /** Vence lo caducado y, si hay transporte, intenta un lote. Devuelve cuantos mensajes intento. */
    public int processBatch() {
        LocalDateTime now = now();
        jdbc.update("UPDATE correos_salientes SET estado = 'FAILED', payload_cifrado = NULL, ultimo_error = 'VENCIDO'"
                + " WHERE estado = 'PENDING' AND expira_en <= ? AND (reclamado_hasta IS NULL OR reclamado_hasta < ?)",
                now, now);
        MailTransport t = transport.getIfAvailable();
        if (t == null) {
            return 0;
        }
        LocalDateTime lease = now.plus(RECLAMO);
        List<Claimed> claimed = tx.execute(status -> claim(now, lease));
        for (Claimed c : claimed) {
            deliver(t, c, lease);
        }
        return claimed.size();
    }

    private List<Claimed> claim(LocalDateTime now, LocalDateTime lease) {
        List<Long> ids = jdbc.queryForList("SELECT id_correo FROM correos_salientes WHERE estado = 'PENDING'"
                + " AND proximo_intento <= ? AND (reclamado_hasta IS NULL OR reclamado_hasta < ?)"
                + " ORDER BY proximo_intento, id_correo LIMIT " + LOTE + " FOR UPDATE SKIP LOCKED", Long.class, now, now);
        List<Claimed> claimed = new ArrayList<>();
        for (Long id : ids) {
            int n = jdbc.update("UPDATE correos_salientes SET reclamado_hasta = ?, intentos = intentos + 1"
                    + " WHERE id_correo = ? AND estado = 'PENDING' AND (reclamado_hasta IS NULL OR reclamado_hasta < ?)",
                    lease, id, now);
            if (n == 1) {
                claimed.add(jdbc.queryForObject("SELECT id_correo, destinatario, plantilla, payload_cifrado, intentos"
                        + " FROM correos_salientes WHERE id_correo = ?", (rs, i) -> new Claimed(rs.getLong(1),
                        rs.getString(2), OutboxTemplate.valueOf(rs.getString(3)), rs.getString(4), rs.getInt(5)), id));
            }
        }
        return claimed;
    }

    private void deliver(MailTransport t, Claimed c, LocalDateTime lease) {
        String body;
        try {
            body = cipher.decrypt(c.payload());
        } catch (GeneralSecurityException | IllegalArgumentException | NullPointerException ex) {
            log.warn("Outbox: correo {} sin cuerpo descifrable", c.id());
            fail(c, lease, "PAYLOAD_INVALIDO");
            return;
        }
        try {
            RenderedMail mail = renderer.render(c.template(), body);
            if (mail.html() == null) {
                log.warn("Outbox: correo {} sin enlace reconocible, va solo en texto", c.id());
            }
            t.send(c.recipient(), c.template().subject(), mail.text(), mail.html());
        } catch (MailTransportException ex) {
            log.warn("Outbox: correo {} no aceptado por el transporte, intento {}, permanente {} ({})", c.id(),
                    c.attempts(), ex.isPermanent(), detalle(ex));
            retryOrFail(c, lease, ex.isPermanent());
            return;
        } catch (RuntimeException ex) {
            log.warn("Outbox: correo {} no aceptado por el transporte, intento {} ({})", c.id(), c.attempts(),
                    detalle(ex));
            retryOrFail(c, lease, false);
            return;
        }
        jdbc.update("UPDATE correos_salientes SET estado = 'SENT', payload_cifrado = NULL, fecha_envio = ?,"
                + " reclamado_hasta = NULL, ultimo_error = NULL"
                + " WHERE id_correo = ? AND estado = 'PENDING' AND reclamado_hasta = ?", now(), c.id(), lease);
        log.info("Outbox: correo {} aceptado por el transporte", c.id());
    }

    /**
     * Para operar sin datos personales: clase y respuesta del servidor SMTP de la causa mas
     * profunda (codigo y texto, por ejemplo 550 5.1.1), con los correos enmascarados (a***@dominio).
     * Nunca el destinatario completo ni el cuerpo (el enlace con el token).
     */
    static String detalle(Throwable ex) {
        Throwable raiz = ex;
        while (raiz.getCause() != null && raiz.getCause() != raiz) {
            raiz = raiz.getCause();
        }
        return raiz.getClass().getSimpleName() + ": " + SafeLog.mask(raiz.getMessage());
    }

    private void retryOrFail(Claimed c, LocalDateTime lease, boolean permanent) {
        if (permanent) {
            fail(c, lease, "TRANSPORTE_PERMANENTE");
        } else if (c.attempts() >= maxAttempts) {
            fail(c, lease, "INTENTOS_AGOTADOS");
        } else {
            Duration espera = backoff.multipliedBy(1L << Math.min(c.attempts() - 1, 20));
            if (espera.compareTo(ESPERA_MAXIMA) > 0) {
                espera = ESPERA_MAXIMA;
            }
            jdbc.update("UPDATE correos_salientes SET proximo_intento = ?, reclamado_hasta = NULL,"
                    + " ultimo_error = 'TRANSPORTE_TEMPORAL'"
                    + " WHERE id_correo = ? AND estado = 'PENDING' AND reclamado_hasta = ?",
                    now().plus(espera), c.id(), lease);
        }
    }

    private void fail(Claimed c, LocalDateTime lease, String reason) {
        jdbc.update("UPDATE correos_salientes SET estado = 'FAILED', payload_cifrado = NULL, reclamado_hasta = NULL,"
                + " ultimo_error = ? WHERE id_correo = ? AND estado = 'PENDING' AND reclamado_hasta = ?",
                reason, c.id(), lease);
    }

    private static LocalDateTime now() {
        return LocalDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
    }
}
