package com.servibox.backend.purchases.dto;

import com.servibox.backend.purchases.entity.Payment;

import java.time.LocalDate;

public record PaymentResponse(
        Long id,
        Long purchaseId,
        Long accountId,
        String accountName,
        Double amount,
        LocalDate date,
        /** Lo que sigue debiendose al proveedor despues de este pago. */
        Double pendingBalance
) {

    public static PaymentResponse from(Payment payment, double pendingBalance) {
        return new PaymentResponse(
                payment.getId(),
                payment.getPurchase() != null ? payment.getPurchase().getId() : null,
                payment.getAccount() != null ? payment.getAccount().getId() : null,
                payment.getAccount() != null ? payment.getAccount().getName() : null,
                payment.getAmount(),
                payment.getDate(),
                pendingBalance
        );
    }
}
