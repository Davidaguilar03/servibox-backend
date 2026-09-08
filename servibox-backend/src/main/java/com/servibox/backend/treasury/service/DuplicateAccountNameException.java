package com.servibox.backend.treasury.service;

/**
 * Se intento crear una cuenta con un nombre que ya usa otra cuenta del mismo tenant.
 * Existe para que el usuario vea un mensaje legible en vez del error crudo de constraint
 * violation que devolveria la base. Mismo patron que DuplicateProductCodeException.
 */
public class DuplicateAccountNameException extends RuntimeException {

    public DuplicateAccountNameException(String name) {
        super("Ya existe una cuenta con el nombre " + name);
    }
}
