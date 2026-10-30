package com.servibox.backend.registration.dto;

/** Cuerpo de aprobar (solo expectedVersion) y de rechazar (expectedVersion y reason). */
public record ReviewRequest(Long expectedVersion, String reason) {
}
