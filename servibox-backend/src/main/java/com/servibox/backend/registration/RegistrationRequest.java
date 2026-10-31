package com.servibox.backend.registration;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/** Solicitud de registro de una serviteca (V9). Global, sin tenant ni contrasena. Fechas en UTC. */
@Entity
@Table(name = "solicitudes_registro")
@Getter
@Setter
public class RegistrationRequest {

    @Id
    @Column(name = "id_solicitud", length = 36)
    private String id;

    @Column(name = "nombre_comercial", length = 150, nullable = false)
    private String businessName;

    @Column(name = "nit", length = 25, nullable = false)
    private String nit;

    @Column(name = "telefono", length = 25, nullable = false)
    private String phone;

    @Column(name = "nombre_responsable", length = 150, nullable = false)
    private String contactName;

    @Column(name = "correo_responsable", length = 150, nullable = false)
    private String contactEmail;

    @Column(name = "correo_normalizado", length = 150, nullable = false)
    private String normalizedEmail;

    @Column(name = "slug_solicitado", length = 30, nullable = false)
    private String requestedSlug;

    /** Igual a requestedSlug mientras la solicitud reserva el slug, null despues. */
    @Column(name = "slug_reservado", length = 30, unique = true)
    private String reservedSlug;

    @Enumerated(EnumType.STRING)
    @Column(name = "estado", length = 20, nullable = false)
    private RegistrationRequestStatus status;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    @Column(name = "clave_idempotencia", length = 100, unique = true)
    private String idempotencyKey;

    @Column(name = "hash_cuerpo", length = 64, nullable = false)
    private String bodyHash;

    @Column(name = "idempotencia_expira_en", nullable = false)
    private LocalDateTime idempotencyExpiresAt;

    @Column(name = "fecha_creacion", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "reserva_expira_en", nullable = false)
    private LocalDateTime reservationExpiresAt;

    @Column(name = "fecha_revision")
    private LocalDateTime reviewedAt;

    @Column(name = "id_revisor")
    private Long reviewerId;

    @Column(name = "motivo_rechazo", length = 500)
    private String rejectionReason;

    /** Tenant creado al activar (V10); solo en ACTIVATED. */
    @Column(name = "id_tenant_activado", unique = true)
    private Long activatedTenantId;
}
