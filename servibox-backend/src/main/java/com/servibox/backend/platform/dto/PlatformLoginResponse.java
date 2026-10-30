package com.servibox.backend.platform.dto;

public record PlatformLoginResponse(String token, PlatformIdentityResponse admin) {
    @Override
    public String toString() {
        return "PlatformLoginResponse[redacted]";
    }
}
