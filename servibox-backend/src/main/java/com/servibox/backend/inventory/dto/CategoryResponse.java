package com.servibox.backend.inventory.dto;

import com.servibox.backend.inventory.entity.ProductCategory;

import java.util.List;

public record CategoryResponse(
        Long id,
        String name,
        String color,
        Integer yellowStockMin,
        Integer redStockMin,
        Double targetMargin,
        List<TaxTypeResponse> taxTypes
) {

    public static CategoryResponse from(ProductCategory category) {
        return new CategoryResponse(
                category.getId(),
                category.getName(),
                category.getColor(),
                category.getYellowStockMin(),
                category.getRedStockMin(),
                category.getTargetMargin(),
                category.getTaxTypes().stream().map(TaxTypeResponse::from).toList()
        );
    }
}
