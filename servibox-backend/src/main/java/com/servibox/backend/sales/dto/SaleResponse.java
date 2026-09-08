package com.servibox.backend.sales.dto;

import com.servibox.backend.sales.entity.PaymentType;
import com.servibox.backend.sales.entity.Sale;
import com.servibox.backend.sales.entity.SaleDetail;
import com.servibox.backend.sales.entity.SaleStatus;

import java.time.LocalDate;
import java.util.List;

public record SaleResponse(
        Long id,
        String invoiceNumber,
        Long customerId,
        String customerName,
        LocalDate invoiceDate,
        LocalDate dueDate,
        PaymentType paymentType,
        Long accountId,
        String accountName,
        String paymentMethod,
        SaleStatus status,
        Double subtotal,
        Double ivaPorPagar,
        Double total,
        Double pendingBalance,
        List<SaleDetailResponse> details
) {

    public static SaleResponse from(Sale sale, List<SaleDetail> details, double pendingBalance) {
        return new SaleResponse(
                sale.getId(),
                sale.getInvoiceNumber(),
                sale.getCustomer() != null ? sale.getCustomer().getId() : null,
                sale.getCustomer() != null ? sale.getCustomer().getName() : null,
                sale.getInvoiceDate(),
                sale.getDueDate(),
                sale.getPaymentType(),
                sale.getAccount() != null ? sale.getAccount().getId() : null,
                sale.getAccount() != null ? sale.getAccount().getName() : null,
                sale.getPaymentMethod(),
                sale.getStatus(),
                sale.getSubtotal(),
                sale.getIvaPorPagar(),
                sale.getTotal(),
                pendingBalance,
                details.stream().map(SaleDetailResponse::from).toList()
        );
    }
}
