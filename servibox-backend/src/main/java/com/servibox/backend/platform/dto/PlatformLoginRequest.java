package com.servibox.backend.platform.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record PlatformLoginRequest(@NotBlank @Size(max = 150) String email,
                                   @NotBlank @Size(max = 72) String password) {
    @Override
    public String toString() {
        return "PlatformLoginRequest[redacted]";
    }
}
