package com.servibox.backend.inventory.dto;

import jakarta.validation.constraints.NotBlank;

import java.util.List;

public record CategoryRequest(
        @NotBlank String name,
        String color,
        Integer yellowStockMin,
        Integer redStockMin,
        Double targetMargin,
        List<Long> taxTypeIds
) {
}
