package com.servibox.backend.reporting.dto;

import java.time.LocalDate;

/**
 * Un renglon del listado de movimientos del dashboard. No es un Movement de tesoreria:
 * es el documento de negocio (venta, compra, gasto, ingreso ocasional) resumido.
 *
 * type: VENTA, COMPRA, GASTO o INGRESO. sourceId es el id del documento segun type.
 */
public record MovementSummaryResponse(
        LocalDate date,
        String type,
        Long sourceId,
        String concept,
        double amount,
        Long accountId,
        String accountName
) {
}
