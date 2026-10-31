package com.servibox.backend.platform.dto;

/** Cuerpo de suspender y reactivar (expectedVersion obligatorio) y de revocar (solo motivo opcional). */
public record PlatformTenantActionRequest(Long expectedVersion, String reason) {
}
