package com.servibox.backend.mail;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;

public interface OutboxMessageRepository extends JpaRepository<OutboxMessage, Long> {

    /** Cancela lo pendiente de esas activaciones y borra su cuerpo cifrado. */
    @Modifying(flushAutomatically = true)
    @Query("update OutboxMessage m set m.status = com.servibox.backend.mail.OutboxStatus.FAILED,"
            + " m.encryptedPayload = null, m.lastError = :reason"
            + " where m.activationId in :activationIds and m.status = com.servibox.backend.mail.OutboxStatus.PENDING")
    int cancelPending(@Param("activationIds") Collection<Long> activationIds, @Param("reason") String reason);

    long countByActivationIdIn(Collection<Long> activationIds);
}
