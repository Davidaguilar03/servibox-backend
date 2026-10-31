package com.servibox.backend.mail;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Collection;

/** Encola correos en la transaccion de quien llama; nunca envia. El envio es de OutboxWorker. */
@Service
@RequiredArgsConstructor
public class MailOutbox {

    private final OutboxMessageRepository repository;
    private final OutboxCipher cipher;

    @Transactional
    public void enqueue(Long activationId, String recipient, OutboxTemplate template, String body,
            LocalDateTime now, LocalDateTime expiresAt) {
        OutboxMessage m = new OutboxMessage();
        m.setActivationId(activationId);
        m.setRecipient(recipient);
        m.setTemplate(template);
        m.setEncryptedPayload(cipher.encrypt(body));
        m.setStatus(OutboxStatus.PENDING);
        m.setAttempts(0);
        m.setNextAttemptAt(now);
        m.setExpiresAt(expiresAt);
        m.setCreatedAt(now);
        repository.save(m);
    }

    @Transactional
    public void cancelPending(Collection<Long> activationIds, String reason) {
        if (!activationIds.isEmpty()) {
            repository.cancelPending(activationIds, reason);
        }
    }
}
