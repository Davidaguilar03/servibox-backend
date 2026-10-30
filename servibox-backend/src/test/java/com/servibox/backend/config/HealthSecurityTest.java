package com.servibox.backend.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class HealthSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void saludPublicaSinDetalles() throws Exception {
        mockMvc.perform(get("/api/health"))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"status\":\"UP\"}"));
    }

    @Test
    void saludNoAbreOtrasRutasNiMetodos() throws Exception {
        mockMvc.perform(post("/api/health")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/health/details")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/tenant")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/inventory/products")).andExpect(status().isUnauthorized());
    }
}
