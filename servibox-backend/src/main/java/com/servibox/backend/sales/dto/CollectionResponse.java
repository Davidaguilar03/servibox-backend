package com.servibox.backend.sales.dto;

import com.servibox.backend.sales.entity.Collection;

import java.time.LocalDate;

public record CollectionResponse(
        Long id,
        Long saleId,
        Long accountId,
        String accountName,
        Double amount,
        LocalDate date,
        /** Lo que sigue debiendo la factura despues de este abono. */
        Double pendingBalance
) {

    public static CollectionResponse from(Collection collection, double pendingBalance) {
        return new CollectionResponse(
                collection.getId(),
                collection.getSale() != null ? collection.getSale().getId() : null,
                collection.getAccount() != null ? collection.getAccount().getId() : null,
                collection.getAccount() != null ? collection.getAccount().getName() : null,
                collection.getAmount(),
                collection.getDate(),
                pendingBalance
        );
    }
}
