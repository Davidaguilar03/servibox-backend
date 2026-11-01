package com.servibox.backend.config;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Despliegue VPS: el frontend (https://servibox.co) llama a la API en otro origen. CORS solo para
 * los origenes exactos de CORS_ALLOWED_ORIGINS, en ambas cadenas; sin la variable, nada cambia.
 */
class CorsConfigurationTest {

    private static final String FRONT = "https://app.example.invalid";
    private static final String OTHER = "https://evil.example.invalid";

    @Nested
    @SpringBootTest(properties = "servibox.cors.allowed-origins=" + FRONT)
    @AutoConfigureMockMvc
    @ActiveProfiles("test")
    class ConOrigenConfigurado {

        @Autowired
        private MockMvc mockMvc;

        @Test
        void preflightDelOrigenPermitidoEnAmbasCadenas() throws Exception {
            for (String ruta : new String[] {"/api/auth/login", "/api/platform/auth/login", "/api/inventory/products"}) {
                mockMvc.perform(options(ruta).header("Origin", FRONT)
                                .header("Access-Control-Request-Method", "POST")
                                .header("Access-Control-Request-Headers", "authorization,content-type"))
                        .andExpect(status().isOk())
                        .andExpect(header().string("Access-Control-Allow-Origin", FRONT))
                        .andExpect(header().doesNotExist("Access-Control-Allow-Credentials"));
            }
        }

        @Test
        void otroOrigenNoRecibeCabecerasCors() throws Exception {
            mockMvc.perform(options("/api/auth/login").header("Origin", OTHER)
                            .header("Access-Control-Request-Method", "POST"))
                    .andExpect(status().isForbidden())
                    .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
            mockMvc.perform(get("/api/health").header("Origin", OTHER))
                    .andExpect(status().isForbidden())
                    .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
        }

        @Test
        void peticionRealDelOrigenPermitidoExponeRetryAfter() throws Exception {
            mockMvc.perform(get("/api/health").header("Origin", FRONT))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Access-Control-Allow-Origin", FRONT))
                    .andExpect(header().string("Access-Control-Expose-Headers", "Retry-After, Content-Disposition"));
            mockMvc.perform(get("/api/tenant").header("Origin", FRONT))
                    .andExpect(status().isUnauthorized())
                    .andExpect(header().string("Access-Control-Allow-Origin", FRONT));
        }
    }

    @Nested
    @SpringBootTest
    @AutoConfigureMockMvc
    @ActiveProfiles("test")
    class SinOrigenConfigurado {

        @Autowired
        private MockMvc mockMvc;

        @Test
        void sinCorsComoAntes() throws Exception {
            mockMvc.perform(options("/api/auth/login").header("Origin", FRONT)
                            .header("Access-Control-Request-Method", "POST"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
            mockMvc.perform(get("/api/health").header("Origin", FRONT))
                    .andExpect(status().isOk())
                    .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
        }
    }
}
