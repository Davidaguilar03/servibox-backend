package com.servibox.backend.tenant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Data;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

@Entity
@Data
@Table(name = "TENANTS")
public class Tenant {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id_tenant")
    private Long id;

    @Column(name = "nombre_tenant", length = 150, nullable = false)
    private String name;

    @Column(name = "slug_tenant", length = 100, nullable = false, unique = true)
    private String slug;

    /** Desde V2. Null en los tenants anteriores al registro publico. */
    @Column(name = "nit_tenant", length = 25)
    private String nit;

    @Column(name = "telefono_tenant", length = 25)
    private String phone;

    @Column(name = "activo_tenant", nullable = false)
    private Boolean active = Boolean.TRUE;

    /** Desde V11. Sube solo al suspender o reactivar; es el expectedVersion de la plataforma. */
    @Column(name = "version_tenant", nullable = false)
    private Long version = 0L;

    @Column(name = "fecha_suspension_tenant")
    private LocalDateTime suspendedAt;

    @Column(name = "motivo_suspension_tenant", length = 500)
    private String suspensionReason;

    /** En UTC, igual que created_at y updated_at de las entidades de negocio. */
    @Column(name = "fecha_creacion_tenant", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now(ZoneOffset.UTC);
        }
    }
}
