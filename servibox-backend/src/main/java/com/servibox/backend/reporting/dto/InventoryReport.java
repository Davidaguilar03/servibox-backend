package com.servibox.backend.reporting.dto;

import com.servibox.backend.inventory.entity.StockStatus;

import java.time.LocalDate;

/** Indicadores y filas de GET /api/reporting/inventory. Solo items PRODUCTO. */
public final class InventoryReport {

    private InventoryReport() {
    }

    /**
     * Stock y valores al corte (hoy); unitsSoldInPeriod en el rango, sin facturas anuladas.
     * saleValue es stock por precio de venta sin IVA (suggestedPrice).
     */
    public record Summary(
            long productCount,
            long unitsInStock,
            double costValue,
            double saleValue,
            long lowStockCount,
            long criticalStockCount,
            long outOfStockCount,
            long unitsSoldInPeriod
    ) {
    }

    /**
     * lastSaleDate: la ultima venta no anulada de cualquier fecha (null si nunca se vendio).
     * daysWithoutSale: dias desde lastSaleDate hasta el corte, null si nunca se vendio.
     */
    public record Row(
            Long productId,
            String code,
            String description,
            String categoryName,
            int stock,
            StockStatus stockStatus,
            double unitCost,
            double costValue,
            double salePrice,
            long unitsSold,
            LocalDate lastSaleDate,
            Long daysWithoutSale
    ) {
    }
}
