package com.servibox.backend.auth.entity;

import com.servibox.backend.shared.TenantAwareEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * El username es unico dentro de un tenant, no globalmente: dos negocios distintos pueden
 * tener cada uno su usuario "admin". De ahi la restriccion compuesta (tenant_id, username)
 * en vez de unique en la columna sola.
 */
@Entity
@Data
@EqualsAndHashCode(callSuper = true)
@Table(
        name = "USUARIOS",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_usuario_tenant_username",
                columnNames = {"tenant_id", "username"}
        )
)
public class User extends TenantAwareEntity {

    @Column(name = "username", nullable = false)
    private String username;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Column(name = "email")
    private String email;

    @Column(name = "activo", nullable = false)
    private boolean active = true;

    @Enumerated(EnumType.STRING)
    @Column(name = "rol", nullable = false)
    private Role role;
}
