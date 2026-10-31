package com.servibox.backend.platform.dto;

import com.servibox.backend.tenant.Tenant;
import com.servibox.backend.tenant.TenantStatus;

import java.time.LocalDateTime;

/** Fila del listado de tenants de la plataforma. Fechas en UTC. */
public record PlatformTenantResponse(
        Long id,
        String name,
        String slug,
        String nit,
        String phone,
        TenantStatus status,
        Long version,
        LocalDateTime createdAt,
        LocalDateTime suspendedAt,
        String suspensionReason) {

    public static PlatformTenantResponse from(Tenant t) {
        return new PlatformTenantResponse(t.getId(), t.getName(), t.getSlug(), t.getNit(), t.getPhone(),
                TenantStatus.of(t), t.getVersion(), t.getCreatedAt(), t.getSuspendedAt(), t.getSuspensionReason());
    }
}
