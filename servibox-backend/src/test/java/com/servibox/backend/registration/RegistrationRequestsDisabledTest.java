package com.servibox.backend.registration;

import com.servibox.backend.tenant.TenantRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Configuracion por defecto: solicitudes apagadas y registro inmediato intacto. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RegistrationRequestsDisabledTest {

    @Autowired MockMvc mvc;
    @Autowired RegistrationRequestRepository requests;
    @Autowired TenantRepository tenants;

    @Value("${servibox.registration-requests.enabled}")
    boolean enabled;

    @Test
    void laCompuertaVieneApagadaYLaRutaRespondeComoNoDisponible() throws Exception {
        assertThat(enabled).isFalse();
        mvc.perform(post(RegistrationRequestTest.RUTA).header("Idempotency-Key", "clave-off-1")
                        .contentType(MediaType.APPLICATION_JSON).content(RegistrationRequestTest.cuerpo("taller-off")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("Recurso no encontrado"))
                .andExpect(jsonPath("$.status").value(404));
        assertThat(requests.count()).isZero();
    }

    @Test
    void conLaCompuertaApagadaActivarRespondeComoNoDisponible() throws Exception {
        long antes = tenants.count();
        mvc.perform(post(RegistrationActivationTest.ACTIVAR).contentType(MediaType.APPLICATION_JSON)
                        .content(RegistrationActivationTest.activacion("A".repeat(43), "dueno", "clave-segura-1")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("Recurso no encontrado"));
        assertThat(tenants.count()).isEqualTo(antes);
    }

    @Test
    void elRegistroInmediatoSigueCreandoTenantYSesion() throws Exception {
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content("""
                        {"businessName":"Serviteca","nit":"900123456-7","phone":"3001234567",
                         "slug":"taller-inmediato-off","adminFullName":"Ana Rojas","adminEmail":"ana@uno.co",
                         "adminUsername":"admin","password":"clave-segura-1","acceptTerms":true}
                        """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.token").isNotEmpty())
                .andExpect(jsonPath("$.tenant.slug").value("taller-inmediato-off"));
        assertThat(tenants.findBySlug("taller-inmediato-off")).isPresent();
        assertThat(requests.count()).isZero();
    }
}
