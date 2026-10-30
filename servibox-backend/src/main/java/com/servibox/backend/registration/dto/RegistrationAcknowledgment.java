package com.servibox.backend.registration.dto;

/** Acuse 202 de una solicitud: sin tenant, usuario ni token. Un reintento con la misma clave recibe el mismo. */
public record RegistrationAcknowledgment(String requestId, String status, String message) {

    public static RegistrationAcknowledgment of(String requestId) {
        return new RegistrationAcknowledgment(requestId, "PENDING", "Solicitud recibida, pendiente de revision");
    }
}
