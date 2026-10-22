package com.servibox.backend.auth;

/** servibox.registration.enabled=false. 403, sin crear nada. */
public class RegistrationDisabledException extends RuntimeException {

    public RegistrationDisabledException() {
        super("El registro de nuevas servitecas esta deshabilitado");
    }
}
