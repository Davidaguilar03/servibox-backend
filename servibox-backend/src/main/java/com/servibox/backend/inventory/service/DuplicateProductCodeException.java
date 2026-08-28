package com.servibox.backend.inventory.service;

/**
 * Se intento guardar un producto con un codigo que ya usa otro producto del mismo tenant.
 * Existe para que el usuario vea un mensaje legible en vez del error crudo de constraint
 * violation que devolveria la base.
 */
public class DuplicateProductCodeException extends RuntimeException {

    public DuplicateProductCodeException(String code) {
        super("Ya existe un producto con el codigo " + code);
    }
}
