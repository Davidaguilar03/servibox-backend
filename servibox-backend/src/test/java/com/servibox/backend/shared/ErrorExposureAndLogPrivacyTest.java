package com.servibox.backend.shared;

import com.jayway.jsonpath.JsonPath;
import com.servibox.backend.tenant.Tenant;
import com.servibox.backend.tenant.TenantRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Tarea 52: las IllegalArgumentException e IllegalStateException que no son de dominio responden
 * el 400 de siempre con "Peticion invalida"; las de dominio, su mensaje exacto. El log guarda la
 * clase y el lugar, nunca el mensaje ni datos personales (correo, contrasena, token, valores de
 * columnas). Rutas de prueba bajo /api/inventory (cadena tenant) solo para provocar cada caso.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@ExtendWith(OutputCaptureExtension.class)
class ErrorExposureAndLogPrivacyTest {

    static final String CORREO = "ana.rojas.privada@uno-secreto.co";

    @TestConfiguration
    static class RutasDePrueba {

        @RestController
        static class ControladorDePrueba {

            private final TenantRepository tenants;

            ControladorDePrueba(TenantRepository tenants) {
                this.tenants = tenants;
            }

            @GetMapping("/api/inventory/t52/estado")
            String estado() {
                throw new IllegalStateException("Fallo interno con " + CORREO);
            }

            @GetMapping("/api/inventory/t52/argumento")
            String argumento() {
                throw new IllegalArgumentException("Libreria rechazo " + CORREO);
            }

            /** Duplicado real en la base (uk_tenant_slug) con un valor que parece un correo. */
            @GetMapping("/api/inventory/t52/duplicado")
            String duplicado() {
                for (int i = 0; i < 2; i++) {
                    Tenant t = new Tenant();
                    t.setName("Duplicado");
                    t.setSlug(CORREO);
                    t.setActive(Boolean.TRUE);
                    tenants.saveAndFlush(t);
                }
                return "no llega";
            }
        }
    }

    @Autowired MockMvc mvc;

    private String token;

    @BeforeEach
    void registrar() throws Exception {
        String slug = "t52-" + UUID.randomUUID().toString().substring(0, 8);
        String r = mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content("""
                {"businessName":"Taller T52","nit":"900123456-7","phone":"3001234567","slug":"%s",
                 "adminFullName":"Ana T52","adminEmail":"ana@example.invalid","adminUsername":"admin",
                 "password":"clave-segura-1","acceptTerms":true}
                """.formatted(slug))).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        token = JsonPath.read(r, "$.token");
    }

    private ResultActions conToken(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder b)
            throws Exception {
        return mvc.perform(b.header("Authorization", "Bearer " + token));
    }

    private static String error(String mensaje, int status) {
        return "{\"error\":\"" + mensaje + "\",\"status\":" + status + "}";
    }

    @Test
    void excepcionesNoDeDominioDanMensajeGenericoYElLogNoGuardaSuMensaje(CapturedOutput output) throws Exception {
        conToken(get("/api/inventory/t52/estado")).andExpect(status().isBadRequest())
                .andExpect(content().json(error("Peticion invalida", 400), true));
        conToken(get("/api/inventory/t52/argumento")).andExpect(status().isBadRequest())
                .andExpect(content().json(error("Peticion invalida", 400), true));
        // IllegalArgumentException real de una libreria: BigDecimal no acepta un saldo infinito (1e400).
        conToken(post("/api/treasury/accounts").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Infinita\",\"type\":\"EFECTIVO\",\"initialBalance\":1e400}"))
                .andExpect(status().isBadRequest()).andExpect(content().json(error("Peticion invalida", 400), true));

        assertThat(output.getAll()).contains("java.lang.IllegalStateException at ")
                .contains("java.lang.IllegalArgumentException at ")
                .contains("java.lang.NumberFormatException at ")
                .doesNotContain(CORREO).doesNotContain("uno-secreto").doesNotContain("Infinite or NaN");
    }

    @Test
    void errorDeBaseResponde500GenericoSinValoresEnElLog(CapturedOutput output) throws Exception {
        conToken(get("/api/inventory/t52/duplicado")).andExpect(status().isInternalServerError())
                .andExpect(content().json(error("Error interno del servidor", 500), true));
        // Ni el SqlExceptionHelper de Hibernate (apagado) ni el manejador escriben el valor duplicado.
        assertThat(output.getAll()).contains("Error no controlado: ").contains("DataIntegrityViolationException")
                .doesNotContain(CORREO).doesNotContain("uno-secreto");
    }

    /** HSTS solo en prod (StartupLogAndHstsTest); en el perfil test no se envia. */
    @Test
    void sinHstsFueraDeProd() throws Exception {
        mvc.perform(get("/api/health")).andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
                        .doesNotExist("Strict-Transport-Security"));
    }

    @Test
    void mensajesDeDominioLleganExactos() throws Exception {
        conToken(post("/api/treasury/accounts").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Color\",\"type\":\"EFECTIVO\",\"initialBalance\":0,\"color\":\"rojo\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().json(error("El color debe tener el formato #RRGGBB", 400), true));
        conToken(post("/api/inventory/products").contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\"P1\",\"description\":\"Llanta\",\"purchaseCost\":1000,\"quantity\":1,"
                        + "\"categoryId\":987654}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().json(error("Categoria no encontrada: 987654", 400), true));
        conToken(get("/api/reporting/kpis?desde=2026-10-10&hasta=2026-10-01")).andExpect(status().isBadRequest())
                .andExpect(content().json(error("La fecha desde no puede ser posterior a la fecha hasta", 400), true));
    }

    @Test
    void loginsFallidosYExitososNoDejanCredencialesNiTokensEnElLog(CapturedOutput output) throws Exception {
        String clave = "ClaveUnicaQueNoDebeVerse-52";
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content("""
                {"tenantSlug":"no-existe-52","username":"%s","password":"%s"}
                """.formatted(CORREO, clave))).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/platform/auth/login").contentType(MediaType.APPLICATION_JSON).content("""
                {"email":"%s","password":"%s"}
                """.formatted(CORREO, clave))).andExpect(status().isUnauthorized());
        assertThat(output.getAll()).doesNotContain(clave).doesNotContain(CORREO).doesNotContain(token);
    }
}
