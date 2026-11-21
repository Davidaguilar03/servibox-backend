package com.servibox.backend.reporting.dto;

import com.servibox.backend.inventory.entity.ItemType;

/** Indicadores y filas de GET /api/reporting/products-sold. Sin facturas anuladas. */
public final class ProductsSoldReport {

    private ProductsSoldReport() {
    }

    /** revenue es sin IVA; cost es el costo congelado en cada linea de venta. */
    public record Summary(
            long unitsSold,
            double revenue,
            double cost,
            double grossMargin,
            double marginPercent
    ) {
    }

    /** marginPercent en por ciento (12.5 es 12,5 %), 0 si no hubo ingresos. */
    public record Row(
            Long productId,
            String code,
            String description,
            ItemType itemType,
            String categoryName,
            long units,
            double revenue,
            double cost,
            double margin,
            double marginPercent
    ) {
    }
}
