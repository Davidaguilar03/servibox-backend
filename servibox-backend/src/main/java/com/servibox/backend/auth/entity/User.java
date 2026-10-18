package com.servibox.backend.auth.entity;

import com.servibox.backend.shared.TenantAwareEntity;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * El username es unico dentro de un tenant, no globalmente: dos negocios distintos pueden
 * tener cada uno su usuario "admin". De ahi la restriccion compuesta (id_tenant, username)
 * en vez de unique en la columna sola.
 */
@Entity
@Data
@EqualsAndHashCode(callSuper = true)
@AttributeOverride(name = "id", column = @Column(name = "id_usuario"))
@Table(
        name = "USUARIOS",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_usuario_tenant_username",
                columnNames = {"id_tenant", "username"}
        )
)
public class User extends TenantAwareEntity {

    @Column(name = "username", length = 50, nullable = false)
    private String username;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Column(name = "email", length = 150)
    private String email;

    @Column(name = "activo", nullable = false)
    private boolean active = true;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "rol", length = 30, nullable = false)
    private Role role;
}
