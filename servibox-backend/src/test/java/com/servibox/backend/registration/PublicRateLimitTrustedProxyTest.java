package com.servibox.backend.registration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Con la cabecera del proxy configurada cuenta su ultima entrada; lo que el cliente antepone no. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "servibox.registration-requests.enabled=true",
        "servibox.rate-limit.enabled=true",
        "servibox.rate-limit.client-ip-header=X-Forwarded-For",
        "servibox.rate-limit.activation.limit=2"})
class PublicRateLimitTrustedProxyTest {

    @Autowired MockMvc mvc;

    private ResultActions activar(String xff) throws Exception {
        // Todas llegan desde el mismo socket, el del proxy.
        return mvc.perform(PublicRateLimitTest.desde(post(RegistrationActivationTest.ACTIVAR), "10.0.0.250")
                .header("X-Forwarded-For", xff).contentType(MediaType.APPLICATION_JSON)
                .content(RegistrationActivationTest.activacion(PublicRateLimitTest.TOKEN_FALSO, "dueno", "clave-segura-1")));
    }

    @Test
    void cadaClienteDetrasDelProxyTieneSuCuota() throws Exception {
        activar("198.51.100.1").andExpect(status().isBadRequest());
        activar("198.51.100.1").andExpect(status().isBadRequest());
        activar("198.51.100.1").andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error").value(PublicRateLimitTest.ACTIVACION_429));
        activar("198.51.100.2").andExpect(status().isBadRequest());
    }

    @Test
    void loQueElClienteAntepongaEnLaCabeceraNoLeDaCuotaNueva() throws Exception {
        activar("203.0.113.9").andExpect(status().isBadRequest());
        activar("1.1.1.1, 203.0.113.9").andExpect(status().isBadRequest());
        activar("2.2.2.2, 3.3.3.3, 203.0.113.9").andExpect(status().isTooManyRequests());
    }
}
