package com.servibox.backend.mail;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.List;

/**
 * Borra del outbox las filas terminales viejas: SENT desde fecha_envio y FAILED desde expira_en
 * (una fila falla a mas tardar al vencer). Nunca PENDING ni filas con lease vigente. Por lotes,
 * cada uno en su transaccion corta con SELECT ... FOR UPDATE SKIP LOCKED: varias instancias
 * pueden purgar a la vez sin esperarse ni borrar dos veces. No toca activaciones ni solicitudes.
 * Se programa con OutboxSchedulingConfiguration (como el worker) y se apaga con purge-enabled.
 */
@Component
@Slf4j
public class OutboxPurger {

    static final int LOTE = 500;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final boolean enabled;
    private final int sentDays;
    private final int failedDays;

    public OutboxPurger(JdbcTemplate jdbc, PlatformTransactionManager transactionManager, Clock clock,
            @Value("${servibox.mail.outbox.purge-enabled}") boolean enabled,
            @Value("${servibox.mail.outbox.retention-sent-days}") int sentDays,
            @Value("${servibox.mail.outbox.retention-failed-days}") int failedDays) {
        if (sentDays < 1 || failedDays < 1) {
            throw new IllegalStateException("Configuracion del outbox de correo invalida");
        }
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(transactionManager);
        this.clock = clock;
        this.enabled = enabled;
        this.sentDays = sentDays;
        this.failedDays = failedDays;
    }

    @Scheduled(fixedDelayString = "${servibox.mail.outbox.purge-interval}",
            initialDelayString = "${servibox.mail.outbox.purge-interval}")
    public void scheduled() {
        try {
            purge();
        } catch (RuntimeException ex) {
            log.warn("Outbox: purga no completada ({})", ex.getClass().getSimpleName());
        }
    }

    /** Devuelve cuantas filas borro. Apagada no borra nada. */
    public int purge() {
        if (!enabled) {
            return 0;
        }
        LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        LocalDateTime sentCutoff = now.minusDays(sentDays);
        LocalDateTime failedCutoff = now.minusDays(failedDays);
        int total = 0;
        int borradas;
        do {
            borradas = tx.execute(status -> batch(now, sentCutoff, failedCutoff));
            total += borradas;
        } while (borradas == LOTE);
        if (total > 0) {
            log.info("Outbox: purgados {} correos terminales", total);
        }
        return total;
    }

    private static final String CONDICION = " ((estado = 'SENT' AND fecha_envio < ?) OR (estado = 'FAILED' AND expira_en < ?))"
            + " AND (reclamado_hasta IS NULL OR reclamado_hasta < ?)";

    private int batch(LocalDateTime now, LocalDateTime sentCutoff, LocalDateTime failedCutoff) {
        List<Long> ids = jdbc.queryForList("SELECT id_correo FROM correos_salientes WHERE" + CONDICION
                + " ORDER BY id_correo LIMIT " + LOTE + " FOR UPDATE SKIP LOCKED", Long.class,
                sentCutoff, failedCutoff, now);
        if (ids.isEmpty()) {
            return 0;
        }
        // La condicion se repite en el DELETE: solo borra lo que sigue cumpliendola.
        Object[] args = new Object[ids.size() + 3];
        args[0] = sentCutoff;
        args[1] = failedCutoff;
        args[2] = now;
        for (int i = 0; i < ids.size(); i++) {
            args[i + 3] = ids.get(i);
        }
        return jdbc.update("DELETE FROM correos_salientes WHERE" + CONDICION + " AND id_correo IN ("
                + String.join(",", Collections.nCopies(ids.size(), "?")) + ")", args);
    }
}
