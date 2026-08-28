package com.servibox.backend.inventory.dto;

import com.servibox.backend.inventory.entity.Product;

public record ProductResponse(
        Long id,
        String code,
        String description,
        Double purchaseCost,
        Integer quantity,
        Double taxAmount,
        Double suggestedPrice,
        Long categoryId,
        String categoryName
) {

    public static ProductResponse from(Product product) {
        return new ProductResponse(
                product.getId(),
                product.getCode(),
                product.getDescription(),
                product.getPurchaseCost(),
                product.getQuantity(),
                product.getTaxAmount(),
                product.getSuggestedPrice(),
                product.getCategory() != null ? product.getCategory().getId() : null,
                product.getCategory() != null ? product.getCategory().getName() : null
        );
    }
}
