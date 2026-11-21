package com.servibox.backend.reporting.dto;

import com.servibox.backend.purchases.entity.PurchaseStatus;

import java.time.LocalDate;

/** Indicadores y filas de GET /api/reporting/purchases. */
public final class PurchasesReport {

    private PurchasesReport() {
    }

    /** Sin anuladas. totalPurchased es con IVA; deductibleVat es el IVA de las compras. */
    public record Summary(
            double totalPurchased,
            long invoiceCount,
            double deductibleVat,
            double pendingTotal
    ) {
    }

    /** pendingBalance es 0 en una ANULADA. */
    public record Row(
            Long purchaseId,
            String invoiceNumber,
            LocalDate invoiceDate,
            Long supplierId,
            String supplierName,
            PurchaseStatus status,
            double subtotal,
            double iva,
            double total,
            double pendingBalance
    ) {
    }
}
