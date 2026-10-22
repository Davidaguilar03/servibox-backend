package com.servibox.backend.auth;

/** Registro con un slug que ya usa otra serviteca. 409. */
public class DuplicateTenantSlugException extends RuntimeException {

    public DuplicateTenantSlugException(String slug) {
        super("Ya existe una serviteca con el identificador " + slug);
    }
}
