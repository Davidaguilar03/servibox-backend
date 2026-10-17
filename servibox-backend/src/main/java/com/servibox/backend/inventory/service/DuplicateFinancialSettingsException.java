package com.servibox.backend.inventory.service;

/** Segunda configuracion financiera para un tenant que ya tiene una. */
public class DuplicateFinancialSettingsException extends RuntimeException {

    public DuplicateFinancialSettingsException() {
        super("El tenant ya tiene una configuracion financiera; edita la existente en vez de crear otra");
    }
}
