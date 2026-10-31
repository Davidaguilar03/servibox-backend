package com.servibox.backend.registration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Compuerta apagada con el limite encendido: siempre 404 y la cuota queda intacta. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "servibox.rate-limit.enabled=true",
        "servibox.rate-limit.registration-requests.limit=1",
        "servibox.rate-limit.activation.limit=1"})
class PublicRateLimitGateOffTest {

    @Autowired MockMvc mvc;
    @Autowired PublicRateLimiter limiter;

    @Test
    void conLaCompuertaApagadaResponde404YNoConsumeCuota() throws Exception {
        for (int i = 0; i < 5; i++) {
            mvc.perform(post(RegistrationRequestTest.RUTA).header("Idempotency-Key", "clave-off-rl-" + i)
                            .contentType(MediaType.APPLICATION_JSON).content(RegistrationRequestTest.cuerpo("taller-off-rl")))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error").value("Recurso no encontrado"));
            mvc.perform(post(RegistrationActivationTest.ACTIVAR).contentType(MediaType.APPLICATION_JSON)
                            .content(RegistrationActivationTest.activacion(PublicRateLimitTest.TOKEN_FALSO, "dueno", "clave-segura-1")))
                    .andExpect(status().isNotFound());
        }
        // MockMvc llega desde 127.0.0.1: esa IP conserva su unica unidad en cada ruta.
        MockHttpServletRequest local = new MockHttpServletRequest();
        local.setRemoteAddr("127.0.0.1");
        assertThatCode(() -> limiter.checkSubmission(local)).doesNotThrowAnyException();
        assertThatCode(() -> limiter.checkActivation(local)).doesNotThrowAnyException();
        assertThatThrownBy(() -> limiter.checkSubmission(local)).isInstanceOf(RateLimitExceededException.class);
        assertThatThrownBy(() -> limiter.checkActivation(local)).isInstanceOf(RateLimitExceededException.class);
    }
}
