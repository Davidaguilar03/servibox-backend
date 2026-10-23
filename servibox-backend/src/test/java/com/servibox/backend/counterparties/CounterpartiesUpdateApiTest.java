package com.servibox.backend.counterparties;

import com.jayway.jsonpath.JsonPath;
import com.servibox.backend.auth.entity.Role;
import com.servibox.backend.auth.entity.User;
import com.servibox.backend.auth.repository.UserRepository;
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

import java.util.Map;

import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * PUT /api/counterparties/{id}: edicion de los datos de un tercero, sin tocar su rol, con las
 * reglas de documento repetido, el Consumidor final protegido y el aislamiento por tenant.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CounterpartiesUpdateApiTest {

    private static final String PASSWORD = "clave-correcta";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private SalesFixture fixture;

    @Autowired
    private TenantRepository tenantRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private String token;
    private Product llanta;

    @BeforeEach
    void seed() throws Exception {
        token = tenantConAdmin("taller-terceros-put-");
    }

    @AfterEach
    void cleanUp() {
        TenantContext.clear();
    }

    /** Crea un tenant con su admin y un producto, y devuelve el token de su login. */
    private String tenantConAdmin(String prefijo) throws Exception {
        String slug = prefijo + System.nanoTime();
        Tenant tenant = new Tenant();
        tenant.setName(slug);
        tenant.setSlug(slug);
        tenant.setActive(Boolean.TRUE);
        Long id = tenantRepository.save(tenant).getId();

        fixture.usarTenant(id);
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
        return JsonPath.read(result.getResponse().getContentAsString(), "$.token");
    }

    private int crear(String role, String name, String documentType, String documentNumber) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/counterparties")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"role":"%s","name":"%s","documentType":"%s","documentNumber":"%s",
                                 "email":"contacto@correo.local","phone":"3001112233"}
                                """.formatted(role, name, documentType, documentNumber)))
                .andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(), "$.id");
    }

    private ResultActions editar(Object id, String cuerpo) throws Exception {
        return mockMvc.perform(put("/api/counterparties/" + id)
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(cuerpo));
    }

    private static String cuerpo(String name, String documentType, String documentNumber, String email,
                                 String phone) {
        return """
                {"name":"%s","documentType":"%s","documentNumber":"%s","email":"%s","phone":"%s"}
                """.formatted(name, documentType, documentNumber, email, phone);
    }

    /** El tercero tal como sale en el GET del listado. */
    private ResultActions listado() throws Exception {
        return mockMvc.perform(get("/api/counterparties").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
    }

    private int idDe(ResultActions resultado) throws Exception {
        return JsonPath.read(resultado.andReturn().getResponse().getContentAsString(), "$.id");
    }

    private int vender(int clienteId) throws Exception {
        return idDe(mockMvc.perform(post("/api/sales")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"customerId":%d,"paymentType":"CREDITO",
                                 "details":[{"productId":%d,"quantity":1,"price":100000}]}
                                """.formatted(clienteId, llanta.getId())))
                .andExpect(status().isCreated()));
    }

    private int comprar(String numero, int proveedorId) throws Exception {
        return idDe(mockMvc.perform(post("/api/purchases")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"invoiceNumber":"%s","supplierId":%d,"paymentType":"CREDITO",
                                 "details":[{"productId":%d,"quantity":1,"price":100000}]}
                                """.formatted(numero, proveedorId, llanta.getId())))
                .andExpect(status().isCreated()));
    }

    @Test
    void editarUnClienteYUnProveedorResponde200YElGetLoRefleja() throws Exception {
        int cliente = crear("CLIENTE", "Andina", "CC", "111");
        int proveedor = crear("PROVEEDOR", "Bodega", "NIT", "222");

        editar(cliente, cuerpo("Andina SAS", "NIT", "900111", "ventas@andina.local", "3109998877"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(cliente))
                .andExpect(jsonPath("$.role").value("CLIENTE"))
                .andExpect(jsonPath("$.name").value("Andina SAS"))
                .andExpect(jsonPath("$.documentType").value("NIT"))
                .andExpect(jsonPath("$.documentNumber").value("900111"))
                .andExpect(jsonPath("$.email").value("ventas@andina.local"))
                .andExpect(jsonPath("$.phone").value("3109998877"))
                .andExpect(jsonPath("$.tenantId").doesNotExist());
        editar(proveedor, cuerpo("Bodega Central", "CE", "CE-222", "bodega@correo.local", "6015550000"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("PROVEEDOR"))
                .andExpect(jsonPath("$.name").value("Bodega Central"));

        listado()
                .andExpect(jsonPath("$[?(@.id == %d)].name".formatted(cliente)).value(hasItem("Andina SAS")))
                .andExpect(jsonPath("$[?(@.id == %d)].documentType".formatted(cliente)).value(hasItem("NIT")))
                .andExpect(jsonPath("$[?(@.id == %d)].documentNumber".formatted(cliente)).value(hasItem("900111")))
                .andExpect(jsonPath("$[?(@.id == %d)].email".formatted(cliente))
                        .value(hasItem("ventas@andina.local")))
                .andExpect(jsonPath("$[?(@.id == %d)].phone".formatted(cliente)).value(hasItem("3109998877")))
                .andExpect(jsonPath("$[?(@.id == %d)].documentType".formatted(proveedor)).value(hasItem("CE")))
                .andExpect(jsonPath("$[?(@.id == %d)].documentNumber".formatted(proveedor)).value(hasItem("CE-222")));
    }

    @Test
    void losTextosSeRecortanYCorreoYCelularEnBlancoQuedanEnNull() throws Exception {
        int cliente = crear("CLIENTE", "Andina", "CC", "111");

        editar(cliente, cuerpo("  Andina SAS  ", "CC", " 111 ", "   ", ""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Andina SAS"))
                .andExpect(jsonPath("$.documentNumber").value("111"))
                .andExpect(jsonPath("$.email").doesNotExist())
                .andExpect(jsonPath("$.phone").doesNotExist());

        editar(cliente, "{\"name\":\"Andina SAS\",\"documentType\":\"CC\",\"documentNumber\":\"111\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").doesNotExist());
    }

    @Test
    void elRolNoCambiaAunqueLlegueEnElCuerpo() throws Exception {
        int cliente = crear("CLIENTE", "Andina", "CC", "111");
        int ambos = crear("PROVEEDOR", "Bodega", "NIT", "222");
        crear("CLIENTE", "Bodega", "NIT", "222");

        editar(cliente, """
                {"role":"PROVEEDOR","name":"Andina","documentType":"CC","documentNumber":"111"}
                """)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("CLIENTE"));
        editar(ambos, """
                {"role":"CLIENTE","name":"Bodega SAS","documentType":"NIT","documentNumber":"222"}
                """)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("AMBOS"))
                .andExpect(jsonPath("$.name").value("Bodega SAS"));

        listado().andExpect(jsonPath("$[?(@.id == %d)].role".formatted(ambos)).value(hasItem("AMBOS")));
    }

    @Test
    void dejarElPropioDocumentoNoEsConflictoYElDeOtroTerceroEs409() throws Exception {
        int andina = crear("CLIENTE", "Andina", "CC", "111");
        crear("PROVEEDOR", "Bodega", "NIT", "222");

        editar(andina, cuerpo("Andina 2", "CC", "111", "", "")).andExpect(status().isOk());

        editar(andina, cuerpo("Andina", "CC", "222", "", ""))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.error").value("Ya existe un tercero con el documento 222"));

        listado()
                .andExpect(jsonPath("$[?(@.id == %d)].documentNumber".formatted(andina)).value(hasItem("111")))
                .andExpect(jsonPath("$[?(@.id == %d)].name".formatted(andina)).value(hasItem("Andina 2")));
    }

    @Test
    void cadaReglaDeValidacionEs400ConSuMensaje() throws Exception {
        int cliente = crear("CLIENTE", "Andina", "CC", "111");
        Map<String, String> casos = Map.of(
                cuerpo(" ", "CC", "111", "", ""), "El tercero necesita un nombre o razon social",
                "{\"documentType\":\"CC\",\"documentNumber\":\"111\"}", "El tercero necesita un nombre o razon social",
                cuerpo("A".repeat(151), "CC", "111", "", ""), "El nombre o razon social admite hasta 150 caracteres",
                cuerpo("Andina", "CC", " ", "", ""), "El tercero necesita un numero de documento",
                cuerpo("Andina", "CC", "1".repeat(26), "", ""), "El numero de documento admite hasta 25 caracteres",
                cuerpo("Andina", "CC", "111", "a".repeat(151), ""), "El correo admite hasta 150 caracteres",
                cuerpo("Andina", "CC", "111", "", "3".repeat(26)), "El celular admite hasta 25 caracteres",
                "{\"name\":\"Andina\",\"documentNumber\":\"111\"}", "El tercero necesita un tipo de documento",
                cuerpo("Andina", "DNI", "111", "", ""),
                "Valor invalido 'DNI' en el campo documentType. Valores permitidos: CC, NIT, CE, RUT, PASAPORTE");
        for (Map.Entry<String, String> caso : casos.entrySet()) {
            editar(cliente, caso.getKey())
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.status").value(400))
                    .andExpect(jsonPath("$.error").value(caso.getValue()));
        }

        // Los limites exactos si caben.
        editar(cliente, cuerpo("A".repeat(150), "CC", "1".repeat(25), "a".repeat(150), "3".repeat(25)))
                .andExpect(status().isOk());

        listado().andExpect(jsonPath("$[?(@.id == %d)].name".formatted(cliente))
                .value(hasItem("A".repeat(150))));
    }

    @Test
    void elConsumidorFinalNoSePuedeEditar() throws Exception {
        MvcResult lista = mockMvc.perform(get("/api/counterparties?role=CLIENTE")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        int consumidor = JsonPath.read(lista.getResponse().getContentAsString(), "$[0].id");

        editar(consumidor, cuerpo("Otro nombre", "CC", "123", "x@correo.local", "300"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.error").value("El tercero Consumidor final es del sistema y no se puede editar"));

        listado()
                .andExpect(jsonPath("$[?(@.id == %d)].name".formatted(consumidor)).value(hasItem("Consumidor final")))
                .andExpect(jsonPath("$[?(@.id == %d)].documentNumber".formatted(consumidor))
                        .value(hasItem("222222222222")));
    }

    @Test
    void unTenantNoPuedeEditarLosTercerosDelOtro() throws Exception {
        int propio = crear("CLIENTE", "Andina", "CC", "111");
        String tokenPropio = token;

        token = tenantConAdmin("taller-terceros-put-ajeno-");
        editar(propio, cuerpo("Robado", "CC", "999", "", ""))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.error").value("Tercero no encontrado: " + propio));

        token = tokenPropio;
        listado()
                .andExpect(jsonPath("$[?(@.id == %d)].name".formatted(propio)).value(hasItem("Andina")))
                .andExpect(jsonPath("$[?(@.id == %d)].documentNumber".formatted(propio)).value(hasItem("111")));
    }

    @Test
    void unIdInexistenteEs404YUnoNoNumericoEs400() throws Exception {
        editar(999999999, cuerpo("Andina", "CC", "111", "", ""))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("Tercero no encontrado: 999999999"));

        editar("abc", cuerpo("Andina", "CC", "111", "", ""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("Valor invalido 'abc' en el parametro id"));
    }

    @Test
    void sinTokenEs401() throws Exception {
        int cliente = crear("CLIENTE", "Andina", "CC", "111");

        mockMvc.perform(put("/api/counterparties/" + cliente)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(cuerpo("Otro", "CC", "111", "", "")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void lasFacturasYaEmitidasMuestranElNombreNuevoYElTerceroSigueSirviendo() throws Exception {
        int cliente = crear("CLIENTE", "Andina", "CC", "111");
        int proveedor = crear("PROVEEDOR", "Bodega", "NIT", "222");
        int venta = vender(cliente);
        int compra = comprar("FAC-00001", proveedor);

        editar(cliente, cuerpo("Andina SAS", "NIT", "900111", "", "")).andExpect(status().isOk());
        editar(proveedor, cuerpo("Bodega Central", "NIT", "900222", "", "")).andExpect(status().isOk());

        // Las facturas guardan solo id_tercero: muestran los datos actuales del tercero.
        mockMvc.perform(get("/api/sales/" + venta).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.customer.id").value(cliente))
                .andExpect(jsonPath("$.customer.name").value("Andina SAS"))
                .andExpect(jsonPath("$.customer.documentNumber").value("900111"));
        mockMvc.perform(get("/api/purchases/" + compra).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.supplier.id").value(proveedor))
                .andExpect(jsonPath("$.supplier.name").value("Bodega Central"));

        int ventaNueva = vender(cliente);
        int compraNueva = comprar("FAC-00002", proveedor);
        mockMvc.perform(get("/api/sales/" + ventaNueva).header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.customer.name").value("Andina SAS"));
        mockMvc.perform(get("/api/purchases/" + compraNueva).header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.supplier.name").value("Bodega Central"));
    }
}
