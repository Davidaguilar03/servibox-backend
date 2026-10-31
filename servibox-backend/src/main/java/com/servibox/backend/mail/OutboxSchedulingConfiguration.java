package com.servibox.backend.mail;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Programa OutboxWorker solo en el servidor HTTP (el bootstrap de plataforma no envia) y si
 * servibox.mail.outbox.scheduler-enabled no es false; las pruebas lo apagan y llaman al worker.
 */
@Configuration
@EnableScheduling
@ConditionalOnWebApplication
@ConditionalOnProperty(name = "servibox.mail.outbox.scheduler-enabled", havingValue = "true", matchIfMissing = true)
public class OutboxSchedulingConfiguration {
}
