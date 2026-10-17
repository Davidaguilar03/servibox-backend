package com.servibox.backend.sales.dto;

import com.servibox.backend.counterparties.dto.CounterpartyResponse;
import com.servibox.backend.sales.entity.PaymentType;
import com.servibox.backend.sales.entity.Sale;
import com.servibox.backend.sales.entity.SaleDetail;
import com.servibox.backend.sales.entity.SaleStatus;

import java.time.LocalDate;
import java.util.List;

public record SaleResponse(
        Long id,
        String invoiceNumber,
        CounterpartyResponse customer,
        LocalDate invoiceDate,
        LocalDate dueDate,
        PaymentType paymentType,
        String paymentMethod,
        SaleStatus status,
        Double subtotal,
        Double ivaPorPagar,
        Double total,
        /** Lo que falta por cobrar. 0 en una ANULADA: una factura anulada no se debe. */
        Double pendingBalance,
        List<SaleDetailResponse> details
) {

    public static SaleResponse from(Sale sale, List<SaleDetail> details, double pendingBalance) {
        return new SaleResponse(
                sale.getId(),
                sale.getInvoiceNumber(),
                CounterpartyResponse.from(sale.getCustomer()),
                sale.getInvoiceDate(),
                sale.getDueDate(),
                sale.getPaymentType(),
                sale.getPaymentMethod(),
                sale.getStatus(),
                sale.getSubtotal(),
                sale.getIvaPorPagar(),
                sale.getTotal(),
                sale.getStatus() == SaleStatus.ANULADA ? 0.0 : pendingBalance,
                details.stream().map(SaleDetailResponse::from).toList()
        );
    }
}
