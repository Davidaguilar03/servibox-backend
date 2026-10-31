package com.servibox.backend.platform.dto;

import com.servibox.backend.auth.entity.Role;
import com.servibox.backend.auth.entity.User;

import java.time.LocalDateTime;

/** Usuario de un tenant visto por la plataforma. Nunca lleva hash, version de sesion ni token. */
public record PlatformTenantUserResponse(
        Long id,
        String username,
        String fullName,
        String email,
        Role role,
        boolean active,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {

    public static PlatformTenantUserResponse from(User u) {
        return new PlatformTenantUserResponse(u.getId(), u.getUsername(), u.getFullName(), u.getEmail(),
                u.getRole(), u.isActive(), u.getCreatedAt(), u.getUpdatedAt());
    }
}
