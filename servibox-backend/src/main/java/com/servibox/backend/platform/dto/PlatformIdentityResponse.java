package com.servibox.backend.platform.dto;

import com.servibox.backend.platform.PlatformAdmin;

public record PlatformIdentityResponse(Long id, String email) {
    public static PlatformIdentityResponse from(PlatformAdmin admin) {
        return new PlatformIdentityResponse(admin.getId(), admin.getEmail());
    }
}
