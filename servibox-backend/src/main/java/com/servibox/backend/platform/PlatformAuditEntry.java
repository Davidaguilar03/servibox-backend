package com.servibox.backend.platform;

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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;

/** Fila de AUDITORIA_PLATAFORMA (V11, V12). Global, solo insercion; tenant, usuario y solicitud sin FK. */
@Entity
@Table(name = "auditoria_plataforma")
@Getter
@Setter
public class PlatformAuditEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id_auditoria")
    private Long id;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "accion", length = 30, nullable = false)
    private PlatformAuditAction action;

    @Column(name = "id_administrador", nullable = false)
    private Long adminId;

    /** NULL solo en RESEND_ACTIVATION (CHECK ck_auditoria_objetivo). */
    @Column(name = "id_tenant_objetivo")
    private Long tenantId;

    @Column(name = "id_solicitud_objetivo", length = 36)
    private String requestId;

    @Column(name = "id_usuario_objetivo")
    private Long userId;

    @Column(name = "motivo", length = 500)
    private String reason;

    @Column(name = "fecha_auditoria", nullable = false)
    private LocalDateTime createdAt;
}
