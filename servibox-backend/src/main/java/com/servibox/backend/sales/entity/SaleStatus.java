package com.servibox.backend.sales.entity;

/**
 * PAGADA: cobrada por completo. PENDIENTE: a credito, esperando abonos.
 * ANULADA: anulada, con el stock ya devuelto.
 */
public enum SaleStatus {
    PAGADA,
    PENDIENTE,
    ANULADA
}
