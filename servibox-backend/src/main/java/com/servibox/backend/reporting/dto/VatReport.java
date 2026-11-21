package com.servibox.backend.reporting.dto;

/** Indicadores y filas de GET /api/reporting/vat. Mismas cifras que /iva. */
public final class VatReport {

    private VatReport() {
    }

    /** ivaNeto es el saldo por pagar: generado menos descontable. */
    public record Summary(
            double ivaGenerado,
            double ivaDescontable,
            double ivaNeto,
            long invoicesWithIva
    ) {
    }

    /** ratePercent: la tarifa de las lineas en por ciento (19.0), congelada al facturar. */
    public record Row(
            String categoryName,
            double ratePercent,
            double taxableBase,
            double ivaGenerado,
            double ivaDescontable,
            double ivaNeto
    ) {
    }
}
