package com.servibox.backend.auth;

import com.servibox.backend.auth.repository.UserRepository;
import com.servibox.backend.tenant.TenantRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Con servibox.registration.enabled=false el registro responde 403 y no crea nada. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "servibox.registration.enabled=false")
class TenantRegistrationDisabledTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TenantRepository tenantRepository;

    @Autowired
    private UserRepository userRepository;

    @Test
    void conElRegistroDeshabilitadoResponde403SinCrearNada() throws Exception {
        long tenants = tenantRepository.count();
        long usuarios = userRepository.count();

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"businessName":"Serviteca","nit":"900123456-7","phone":"3001234567",
                                 "slug":"taller-cerrado","adminFullName":"Ana Rojas","adminEmail":"ana@cerrado.co",
                                 "adminUsername":"admin","password":"clave-segura-1","acceptTerms":true}
                                """))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("El registro de nuevas servitecas esta deshabilitado"))
                .andExpect(jsonPath("$.status").value(403));

        assertThat(tenantRepository.count()).isEqualTo(tenants);
        assertThat(userRepository.count()).isEqualTo(usuarios);
        assertThat(tenantRepository.findBySlug("taller-cerrado")).isEmpty();
    }
}
