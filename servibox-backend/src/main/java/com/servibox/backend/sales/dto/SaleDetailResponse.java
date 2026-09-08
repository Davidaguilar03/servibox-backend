package com.servibox.backend.sales.dto;

import com.servibox.backend.sales.entity.SaleDetail;

public record SaleDetailResponse(
        Long id,
        Long productId,
        String productCode,
        String productDescription,
        Integer quantity,
        Double price,
        /** IVA congelado al facturar. No se recalcula. */
        Double ivaAmount
) {

    public static SaleDetailResponse from(SaleDetail detail) {
        return new SaleDetailResponse(
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
