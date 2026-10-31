package com.servibox.backend.auth;

import com.servibox.backend.platform.PlatformAdminRepository;
import com.servibox.backend.platform.PlatformBootstrapService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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

/** Con la cabecera del proxy configurada (X-Real-IP) cada cliente tiene su cuota; lo antepuesto no cuenta. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "servibox.login-throttle.enabled=true",
        "servibox.login-throttle.ip-account-limit=2",
        "servibox.rate-limit.client-ip-header=X-Real-IP"})
class LoginThrottleTrustedProxyTest {

    @Autowired MockMvc mvc;
    @Autowired PlatformBootstrapService bootstrap;
    @Autowired PlatformAdminRepository admins;

    @BeforeEach
    void seed() {
        bootstrap.create(LoginThrottleTest.PLATFORM_EMAIL, LoginThrottleTest.PLATFORM_PASSWORD.toCharArray());
    }

    @AfterEach
    void cleanup() {
        admins.deleteAll();
    }

    private ResultActions login(String realIp, String password) throws Exception {
        // Todas llegan desde el mismo socket, el del proxy.
        return mvc.perform(LoginThrottleTest.desde(post(LoginThrottleTest.PLATFORM_LOGIN), "10.0.0.250")
                .header("X-Real-IP", realIp).contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(LoginThrottleTest.PLATFORM_EMAIL, password)));
    }

    @Test
    void cadaClienteDetrasDelProxyTieneSuCuotaYLoAntepuestoNoDaCuotaNueva() throws Exception {
        login("198.51.100.1", "mala-clave").andExpect(status().isUnauthorized());
        login("9.9.9.9, 198.51.100.1", "mala-clave").andExpect(status().isUnauthorized());
        login("8.8.8.8, 198.51.100.1", LoginThrottleTest.PLATFORM_PASSWORD).andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error").value(LoginThrottleTest.MENSAJE_429));
        login("198.51.100.2", LoginThrottleTest.PLATFORM_PASSWORD).andExpect(status().isOk());
    }
}
