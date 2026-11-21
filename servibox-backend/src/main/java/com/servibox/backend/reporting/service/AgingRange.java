package com.servibox.backend.reporting.service;

import com.servibox.backend.shared.DomainValidationException;

import java.util.Arrays;
import java.util.stream.Collectors;

/** Rangos de antiguedad de la cartera, en dias. El parametro antiguedad usa la etiqueta. */
public enum AgingRange {
    D0_30("0-30", 0, 30),
    D31_60("31-60", 31, 60),
    D61_90("61-90", 61, 90),
    D90_MAS("90+", 91, Long.MAX_VALUE);

    private final String label;
    private final long desde;
    private final long hasta;

    AgingRange(String label, long desde, long hasta) {
        this.label = label;
        this.desde = desde;
        this.hasta = hasta;
    }

    public String label() {
        return label;
    }

    public static AgingRange of(long dias) {
        return Arrays.stream(values()).filter(r -> dias >= r.desde && dias <= r.hasta).findFirst().orElse(D0_30);
    }

    /** Null si no viene. Un valor que no es etiqueta es 400 con el formato de los demas parametros. */
    public static AgingRange parse(String valor) {
        if (valor == null || valor.isBlank()) {
            return null;
        }
        return Arrays.stream(values()).filter(r -> r.label.equals(valor.trim())).findFirst()
                .orElseThrow(() -> new DomainValidationException("Valor invalido '" + valor
                        + "' en el parametro antiguedad. Valores permitidos: "
                        + Arrays.stream(values()).map(AgingRange::label).collect(Collectors.joining(", "))));
    }
}
