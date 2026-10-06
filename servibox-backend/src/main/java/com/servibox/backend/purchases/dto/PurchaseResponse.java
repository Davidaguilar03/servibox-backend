package com.servibox.backend.purchases.dto;

import com.servibox.backend.counterparties.dto.CounterpartyResponse;
import com.servibox.backend.purchases.entity.PaymentType;
import com.servibox.backend.purchases.entity.Purchase;
import com.servibox.backend.purchases.entity.PurchaseDetail;
import com.servibox.backend.purchases.entity.PurchaseStatus;

import java.time.LocalDate;
import java.util.List;

public record PurchaseResponse(
        Long id,
        String invoiceNumber,
        CounterpartyResponse supplier,
        LocalDate invoiceDate,
        LocalDate dueDate,
        PaymentType paymentType,
        Long accountId,
        String accountName,
        String paymentMethod,
        PurchaseStatus status,
        Double subtotal,
        Double ivaTotal,
        Double total,
        Double pendingBalance,
        List<PurchaseDetailResponse> details
) {

    public static PurchaseResponse from(Purchase purchase, List<PurchaseDetail> details,
                                        double pendingBalance) {
        return new PurchaseResponse(
                purchase.getId(),
                purchase.getInvoiceNumber(),
                CounterpartyResponse.from(purchase.getSupplier()),
                purchase.getInvoiceDate(),
                purchase.getDueDate(),
                purchase.getPaymentType(),
                purchase.getAccount() != null ? purchase.getAccount().getId() : null,
                purchase.getAccount() != null ? purchase.getAccount().getName() : null,
                purchase.getPaymentMethod(),
                purchase.getStatus(),
                purchase.getSubtotal(),
                purchase.getIvaTotal(),
                purchase.getTotal(),
                pendingBalance,
                details.stream().map(PurchaseDetailResponse::from).toList()
        );
    }
}
