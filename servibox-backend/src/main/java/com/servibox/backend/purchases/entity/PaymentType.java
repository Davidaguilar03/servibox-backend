package com.servibox.backend.purchases.entity;

/**
 * Gemelo de sales.entity.PaymentType. Se duplica el enum a proposito en vez de compartir
 * uno: son dos valores fijos, y una clase comun obligaria a que purchases y sales
 * dependieran entre si o de un paquete compartido por dos constantes.
 */
public enum PaymentType {
    CONTADO,
    CREDITO
}
