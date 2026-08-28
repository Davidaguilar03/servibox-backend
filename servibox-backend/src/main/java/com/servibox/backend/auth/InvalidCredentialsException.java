package com.servibox.backend.auth;

/**
 * Credenciales de login rechazadas. El mensaje es siempre el mismo sin importar la causa
 * real: distinguir usuario inexistente de contrasena incorrecta le confirmaria a un
 * atacante que usuarios existen en que tenant.
 */
public class InvalidCredentialsException extends RuntimeException {

    public InvalidCredentialsException() {
        super("Credenciales invalidas");
    }
}
