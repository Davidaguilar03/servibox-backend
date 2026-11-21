package com.servibox.backend.reporting.dto;

import com.servibox.backend.sales.entity.PaymentType;
import com.servibox.backend.sales.entity.SaleStatus;

import java.time.LocalDate;

/** Indicadores y filas de GET /api/reporting/sales. */
public final class SalesReport {

    private SalesReport() {
    }

    /** Todo sin anuladas salvo annulledCount. Los montos son con IVA. */
    public record Summary(
            double totalSold,
            long invoiceCount,
            double averageTicket,
            double cashTotal,
            double creditTotal,
            long annulledCount
    ) {
    }

    /** iva es el IVA generado (total menos subtotal). pendingBalance es 0 en una ANULADA. */
    public record Row(
            Long saleId,
            String invoiceNumber,
            LocalDate invoiceDate,
            Long customerId,
            String customerName,
            PaymentType paymentType,
            SaleStatus status,
            double subtotal,
            double iva,
            double total,
            double pendingBalance
    ) {
    }
}
