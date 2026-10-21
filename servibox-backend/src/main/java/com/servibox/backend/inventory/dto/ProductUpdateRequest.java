package com.servibox.backend.inventory.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * Cuerpo de PUT /api/inventory/products/{id}: las mismas validaciones que ProductRequest,
 * sin quantity. El stock solo cambia con ventas y compras; si llega, se ignora.
 */
public record ProductUpdateRequest(
        @NotBlank String code,
        @NotBlank String description,
        @NotNull Double purchaseCost,
        @NotNull Long categoryId
) {
}
