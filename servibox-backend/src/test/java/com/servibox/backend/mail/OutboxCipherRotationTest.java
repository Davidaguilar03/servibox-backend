package com.servibox.backend.mail;

import org.junit.jupiter.api.Test;

import java.security.GeneralSecurityException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Rotacion de MAIL_OUTBOX_ENCRYPTION_KEY (tarea 51). Claves ficticias, solo de esta prueba. */
class OutboxCipherRotationTest {

    private static final String VIEJA = "unit-rotation-old-outbox-key-2026-xxxxxxx";
    private static final String NUEVA = "unit-rotation-new-outbox-key-2026-xxxxxxx";
    private static final String OTRA = "unit-rotation-other-outbox-key-2026-xxxxxx";

    @Test
    void conLaAnteriorDescifraLoPendienteViejoYCifraSoloConLaActual() throws Exception {
        String pendienteViejo = new OutboxCipher(VIEJA, "").encrypt("cuerpo viejo");
        OutboxCipher rotado = new OutboxCipher(NUEVA, VIEJA);

        assertThat(rotado.decrypt(pendienteViejo)).isEqualTo("cuerpo viejo");
        String nuevo = rotado.encrypt("cuerpo nuevo");
        assertThat(rotado.decrypt(nuevo)).isEqualTo("cuerpo nuevo");
        // Lo nuevo va con la actual: la clave vieja sola no lo abre, la actual sola si.
        assertThatThrownBy(() -> new OutboxCipher(VIEJA, "").decrypt(nuevo)).isInstanceOf(GeneralSecurityException.class);
        assertThat(new OutboxCipher(NUEVA, "").decrypt(nuevo)).isEqualTo("cuerpo nuevo");
    }

    @Test
    void sinLaAnteriorLoViejoNoSeDescifraYUnaTerceraClaveTampoco() throws Exception {
        String pendienteViejo = new OutboxCipher(VIEJA, "").encrypt("cuerpo viejo");
        assertThatThrownBy(() -> new OutboxCipher(NUEVA, "").decrypt(pendienteViejo))
                .isInstanceOf(GeneralSecurityException.class);
        assertThatThrownBy(() -> new OutboxCipher(NUEVA, OTRA).decrypt(pendienteViejo))
                .isInstanceOf(GeneralSecurityException.class);
        // Alterado: ninguna de las dos lo abre.
        char[] c = new OutboxCipher(NUEVA, VIEJA).encrypt("x").toCharArray();
        c[20] = c[20] == 'A' ? 'B' : 'A';
        assertThatThrownBy(() -> new OutboxCipher(NUEVA, VIEJA).decrypt(new String(c)))
                .isInstanceOf(GeneralSecurityException.class);
    }

    @Test
    void anteriorCortaOIgualALaActualNoArranca() {
        for (String previa : new String[]{"corta", " ", NUEVA}) {
            assertThatThrownBy(() -> new OutboxCipher(NUEVA, previa)).isInstanceOf(IllegalStateException.class)
                    .hasMessage("MAIL_OUTBOX_ENCRYPTION_KEY_PREVIOUS requiere al menos 32 bytes y debe ser distinta de MAIL_OUTBOX_ENCRYPTION_KEY");
        }
        assertThat(new OutboxCipher(NUEVA, null)).isNotNull();
    }
}
