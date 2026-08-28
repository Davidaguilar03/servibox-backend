package com.servibox.backend.auth.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * tenantSlug es obligatorio porque el username solo es unico dentro de un tenant.
 * Sin el, "admin" es ambiguo entre negocios distintos.
 */
public record LoginRequest(
        @NotBlank String tenantSlug,
        @NotBlank String username,
        @NotBlank String password
) {
}
