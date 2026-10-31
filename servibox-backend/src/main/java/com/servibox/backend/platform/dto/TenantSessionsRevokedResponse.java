package com.servibox.backend.platform.dto;

public record TenantSessionsRevokedResponse(Long tenantId, int revokedUsers) {
}
