package com.servibox.backend.shared;

/**
 * Un recurso direccionado por la ruta no existe para el tenant que pregunta. Se traduce a
 * 404.
 *
 * El mismo 404 cubre "no existe" y "existe pero es de otro tenant" a proposito: distinguir
 * los dos casos le confirmaria a un cliente que cierto id existe en otra cuenta, que es
 * justo lo que el aislamiento por tenant tiene que ocultar. Mismo criterio que el mensaje
 * generico de login en AuthController.
 *
 * Para un id que llega dentro del cuerpo de una peticion la respuesta correcta sigue
 * siendo 400 (IllegalArgumentException): ahi el problema es la peticion, no la ruta.
 */
public class ResourceNotFoundException extends RuntimeException {

    public ResourceNotFoundException(String mensaje) {
        super(mensaje);
    }
}
