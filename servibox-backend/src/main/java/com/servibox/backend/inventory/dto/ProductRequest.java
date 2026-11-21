package com.servibox.backend.inventory.dto;

import com.servibox.backend.inventory.entity.ItemType;
import jakarta.validation.constraints.NotBlank;

/**
 * Cuerpo de POST /api/inventory/products. itemType opcional, PRODUCTO por defecto.
 *
 * * PRODUCTO: purchaseCost, quantity y categoryId obligatorios (400 "Peticion invalida" si
 *   faltan, como antes); salePrice y vatApplies se ignoran.
 * * SERVICIO: salePrice obligatorio y mayor que cero; vatApplies opcional (true por defecto);
 *   purchaseCost, quantity y categoryId se ignoran: quedan 0, 0 y la categoria SERVICIOS.
 */
public record ProductRequest(
        @NotBlank String code,
        @NotBlank String description,
        Double purchaseCost,
        Integer quantity,
        Long categoryId,
        ItemType itemType,
        Double salePrice,
        Boolean vatApplies
) {
}
