package com.servibox.backend.treasury.entity;

/**
 * Factura que genero un movimiento de venta o de compra. Junto con Movement.sourceId forma
 * el par (tipo, id) con el que se enlazan hoy los movimientos RECAUDO_VENTA y PAGO_COMPRA.
 *
 * **Desaparece en el paso 3 del refactor de normalizacion**, cuando esos movimientos pasen
 * a enlazarse con las claves foraneas `id_venta` / `id_compra` del diccionario. Los
 * origenes TRANSFER, OCCASIONAL_INCOME y OPERATIONAL_EXPENSE ya se quitaron en el paso 2:
 * esos registros ahora son movimientos con categoria. Ver 03-DECISIONS.md.
 *
 * El id al que apunta sourceId se interpreta segun este tipo: SALE es un id de Sale y
 * PURCHASE de Purchase. No hay clave foranea que lo garantice; la garantia esta en que
 * todo Movement se crea desde un metodo de TreasuryService que recibe la entidad tipada.
 */
public enum MovementSourceType {
    SALE,
    PURCHASE
}
