package com.servibox.backend.inventory.entity;

/**
 * Tipo de item del catalogo, como itemType en Autollantas. Un SERVICIO no tiene stock ni costo
 * de compra y su precio es fijo. No cambia despues de crear el item.
 */
public enum ItemType {
    PRODUCTO,
    SERVICIO
}
