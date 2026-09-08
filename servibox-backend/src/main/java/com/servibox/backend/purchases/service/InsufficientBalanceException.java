package com.servibox.backend.purchases.service;

/**
 * La cuenta no tiene con que pagar. Autollantas lo valida en el formulario antes de
 * guardar una compra de contado; aqui la validacion vive en el service.
 */
public class InsufficientBalanceException extends RuntimeException {

    public InsufficientBalanceException(String cuenta, double saldo, double requerido) {
        super("Saldo insuficiente en " + cuenta + ": hay " + saldo + " y se requieren " + requerido);
    }
}
