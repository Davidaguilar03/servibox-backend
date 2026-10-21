package com.servibox.backend.inventory.service;

/** Otra categoria del mismo tenant ya tiene ese nombre. Solo lo valida la edicion. */
public class DuplicateCategoryNameException extends RuntimeException {

    public DuplicateCategoryNameException(String name) {
        super("Ya existe una categoria con el nombre " + name);
    }
}
