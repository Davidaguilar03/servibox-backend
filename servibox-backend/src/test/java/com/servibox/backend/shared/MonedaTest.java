package com.servibox.backend.shared;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Formato es-CO sin decimales de los montos en mensajes de error. */
class MonedaTest {

    @Test
    void formateaConPuntoDeMilesSinDecimalesNiNotacionCientifica() {
        assertThat(Moneda.formatear(3250000.0)).isEqualTo("$ 3.250.000");
        assertThat(Moneda.formatear(4212600.0)).isEqualTo("$ 4.212.600");
        assertThat(Moneda.formatear(1.2E7)).isEqualTo("$ 12.000.000");
        assertThat(Moneda.formatear(0.0)).isEqualTo("$ 0");
        assertThat(Moneda.formatear(999.0)).isEqualTo("$ 999");
    }

    @Test
    void redondeaAlPesoYMuestraElSignoDeUnSaldoNegativo() {
        assertThat(Moneda.formatear(1500.5)).isEqualTo("$ 1.501");
        assertThat(Moneda.formatear(1500.4)).isEqualTo("$ 1.500");
        assertThat(Moneda.formatear(-30.0)).isEqualTo("-$ 30");
    }
}
