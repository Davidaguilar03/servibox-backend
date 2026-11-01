package com.servibox.backend.auth;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.util.Base64;

/**
 * Comparacion BCrypt de relleno para los logins que terminan sin evaluar una contrasena real
 * (cuenta inexistente o inactiva, tenant suspendido): pagan el mismo costo que una contrasena
 * incorrecta y el tiempo de respuesta no revela si la cuenta existe. El hash se genera una
 * vez al arrancar con el mismo PasswordEncoder, asi que tiene su mismo costo. Se compara un
 * candidato fijo (no la contrasena recibida) para que la longitud de la entrada no cambie nada.
 */
@Component
public class LoginTimingEqualizer {

    private static final String CANDIDATO = "servibox-login-timing-equalizer";

    private final PasswordEncoder passwordEncoder;
    private final String dummyHash;

    public LoginTimingEqualizer(PasswordEncoder passwordEncoder) {
        this.passwordEncoder = passwordEncoder;
        byte[] raw = new byte[24];
        new SecureRandom().nextBytes(raw);
        this.dummyHash = passwordEncoder.encode(Base64.getEncoder().encodeToString(raw));
    }

    public void compareDummy() {
        passwordEncoder.matches(CANDIDATO, dummyHash);
    }
}
