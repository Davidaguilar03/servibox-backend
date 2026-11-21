package com.servibox.backend.inventory.entity;

/**
 * Estado de stock de un PRODUCTO, la misma regla de servibox-frontend/src/lib/stock.ts (umbrales
 * de Autollantas mas un estado para el stock en cero): SIN_STOCK si el stock es 0 o menos,
 * CRITICO si es menor o igual al umbral rojo de la categoria, BAJO si es menor o igual al
 * amarillo y NORMAL en otro caso. Los umbrales son NOT NULL en CATEGORIA_PRODUCTOS; los valores
 * por defecto del frontend (amarillo 5, rojo 2) solo cubren un umbral nulo. Ver 03-DECISIONS.md.
 */
public enum StockStatus {
    NORMAL,
    BAJO,
    CRITICO,
    SIN_STOCK;

    public static final int AMARILLO_POR_DEFECTO = 5;
    public static final int ROJO_POR_DEFECTO = 2;

    public static StockStatus of(Integer cantidad, Integer amarillo, Integer rojo) {
        int stock = cantidad != null ? cantidad : 0;
        int umbralRojo = rojo != null ? rojo : ROJO_POR_DEFECTO;
        int umbralAmarillo = amarillo != null ? amarillo : AMARILLO_POR_DEFECTO;
        if (stock <= 0) {
            return SIN_STOCK;
        }
        if (stock <= umbralRojo) {
            return CRITICO;
        }
        if (stock <= umbralAmarillo) {
            return BAJO;
        }
        return NORMAL;
    }
}
