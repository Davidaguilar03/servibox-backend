package com.servibox.backend.treasury.service;

/**
 * Operacion manual que no aplica al movimiento: crear, editar o eliminar a mano uno que no
 * es GASTO_OPERATIVO ni INGRESO_OCASIONAL, o cambiarle la categoria al editarlo.
 */
public class InvalidMovementOperationException extends RuntimeException {

    public InvalidMovementOperationException(String mensaje) {
        super(mensaje);
    }
}
