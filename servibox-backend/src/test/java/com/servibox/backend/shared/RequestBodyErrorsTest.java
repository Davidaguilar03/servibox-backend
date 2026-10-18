package com.servibox.backend.shared;

import com.jayway.jsonpath.JsonPath;
import com.servibox.backend.auth.entity.Role;
import com.servibox.backend.auth.entity.User;
import com.servibox.backend.auth.repository.UserRepository;
import com.servibox.backend.counterparties.entity.CounterpartyRole;
import com.servibox.backend.counterparties.entity.DocumentType;
import com.servibox.backend.tenant.Tenant;
import com.servibox.backend.tenant.TenantContext;
import com.servibox.backend.tenant.TenantRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Un cuerpo ilegible o un valor de enum que no existe (en el cuerpo o en un parametro de
 * query) es un error del cliente: 400 con un mensaje legible, no 500. Un caso por cada enum que enumera el diccionario.
 *
 * Tipo de cuenta y categoria de movimiento se prueban en sus endpoints reales. Rol, tipo de
 * tercero y tipo de documento no los recibe ningun endpoint todavia (no hay alta de
 * usuarios ni de terceros por la API), asi que van por un controlador de prueba que solo
 * existe en este contexto.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(RequestBodyErrorsTest.EnumsDelDiccionarioController.class)
class RequestBodyErrorsTest {

    private static final String PASSWORD = "clave-correcta";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TenantRepository tenantRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private String token;

    /** Recibe los enums del diccionario que ningun endpoint real recibe todavia. */
    @RestController
    static class EnumsDelDiccionarioController {

        record Cuerpo(Role role, CounterpartyRole counterpartyRole, DocumentType documentType) {
        }

        @PostMapping("/api/test/enums")
        Cuerpo eco(@RequestBody Cuerpo cuerpo) {
            return cuerpo;
        }
    }

    @BeforeEach
    void seed() throws Exception {
        String slug = "taller-errores-" + System.nanoTime();
        Tenant tenant = new Tenant();
        tenant.setName(slug);
        tenant.setSlug(slug);
        tenant.setActive(Boolean.TRUE);
        TenantContext.setTenantId(tenantRepository.save(tenant).getId());
        try {
            User user = new User();
            user.setUsername("admin");
            user.setPasswordHash(passwordEncoder.encode(PASSWORD));
            user.setActive(true);
            user.setRole(Role.ADMIN);
            userRepository.save(user);
        } finally {
            TenantContext.clear();
        }
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"tenantSlug":"%s","username":"admin","password":"%s"}
                                """.formatted(slug, PASSWORD)))
                .andExpect(status().isOk())
                .andReturn();
        token = JsonPath.read(result.getResponse().getContentAsString(), "$.token");
    }

    @AfterEach
    void cleanUp() {
        TenantContext.clear();
    }

    private ResultActions enviar(String ruta, String cuerpo) throws Exception {
        return mockMvc.perform(post(ruta)
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(cuerpo));
    }

    @Test
    void unJsonMalFormadoEs400() throws Exception {
        enviar("/api/treasury/accounts", "{\"name\":\"Caja\",")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(
                        "El cuerpo de la peticion no es un JSON valido o un campo tiene un valor del tipo equivocado"));
    }

    @Test
    void unTipoDeCuentaInvalidoEs400() throws Exception {
        enviar("/api/treasury/accounts", """
                {"name":"Caja","initialBalance":0,"type":"CASH"}
                """)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(
                        "Valor invalido 'CASH' en el campo type. Valores permitidos: EFECTIVO, BANCO, DIGITAL"));
    }

    @Test
    void unaCategoriaDeMovimientoInvalidaEs400() throws Exception {
        enviar("/api/treasury/movements", """
                {"accountId":1,"category":"NOMINA","concept":"Pago","amount":1000}
                """)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Valor invalido 'NOMINA' en el campo category. Valores"
                        + " permitidos: GASTO_OPERATIVO, INGRESO_OCASIONAL, TRANSFERENCIA, PAGO_COMPRA, RECAUDO_VENTA"));
    }

    @Test
    void unRolInvalidoEs400() throws Exception {
        enviar("/api/test/enums", """
                {"role":"EMPLEADO"}
                """)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(
                        "Valor invalido 'EMPLEADO' en el campo role. Valores permitidos: ADMIN, MECANICO, CAJERO"));
    }

    @Test
    void unTipoDeTerceroInvalidoEs400() throws Exception {
        enviar("/api/test/enums", """
                {"counterpartyRole":"EMPLEADO"}
                """)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Valor invalido 'EMPLEADO' en el campo counterpartyRole."
                        + " Valores permitidos: CLIENTE, PROVEEDOR, AMBOS"));
    }

    @Test
    void unTipoDeDocumentoInvalidoEs400() throws Exception {
        enviar("/api/test/enums", """
                {"documentType":"DNI"}
                """)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(
                        "Valor invalido 'DNI' en el campo documentType. Valores permitidos: CC, NIT, CE, RUT, PASAPORTE"));
    }

    /** Lo mismo vale para un enum en un parametro de query, no solo en el cuerpo. */
    @Test
    void unaCategoriaInvalidaEnElQueryEs400() throws Exception {
        mockMvc.perform(get("/api/treasury/movements")
                        .header("Authorization", "Bearer " + token)
                        .param("category", "X"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Valor invalido 'X' en el parametro category. Valores"
                        + " permitidos: GASTO_OPERATIVO, INGRESO_OCASIONAL, TRANSFERENCIA, PAGO_COMPRA, RECAUDO_VENTA"));
    }

    /** Los valores validos pasan: el 400 es por el valor, no por el endpoint de prueba. */
    @Test
    void losValoresValidosPasan() throws Exception {
        enviar("/api/test/enums", """
                {"role":"CAJERO","counterpartyRole":"AMBOS","documentType":"NIT"}
                """)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("CAJERO"));
    }
}
