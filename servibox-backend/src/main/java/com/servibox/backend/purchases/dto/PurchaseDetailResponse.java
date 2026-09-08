package com.servibox.backend.purchases.dto;

import com.servibox.backend.purchases.entity.PurchaseDetail;

public record PurchaseDetailResponse(
        Long id,
        Long productId,
        String productCode,
        String productDescription,
        Integer quantity,
        Double price,
        Double ivaAmount
) {

    public static PurchaseDetailResponse from(PurchaseDetail detail) {
        return new PurchaseDetailResponse(
                detail.getId(),
                detail.getProduct() != null ? detail.getProduct().getId() : null,
                detail.getProduct() != null ? detail.getProduct().getCode() : null,
                detail.getProduct() != null ? detail.getProduct().getDescription() : null,
                detail.getQuantity(),
                detail.getPrice(),
                detail.getIvaAmount()
        );
    }
}
