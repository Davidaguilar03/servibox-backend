package com.servibox.backend.shared;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/** Formato es-CO sin decimales de los montos en mensajes de error y redondeo al peso entero. */
class MonedaTest {

    @Test
    void centavosUsaHalfUpTambienParaMitadesNegativasYResiduos() {
        assertThat(Moneda.aCentavos(1.005)).isEqualTo(1.01);
        assertThat(Moneda.aCentavos(-1.005)).isEqualTo(-1.01);
        assertThat(Moneda.aCentavos(28178315.549999997)).isEqualTo(28178315.55);
        assertThat(Moneda.aCentavos(new BigDecimal("2.674999999"))).isEqualTo(2.67);
    }

    @Test
    void sumasYRestasDecimalesConservanCentavos() {
        assertThat(Moneda.sumar(0.1, 0.2)).isEqualTo(0.3);
        assertThat(Moneda.restar(0.3, 0.1)).isEqualTo(0.2);
        assertThat(Moneda.sumar(16963933.02, Moneda.sumar(4406852.45, 6807530.08)))
                .isEqualTo(28178315.55);
    }

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

    @Test
    void aPesosEnterosRedondeaLaMitadHaciaArriba() {
        // IVA del costo de 10350 al 19 por ciento: 1966.5 sube a 1967, no baja a 1966.
        assertThat(Moneda.aPesosEnteros(BigDecimal.valueOf(10350.0).multiply(BigDecimal.valueOf(0.19))))
                .isEqualTo(1967.0);
        assertThat(Moneda.aPesosEnteros(new BigDecimal("2345.55"))).isEqualTo(2346.0);
        assertThat(Moneda.aPesosEnteros(new BigDecimal("17635.4999"))).isEqualTo(17635.0);
    }

    @Test
    void aPesosEnterosNoCambiaUnResultadoExacto() {
        assertThat(Moneda.aPesosEnteros(BigDecimal.valueOf(10000.0).multiply(BigDecimal.valueOf(0.19))))
                .isEqualTo(1900.0);
        assertThat(Moneda.aPesosEnteros(new BigDecimal("50000.00"))).isEqualTo(50000.0);
    }
}
