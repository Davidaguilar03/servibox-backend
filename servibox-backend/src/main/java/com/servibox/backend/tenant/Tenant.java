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

@Entity
@Data
@Table(name = "TENANTS")
public class Tenant {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id_tenant")
    private Long id;

    @Column(name = "nombre_tenant", nullable = false)
    private String name;

    @Column(name = "slug_tenant", nullable = false, unique = true)
    private String slug;

    @Column(name = "activo_tenant", nullable = false)
    private Boolean active = Boolean.TRUE;

    @Column(name = "fecha_creacion_tenant", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}
