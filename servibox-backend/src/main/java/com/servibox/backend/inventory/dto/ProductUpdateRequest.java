package com.servibox.backend.inventory.dto;

import com.servibox.backend.inventory.entity.ItemType;
import jakarta.validation.constraints.NotBlank;

/**
 * Cuerpo de PUT /api/inventory/products/{id}: las mismas reglas que ProductRequest, sin
 * quantity. El stock solo cambia con ventas y compras; si llega, se ignora. itemType es
 * opcional; si llega distinto del que tiene el item es 400 (el tipo no cambia). En un servicio
 * vatApplies null conserva el IVA que tenia.
 */
public record ProductUpdateRequest(
        @NotBlank String code,
        @NotBlank String description,
        Double purchaseCost,
        Long categoryId,
        ItemType itemType,
        Double salePrice,
        Boolean vatApplies
) {
}
