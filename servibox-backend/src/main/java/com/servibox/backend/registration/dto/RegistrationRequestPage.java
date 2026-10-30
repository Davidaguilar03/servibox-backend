package com.servibox.backend.registration.dto;

import java.util.List;

public record RegistrationRequestPage(
        List<PlatformRegistrationRequestResponse> items, int page, int size, long totalElements, int totalPages) {
}
