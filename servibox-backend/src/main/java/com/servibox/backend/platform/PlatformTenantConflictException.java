package com.servibox.backend.platform;

/** Version esperada distinta o transicion de estado no permitida sobre un tenant. 409. */
public class PlatformTenantConflictException extends RuntimeException {

    static final String VERSION_DISTINTA = "El recurso cambio; actualiza e intenta de nuevo";
    static final String YA_SUSPENDIDO = "El tenant ya esta suspendido";
    static final String YA_ACTIVO = "El tenant ya esta activo";

    public PlatformTenantConflictException(String message) {
        super(message);
    }
}
