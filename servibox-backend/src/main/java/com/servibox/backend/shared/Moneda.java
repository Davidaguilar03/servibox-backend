package com.servibox.backend.shared;

import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;

/**
 * Montos en los mensajes de error, como pesos colombianos (es-CO) sin decimales:
 * 3250000.0 es "$ 3.250.000". Unico sitio con ese formato. Ver 02-CONVENTIONS.md.
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
}
