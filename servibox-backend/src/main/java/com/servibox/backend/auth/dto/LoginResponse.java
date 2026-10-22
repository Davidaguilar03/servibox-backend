package com.servibox.backend.auth.dto;

import com.servibox.backend.auth.entity.Role;
import com.servibox.backend.auth.entity.User;
import com.servibox.backend.tenant.Tenant;

/**
 * El token y, para que el frontend no tenga que decodificarlo, quien entro y a que taller.
 * fullName es null en los usuarios creados antes del registro publico. Tambien es la
 * respuesta de POST /api/auth/register.
 */
public record LoginResponse(String token, UserInfo user, TenantInfo tenant) {

    public record UserInfo(String username, Role role, String fullName) {
    }

    public record TenantInfo(String name, String slug) {
    }

    /** Con el usuario y el tenant que el login ya verifico: sin consultas extra. */
    public static LoginResponse from(String token, User user, Tenant tenant) {
        return new LoginResponse(token,
                new UserInfo(user.getUsername(), user.getRole(), user.getFullName()),
                new TenantInfo(tenant.getName(), tenant.getSlug()));
    }
}
