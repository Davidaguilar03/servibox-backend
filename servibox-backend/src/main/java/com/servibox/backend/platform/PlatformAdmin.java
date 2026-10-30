package com.servibox.backend.platform;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "administradores_plataforma")
@Getter
@Setter
public class PlatformAdmin {
    @Id
    @Column(name = "id_administrador")
    private Long id;

    @Column(nullable = false, length = 150, unique = true)
    private String email;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Column(name = "activo", nullable = false)
    private boolean active = true;

    @Column(name = "auth_version", nullable = false)
    private long authVersion;
}
