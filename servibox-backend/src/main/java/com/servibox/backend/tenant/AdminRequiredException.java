package com.servibox.backend.tenant;

/**
 * Un usuario sin rol ADMIN intenta una operacion reservada al administrador: editar el
 * perfil del tenant, la configuracion financiera o las categorias. 403, sin cambiar nada.
 */
public class AdminRequiredException extends RuntimeException {

    public AdminRequiredException() {
        super("Solo un administrador puede editar los datos de la serviteca");
    }

    public AdminRequiredException(String mensaje) {
        super(mensaje);
    }
}
