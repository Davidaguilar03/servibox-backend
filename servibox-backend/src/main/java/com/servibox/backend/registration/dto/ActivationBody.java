package com.servibox.backend.registration.dto;

/** Cuerpo de POST /api/registration-requests/activate. Sin anotaciones: las reglas son las del registro. */
public record ActivationBody(String token, String username, String password) {
}
