package com.servibox.backend.reporting.dto;

/** Una fila del Reporte de IVA. ivaNeto = ivaGenerado - ivaDescontable. */
public record IvaCategoryResponse(
        String category,
        double ivaGenerado,
        double ivaDescontable,
        double ivaNeto
) {
}
