package com.servibox.backend.inventory.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/** Todo obligatorio salvo los impuestos: CATEGORIA_PRODUCTOS no admite nulos. */
public record CategoryRequest(
        @NotBlank String name,
        @NotBlank String color,
        @NotNull Integer yellowStockMin,
        @NotNull Integer redStockMin,
        @NotNull Double targetMargin,
        List<Long> taxTypeIds
) {
}
