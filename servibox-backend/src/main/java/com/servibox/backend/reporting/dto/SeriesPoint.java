package com.servibox.backend.reporting.dto;

/** Un punto de la grafica de un reporte en vivo: etiqueta (fecha ISO, nombre o rango) y valor. */
public record SeriesPoint(String label, double value) {
}
