package com.servibox.backend.registration.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

public record RegistrationRequestPage(
        @JsonIgnoreProperties({"policyVersion", "policyAcceptedAt"}) List<PlatformRegistrationRequestResponse> items,
        int page, int size, long totalElements, int totalPages) {
}
