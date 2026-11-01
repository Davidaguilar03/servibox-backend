package com.servibox.backend.shared;

/**
 * Regla de negocio o de entrada incumplida, con un mensaje pensado para el cliente (400 con ese
 * texto exacto; el frontend lo mapea). Extiende IllegalArgumentException para no cambiar a
 * quien ya la atrapa como tal. Una IllegalArgumentException que no sea esta (de librerias o de
 * programacion) responde un 400 generico sin su mensaje. Ver 03-DECISIONS.md (tarea 52).
 */
public class DomainValidationException extends IllegalArgumentException {

    public DomainValidationException(String mensaje) {
        super(mensaje);
    }
}
