package com.servibox.backend.inventory.dto;

import com.servibox.backend.inventory.entity.Product;

import java.math.BigDecimal;
import java.math.RoundingMode;

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
                aDosDecimales(product.getTaxAmount()),
                aDosDecimales(product.getSuggestedPrice()),
                product.getCategory() != null ? product.getCategory().getId() : null,
                product.getCategory() != null ? product.getCategory().getName() : null
        );
    }

    /**
     * Como quedan en iva_producto y precio_sugerido (NUMERIC(14,2), redondeo HALF_UP): el POST
     * y el PUT responden lo mismo que dara el GET. No cambia lo que se guarda.
     */
    private static Double aDosDecimales(Double valor) {
        return valor == null ? null : BigDecimal.valueOf(valor).setScale(2, RoundingMode.HALF_UP).doubleValue();
    }
}
