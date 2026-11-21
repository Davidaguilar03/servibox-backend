package com.servibox.backend.reporting.dto;

import java.time.LocalDate;

/**
 * Indicadores y filas de GET /api/reporting/receivables (clientes y facturas de venta) y
 * GET /api/reporting/payables (proveedores y facturas de compra).
 */
public final class PortfolioReport {

    private PortfolioReport() {
    }

    public record Summary(
            double totalBalance,
            long invoiceCount,
            double balance0To30,
            double balance31To60,
            double balance61To90,
            double balanceOver90
    ) {
    }

    /**
     * daysOld: dias desde la fecha de vencimiento (la de la factura si no tiene) hasta el
     * corte, 0 si aun no vence. agingBucket: "0-30", "31-60", "61-90" o "90+".
     */
    public record Row(
            Long invoiceId,
            Long counterpartyId,
            String counterpartyName,
            String invoiceNumber,
            LocalDate invoiceDate,
            LocalDate dueDate,
            long daysOld,
            String agingBucket,
            double total,
            double paid,
            double balance
    ) {
    }
}
