package com.servibox.backend.shared;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SearchTextTest {

    @Test
    void quitaTildesYMayusculasYLaEnieQuedaComoN() {
        assertThat(SearchText.normalize("Niño")).isEqualTo("nino");
        assertThat(SearchText.normalize("NIÑO")).isEqualTo("nino");
        assertThat(SearchText.normalize("Camión")).isEqualTo("camion");
        assertThat(SearchText.normalize("SERVITECA")).isEqualTo("serviteca");
        assertThat(SearchText.normalize("Pingüino Áéíóú")).isEqualTo("pinguino aeiou");
    }

    @Test
    void laFormaDescompuestaDaLoMismoQueLaCompuesta() {
        assertThat(SearchText.normalize("Niño Baterías")).isEqualTo(SearchText.normalize("Niño Baterías"));
    }

    @Test
    void recortaYColapsaEspaciosIncluidosTabulacionesYNoSeparables() {
        assertThat(SearchText.normalize("  taller \t\n  uno  dos ")).isEqualTo("taller uno dos");
        assertThat(SearchText.normalize("   ")).isEmpty();
        assertThat(SearchText.normalize("́")).isEmpty();
    }

    @Test
    void conservaComodinesPuntuacionYLetrasSinDescomposicion() {
        assertThat(SearchText.normalize("100% A_B\\C-d.e")).isEqualTo("100% a_b\\c-d.e");
        assertThat(SearchText.normalize("Straße Øre Łódź")).isEqualTo("straße øre łodz");
        assertThat(SearchText.normalize("İSTANBUL")).isEqualTo("istanbul");
    }

    @Test
    void esIdempotenteYCabeEnLaColumnaConElPeorCaso() {
        String una = SearchText.normalize("  Ñandú  CAMIÓN ");
        assertThat(SearchText.normalize(una)).isEqualTo(una);
        assertThat(SearchText.normalize("한".repeat(150)).length()).isLessThanOrEqualTo(450);
    }
}
