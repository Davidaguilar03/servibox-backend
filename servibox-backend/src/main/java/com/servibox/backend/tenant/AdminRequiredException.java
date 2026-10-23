package com.servibox.backend.tenant;

/** Un usuario sin rol ADMIN intenta editar el perfil del tenant. 403, sin cambiar nada. */
public class AdminRequiredException extends RuntimeException {

    public AdminRequiredException() {
        super("Solo un administrador puede editar los datos de la serviteca");
    }
}
