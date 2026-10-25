package com.servibox.backend.shared;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;

/**
 * Montos en pesos colombianos. formatear: los mensajes de error, es-CO sin decimales
 * (3250000.0 es "$ 3.250.000"), unico sitio con ese formato. aPesosEnteros: el redondeo al
 * peso entero del precio sugerido y del IVA del costo. Ver 02-CONVENTIONS.md.
 */
public final class Moneda {

    private Moneda() {
    }

    public static String formatear(double monto) {
        DecimalFormatSymbols simbolos = new DecimalFormatSymbols();
        simbolos.setGroupingSeparator('.');
        simbolos.setMinusSign('-');
        // DecimalFormat no es thread-safe: una instancia por llamada.
        DecimalFormat formato = new DecimalFormat("$ #,##0;-$ #,##0", simbolos);
        formato.setRoundingMode(RoundingMode.HALF_UP);
        return formato.format(monto);
    }

    /**
     * Redondea al peso entero mas cercano, la mitad hacia arriba (HALF_UP): 1966.5 es 1967.
     * Recibe el resultado exacto en BigDecimal, no un double, para que un 1966,5 no llegue
     * como 1966,4999... Se aplica una sola vez, al resultado final.
     */
    public static double aPesosEnteros(BigDecimal monto) {
        return monto.setScale(0, RoundingMode.HALF_UP).doubleValue();
    }
}
