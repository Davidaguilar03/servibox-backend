package com.servibox.backend.registration;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/** Enlace de activacion emitido (V10). Solo el SHA-256 del token, nunca el token. Fechas en UTC. */
@Entity
@Table(name = "activaciones_registro")
@Getter
@Setter
public class RegistrationActivation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id_activacion")
    private Long id;

    @Column(name = "id_solicitud", length = 36, nullable = false)
    private String requestId;

    @Column(name = "generacion", nullable = false)
    private Integer generation;

    @Column(name = "digest_token", length = 64, nullable = false, unique = true)
    private String tokenDigest;

    @Column(name = "fecha_creacion", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "expira_en", nullable = false)
    private LocalDateTime expiresAt;

    @Column(name = "fecha_consumo")
    private LocalDateTime consumedAt;

    @Column(name = "fecha_revocacion")
    private LocalDateTime revokedAt;
}
