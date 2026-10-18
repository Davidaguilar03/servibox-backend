package com.servibox.backend.counterparties;

import com.jayway.jsonpath.JsonPath;
import com.servibox.backend.auth.entity.Role;
import com.servibox.backend.auth.entity.User;
import com.servibox.backend.auth.repository.UserRepository;
import com.servibox.backend.counterparties.entity.CounterpartyRole;
import com.servibox.backend.counterparties.service.CounterpartyService;
import com.servibox.backend.inventory.entity.Product;
import com.servibox.backend.sales.SalesFixture;
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

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * GET y POST /api/counterparties: alta y listado de terceros por la API, con las reglas de
 * duplicado y promocion a AMBOS, y el Consumidor final en la lista de clientes.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CounterpartiesApiTest {

    private static final String PASSWORD = "clave-correcta";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private SalesFixture fixture;

    @Autowired
    private CounterpartyService counterpartyService;

    @Autowired
    private TenantRepository tenantRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private Long tenantId;
    private String token;
    private Product llanta;

    @BeforeEach
    void seed() throws Exception {
        String slug = "taller-terceros-api-" + System.nanoTime();
        Tenant tenant = new Tenant();
        tenant.setName(slug);
        tenant.setSlug(slug);
        tenant.setActive(Boolean.TRUE);
        tenantId = tenantRepository.save(tenant).getId();

        fixture.usarTenant(tenantId);
        User user = new User();
        user.setUsername("admin");
        user.setPasswordHash(passwordEncoder.encode(PASSWORD));
        user.setActive(true);
        user.setRole(Role.ADMIN);
        userRepository.save(user);
        llanta = fixture.crearProducto(fixture.crearCategoriaConIva(), "LLA-001", 100000.0, 10);
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

    private ResultActions crear(String role, String name, String documentType, String documentNumber)
            throws Exception {
        return mockMvc.perform(post("/api/counterparties")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"role":"%s","name":"%s","documentType":"%s","documentNumber":"%s",
                         "email":"contacto@correo.local","phone":"3001112233"}
                        """.formatted(role, name, documentType, documentNumber)));
    }

    private ResultActions listar(String query) throws Exception {
        return mockMvc.perform(get("/api/counterparties" + query).header("Authorization", "Bearer " + token));
    }

    private int idDe(ResultActions resultado) throws Exception {
        return JsonPath.read(resultado.andReturn().getResponse().getContentAsString(), "$.id");
    }

    @Test
    void crearUnClienteYUnProveedorResponde201ConElTercero() throws Exception {
        crear("CLIENTE", "Andina", "CC", "1010101010")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.role").value("CLIENTE"))
                .andExpect(jsonPath("$.name").value("Andina"))
                .andExpect(jsonPath("$.documentType").value("CC"))
                .andExpect(jsonPath("$.documentNumber").value("1010101010"))
                .andExpect(jsonPath("$.email").value("contacto@correo.local"))
                .andExpect(jsonPath("$.phone").value("3001112233"))
                .andExpect(jsonPath("$.tenantId").doesNotExist());

        crear("PROVEEDOR", "Llantas del Norte", "NIT", "900123456")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.role").value("PROVEEDOR"))
                .andExpect(jsonPath("$.documentType").value("NIT"));
    }

    @Test
    void listarPorRolIncluyeLosAmbosYOrdenaPorNombre() throws Exception {
        crear("CLIENTE", "Carlos", "CC", "111").andExpect(status().isCreated());
        crear("PROVEEDOR", "Bodega", "NIT", "222").andExpect(status().isCreated());
        crear("CLIENTE", "Andina", "NIT", "333").andExpect(status().isCreated());
        crear("PROVEEDOR", "Andina", "NIT", "333").andExpect(status().isOk());

        listar("?role=CLIENTE")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].name").value(contains("Andina", "Carlos", "Consumidor final")))
                .andExpect(jsonPath("$[0].role").value("AMBOS"));

        listar("?role=PROVEEDOR")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].name").value(contains("Andina", "Bodega")));
    }

    @Test
    void listarSinParametroDevuelveTodos() throws Exception {
        crear("PROVEEDOR", "Bodega", "NIT", "222").andExpect(status().isCreated());
        crear("CLIENTE", "Andina", "CC", "111").andExpect(status().isCreated());

        listar("")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].name").value(contains("Andina", "Bodega", "Consumidor final")));
    }

    @Test
    void elConsumidorFinalApareceEnLaListaDeClientes() throws Exception {
        listar("?role=CLIENTE")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].name").value("Consumidor final"))
                .andExpect(jsonPath("$[0].role").value("CLIENTE"))
                .andExpect(jsonPath("$[0].documentType").value("NIT"))
                .andExpect(jsonPath("$[0].documentNumber").value("222222222222"));

        // Listar otra vez no lo duplica, y no es proveedor.
        listar("?role=CLIENTE").andExpect(jsonPath("$.length()").value(1));
        listar("?role=PROVEEDOR").andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void unDocumentoRepetidoConElMismoRolOConAmbosEs409() throws Exception {
        crear("CLIENTE", "Andina", "NIT", "900123456").andExpect(status().isCreated());

        crear("CLIENTE", "Otro", "NIT", "900123456")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.error").value("Ya existe un tercero con el documento 900123456 como CLIENTE"));

        crear("PROVEEDOR", "Andina", "NIT", "900123456").andExpect(status().isOk());
        crear("PROVEEDOR", "Andina", "NIT", "900123456")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("Ya existe un tercero con el documento 900123456 como AMBOS"));
        crear("CLIENTE", "Andina", "NIT", "900123456").andExpect(status().isConflict());
    }

    @Test
    void crearConElOtroRolPromueveAAmbosYResponde200SinDuplicar() throws Exception {
        int id = idDe(crear("PROVEEDOR", "Andina", "NIT", "900123456").andExpect(status().isCreated()));

        crear("CLIENTE", "Otro nombre", "CC", "900123456")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.role").value("AMBOS"))
                .andExpect(jsonPath("$.name").value("Andina"))
                .andExpect(jsonPath("$.documentType").value("NIT"));

        fixture.usarTenant(tenantId);
        assertThat(counterpartyService.findAll())
                .filteredOn(t -> t.getDocumentNumber().equals("900123456"))
                .singleElement()
                .satisfies(t -> assertThat(t.getRole()).isEqualTo(CounterpartyRole.AMBOS));
    }

    @Test
    void losCamposObligatoriosFaltantesSon400() throws Exception {
        List<String> cuerpos = List.of(
                "{\"role\":\"CLIENTE\",\"documentType\":\"CC\",\"documentNumber\":\"111\"}",
                "{\"role\":\"CLIENTE\",\"name\":\" \",\"documentType\":\"CC\",\"documentNumber\":\"111\"}",
                "{\"role\":\"CLIENTE\",\"name\":\"Andina\",\"documentType\":\"CC\"}",
                "{\"role\":\"CLIENTE\",\"name\":\"Andina\",\"documentNumber\":\"111\"}",
                "{\"name\":\"Andina\",\"documentType\":\"CC\",\"documentNumber\":\"111\"}",
                "{\"role\":\"CLIENTE\",\"name\":\"Andina\",\"documentType\":\"CC\",\"documentNumber\":\""
                        + "1".repeat(26) + "\"}");
        for (String cuerpo : cuerpos) {
            mockMvc.perform(post("/api/counterparties")
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(cuerpo))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.status").value(400));
        }

        crear("AMBOS", "Andina", "CC", "111")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("El rol de un tercero nuevo es CLIENTE o PROVEEDOR"));

        fixture.usarTenant(tenantId);
        assertThat(counterpartyService.findAll()).isEmpty();
    }

    @Test
    void unRoleInvalidoEnElListadoEs400() throws Exception {
        listar("?role=X")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error")
                        .value("Valor invalido 'X' en el parametro role. Valores permitidos: CLIENTE, PROVEEDOR, AMBOS"));

        listar("?role=AMBOS")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("El filtro role es CLIENTE o PROVEEDOR"));
    }

    @Test
    void sinTokenEs401() throws Exception {
        mockMvc.perform(get("/api/counterparties")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/counterparties")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"CLIENTE\",\"name\":\"Andina\",\"documentType\":\"CC\",\"documentNumber\":\"111\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void unTerceroDeOtroTenantNoApareceEnLaLista() throws Exception {
        fixture.usarTenant(fixture.crearTenant());
        fixture.crearCliente("Ajeno", "800000001");
        fixture.crearProveedor("Ajeno proveedor", "800000002");
        TenantContext.clear();

        listar("")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].name").value(not(hasItem("Ajeno"))))
                .andExpect(jsonPath("$[*].name").value(not(hasItem("Ajeno proveedor"))));

        // El mismo documento en este tenant es un tercero nuevo, no un duplicado.
        crear("CLIENTE", "Propio", "CC", "800000001").andExpect(status().isCreated());
    }

    @Test
    void unTerceroCreadoPorLaApiSirveEnUnaVentaYEnUnaCompra() throws Exception {
        int cliente = idDe(crear("CLIENTE", "Andina", "CC", "111").andExpect(status().isCreated()));
        int proveedor = idDe(crear("PROVEEDOR", "Bodega", "NIT", "222").andExpect(status().isCreated()));

        mockMvc.perform(post("/api/sales")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"invoiceNumber":"VEN-00001","customerId":%d,"paymentType":"CREDITO",
                                 "details":[{"productId":%d,"quantity":1,"price":100000}]}
                                """.formatted(cliente, llanta.getId())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.customer.id").value(cliente));

        mockMvc.perform(post("/api/purchases")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"invoiceNumber":"FAC-00001","supplierId":%d,"paymentType":"CREDITO",
                                 "details":[{"productId":%d,"quantity":1,"price":100000}]}
                                """.formatted(proveedor, llanta.getId())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.supplier.id").value(proveedor));
    }
}
