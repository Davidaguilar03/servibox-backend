package com.servibox.backend.treasury.entity;

/**
 * Que concepto tecnico representa un movimiento (`categoria_movimiento`). El tipo
 * (INGRESO / EGRESO) da el signo sobre el saldo; la categoria dice de donde viene.
 *
 * Coherencia, validada en TreasuryService: GASTO_OPERATIVO y PAGO_COMPRA son EGRESO;
 * INGRESO_OCASIONAL y RECAUDO_VENTA son INGRESO; TRANSFERENCIA es una pata EGRESO y una
 * pata INGRESO.
 *
 * Solo GASTO_OPERATIVO e INGRESO_OCASIONAL son manuales: el usuario los crea, edita y
 * elimina directamente. Los demas se gestionan desde su operacion (la transferencia, la
 * venta, la compra). Ver 04-DATA-MODEL.md, "Decisiones de implementacion".
 */
public enum MovementCategory {
    GASTO_OPERATIVO,
    INGRESO_OCASIONAL,
    TRANSFERENCIA,
    PAGO_COMPRA,
    RECAUDO_VENTA;

    public boolean esManual() {
        return this == GASTO_OPERATIVO || this == INGRESO_OCASIONAL;
    }
}
