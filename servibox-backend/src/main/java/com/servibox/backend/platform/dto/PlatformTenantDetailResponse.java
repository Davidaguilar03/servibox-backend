package com.servibox.backend.platform.dto;

import com.servibox.backend.auth.entity.Role;
import com.servibox.backend.registration.RegistrationRequestStatus;

import java.time.LocalDateTime;
import java.util.List;

/** Detalle de un tenant: sus datos, conteo de usuarios por rol y estado y la solicitud que lo creo, si existe. */
public record PlatformTenantDetailResponse(
        PlatformTenantResponse tenant,
        long totalUsers,
        List<UserCount> userCounts,
        OriginRequest registrationRequest) {

    public record UserCount(Role role, boolean active, long count) {
    }

    public record OriginRequest(String id, RegistrationRequestStatus status, LocalDateTime createdAt,
            LocalDateTime reviewedAt) {
    }
}
