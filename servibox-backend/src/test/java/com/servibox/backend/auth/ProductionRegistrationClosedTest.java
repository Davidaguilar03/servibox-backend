package com.servibox.backend.auth;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Prod con la compuerta apagada y REGISTRATION_ENABLED por defecto (true): no queda ninguna via
 * publica de alta. Dev y test los siguen cubriendo TenantRegistrationTest,
 * TenantRegistrationDisabledTest y RegistrationRequestsDisabledTest sin cambios.
 */
class ProductionRegistrationClosedTest extends ProductionProfileTestBase {

    @Test
    void registroCompletamenteCerradoConLaCompuertaApagada() throws Exception {
        registroInmediatoResponde404SinCrearNada();
        mvc.perform(post("/api/registration-requests").header("Idempotency-Key", "clave-prod")
                .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isNotFound()).andExpect(content().json(NOT_FOUND, true));
        mvc.perform(post("/api/registration-requests/activate").contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":\"x\",\"username\":\"admin\",\"password\":\"clave-segura-1\"}"))
                .andExpect(status().isNotFound()).andExpect(content().json(NOT_FOUND, true));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM solicitudes_registro", Long.class)).isZero();
    }
}
