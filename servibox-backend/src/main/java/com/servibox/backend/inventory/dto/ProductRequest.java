package com.servibox.backend.inventory.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record ProductRequest(
        @NotBlank String code,
        String description,
        @NotNull Double purchaseCost,
        Integer quantity,
        @NotNull Long categoryId
) {
}
