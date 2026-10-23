package com.servibox.backend.counterparties;

import com.jayway.jsonpath.JsonPath;
import com.servibox.backend.auth.entity.Role;
import com.servibox.backend.auth.entity.User;
import com.servibox.backend.auth.repository.UserRepository;
import com.servibox.backend.tenant.Tenant;
import com.servibox.backend.tenant.TenantContext;
import com.servibox.backend.tenant.TenantRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

import java.util.Map;

import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * POST /api/counterparties alineado con el PUT (recorte, mensajes y limites), formato del
 * correo y el documento 222222222222 reservado al Consumidor final.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CounterpartiesValidationTest {

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

    @BeforeEach
    void seed() throws Exception {
        String slug = "taller-terceros-validacion-" + System.nanoTime();
        Tenant tenant = new Tenant();
        tenant.setName(slug);
        tenant.setSlug(slug);
        tenant.setActive(Boolean.TRUE);
        Long tenantId = tenantRepository.save(tenant).getId();

        TenantContext.setTenantId(tenantId);
        User user = new User();
        user.setUsername("admin");
        user.setPasswordHash(passwordEncoder.encode(PASSWORD));
        user.setActive(true);
        user.setRole(Role.ADMIN);
        userRepository.save(user);
        TenantContext.clear();

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

    private ResultActions crear(String cuerpo) throws Exception {
        return mockMvc.perform(post("/api/counterparties")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(cuerpo));
    }

    private ResultActions editar(int id, String cuerpo) throws Exception {
        return mockMvc.perform(put("/api/counterparties/" + id)
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(cuerpo));
    }

    private static String cuerpo(String role, String name, String documentNumber, String email, String phone) {
        return """
                {"role":"%s","name":"%s","documentType":"NIT","documentNumber":"%s","email":"%s","phone":"%s"}
                """.formatted(role, name, documentNumber, email, phone);
    }

    private ResultActions listar() throws Exception {
        return mockMvc.perform(get("/api/counterparties").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
    }

    private int idDe(ResultActions resultado) throws Exception {
        return JsonPath.read(resultado.andReturn().getResponse().getContentAsString(), "$.id");
    }

    @Test
    void elDocumentoDelConsumidorFinalNoSePuedeUsarEnElPostNiEnElPut() throws Exception {
        String mensaje = "El documento 222222222222 es del Consumidor final";
        // Todavia no existe el Consumidor final en este tenant: igual esta reservado.
        crear(cuerpo("PROVEEDOR", "Impostor", "222222222222", "", ""))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.error").value(mensaje));
        crear(cuerpo("CLIENTE", "Impostor", " 222222222222 ", "", ""))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value(mensaje));

        int cliente = idDe(crear(cuerpo("CLIENTE", "Andina", "111", "", "")).andExpect(status().isCreated()));
        editar(cliente, cuerpo("CLIENTE", "Andina", "222222222222", "", ""))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value(mensaje));

        // El listado de clientes lo sigue creando el sistema, como siempre.
        listar()
                .andExpect(jsonPath("$[?(@.documentNumber == '222222222222')].name").value(hasItem("Consumidor final")))
                .andExpect(jsonPath("$[?(@.id == %d)].documentNumber".formatted(cliente)).value(hasItem("111")))
                .andExpect(jsonPath("$.length()").value(2));
        crear(cuerpo("PROVEEDOR", "Impostor", "222222222222", "", ""))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value(mensaje));
    }

    @Test
    void elPostRecortaEspaciosYGuardaCorreoYCelularEnBlancoComoNull() throws Exception {
        crear(cuerpo("CLIENTE", "  Andina SAS  ", " 900111 ", "   ", ""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Andina SAS"))
                .andExpect(jsonPath("$.documentNumber").value("900111"))
                .andExpect(jsonPath("$.email").doesNotExist())
                .andExpect(jsonPath("$.phone").doesNotExist());

        // El documento recortado es el mismo tercero: la promocion lo encuentra.
        crear(cuerpo("PROVEEDOR", "Otro", "900111  ", " Ventas@Andina.co ", " 300 "))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("AMBOS"))
                .andExpect(jsonPath("$.email").value("Ventas@Andina.co"))
                .andExpect(jsonPath("$.phone").value("300"));
    }

    @Test
    void elPostUsaLosMismosMensajesYLimitesQueElPut() throws Exception {
        Map<String, String> casos = Map.of(
                "{\"role\":\"CLIENTE\",\"documentType\":\"CC\",\"documentNumber\":\"111\"}",
                "El tercero necesita un nombre o razon social",
                cuerpo("CLIENTE", " ", "111", "", ""), "El tercero necesita un nombre o razon social",
                cuerpo("CLIENTE", "A".repeat(151), "111", "", ""), "El nombre o razon social admite hasta 150 caracteres",
                "{\"role\":\"CLIENTE\",\"name\":\"Andina\",\"documentNumber\":\"111\"}",
                "El tercero necesita un tipo de documento",
                cuerpo("CLIENTE", "Andina", " ", "", ""), "El tercero necesita un numero de documento",
                cuerpo("CLIENTE", "Andina", "1".repeat(26), "", ""), "El numero de documento admite hasta 25 caracteres",
                cuerpo("CLIENTE", "Andina", "111", "a".repeat(151), ""), "El correo admite hasta 150 caracteres",
                cuerpo("CLIENTE", "Andina", "111", "", "3".repeat(26)), "El celular admite hasta 25 caracteres",
                "{\"name\":\"Andina\",\"documentType\":\"CC\",\"documentNumber\":\"111\"}", "El tercero necesita un rol");
        for (Map.Entry<String, String> caso : casos.entrySet()) {
            crear(caso.getKey())
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.status").value(400))
                    .andExpect(jsonPath("$.error").value(caso.getValue()));
        }
        listar().andExpect(jsonPath("$.length()").value(1));

        crear(cuerpo("CLIENTE", "A".repeat(150), "1".repeat(25), "a".repeat(140) + "@correo.co", "3".repeat(25)))
                .andExpect(status().isCreated());
    }

    @Test
    void unCorreoSinFormatoEs400EnElPostYEnElPutYUnoValidoPasa() throws Exception {
        String mensaje = "El correo no tiene un formato valido";
        for (String correo : new String[]{"sinarroba.com", "a@b", "con espacio@correo.co", "@correo.co", "a@@b.co"}) {
            crear(cuerpo("CLIENTE", "Andina", "111", correo, ""))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value(mensaje));
        }
        listar().andExpect(jsonPath("$.length()").value(1));

        int id = idDe(crear(cuerpo("CLIENTE", "Andina", "111", "Contacto@Andina.CO", ""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.email").value("Contacto@Andina.CO")));

        editar(id, cuerpo("CLIENTE", "Andina", "111", "contacto@andina", ""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(mensaje));
        editar(id, cuerpo("CLIENTE", "Andina", "111", "ventas.andina@correo.com.co", ""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("ventas.andina@correo.com.co"));
    }

    @Test
    void laPromocionAAmbosYLosCodigos201200Y409SiguenIgual() throws Exception {
        int id = idDe(crear(cuerpo("PROVEEDOR", "Bodega", "900123456", "bodega@correo.co", ""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.role").value("PROVEEDOR")));

        crear(cuerpo("PROVEEDOR", "Bodega", "900123456", "", ""))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("Ya existe un tercero con el documento 900123456 como PROVEEDOR"));

        crear(cuerpo("CLIENTE", "Otro nombre", "900123456", "otro@correo.co", ""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.role").value("AMBOS"))
                .andExpect(jsonPath("$.name").value("Bodega"))
                .andExpect(jsonPath("$.email").value("bodega@correo.co"));

        crear(cuerpo("CLIENTE", "Bodega", "900123456", "", ""))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("Ya existe un tercero con el documento 900123456 como AMBOS"));
        crear(cuerpo("AMBOS", "Bodega", "900123456", "", ""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("El rol de un tercero nuevo es CLIENTE o PROVEEDOR"));
    }
}
