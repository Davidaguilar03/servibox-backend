package com.servibox.backend.inventory.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record ProductRequest(
        @NotBlank String code,
        @NotBlank String description,
        @NotNull Double purchaseCost,
        @NotNull Integer quantity,
        @NotNull Long categoryId
) {
}
