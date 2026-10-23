package com.servibox.backend.purchases.service;

import com.servibox.backend.shared.Moneda;

/**
 * La cuenta no tiene con que pagar. Autollantas lo valida en el formulario antes de
 * guardar una compra de contado; aqui la validacion vive en el service. La usan las
 * compras, los pagos y las transferencias. Montos con Moneda.formatear.
 */
public class InsufficientBalanceException extends RuntimeException {

    public InsufficientBalanceException(String cuenta, double saldo, double requerido) {
        super("Saldo insuficiente en " + cuenta + ": hay " + Moneda.formatear(saldo)
                + " y se requieren " + Moneda.formatear(requerido));
    }
}
