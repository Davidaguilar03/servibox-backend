package com.servibox.backend.registration;

/** Slug no disponible, clave de reintento reutilizada, o estado o version que ya no permiten la operacion. 409. */
public class RegistrationRequestConflictException extends RuntimeException {

    static final String SLUG_NO_DISPONIBLE = "El codigo solicitado no esta disponible";
    static final String CLAVE_REUTILIZADA = "La clave de reintento ya se uso con otros datos";
    static final String ESTADO_INVALIDO = "La solicitud ya no permite esta operacion";
    static final String VERSION_DISTINTA = "El recurso cambio; actualiza e intenta de nuevo";

    public RegistrationRequestConflictException(String message) {
        super(message);
    }
}
