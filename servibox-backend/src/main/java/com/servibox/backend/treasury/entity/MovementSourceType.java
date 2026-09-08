package com.servibox.backend.treasury.entity;

/**
 * De donde salio un movimiento automatico. Junto con Movement.sourceId forma el par
 * (tipo, id) que reemplazo a las cuatro relaciones ManyToOne opcionales que habia antes.
 *
 * **Agregar un origen nuevo es agregar un valor aqui y nada mas.** No hay columna nueva,
 * ni migracion de esquema, ni un parametro mas en aplicarMovimiento. Ese era justamente el
 * problema del diseno anterior. Ver 03-DECISIONS.md.
 *
 * El id al que apunta sourceId se interpreta segun este tipo: SALE es un id de Sale,
 * PURCHASE de Purchase, y asi. No hay clave foranea que lo garantice; la garantia esta en
 * que todo Movement se crea desde un metodo de TreasuryService que recibe la entidad
 * tipada, nunca un id suelto.
 */
public enum MovementSourceType {
    TRANSFER,
    SALE,
    PURCHASE,
    OCCASIONAL_INCOME
}
