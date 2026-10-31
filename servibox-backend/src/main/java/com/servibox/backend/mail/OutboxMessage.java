package com.servibox.backend.mail;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/** Correo en cola (V10). El cuerpo va cifrado y solo mientras esta PENDING. Fechas en UTC. */
@Entity
@Table(name = "correos_salientes")
@Getter
@Setter
public class OutboxMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id_correo")
    private Long id;

    @Column(name = "id_activacion", nullable = false)
    private Long activationId;

    @Column(name = "destinatario", length = 150, nullable = false)
    private String recipient;

    @Enumerated(EnumType.STRING)
    @Column(name = "plantilla", length = 30, nullable = false)
    private OutboxTemplate template;

    @Column(name = "payload_cifrado", length = 4000)
    private String encryptedPayload;

    @Enumerated(EnumType.STRING)
    @Column(name = "estado", length = 10, nullable = false)
    private OutboxStatus status;

    @Column(name = "intentos", nullable = false)
    private Integer attempts;

    @Column(name = "proximo_intento", nullable = false)
    private LocalDateTime nextAttemptAt;

    @Column(name = "reclamado_hasta")
    private LocalDateTime claimedUntil;

    @Column(name = "expira_en", nullable = false)
    private LocalDateTime expiresAt;

    @Column(name = "ultimo_error", length = 100)
    private String lastError;

    @Column(name = "fecha_creacion", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "fecha_envio")
    private LocalDateTime sentAt;
}
