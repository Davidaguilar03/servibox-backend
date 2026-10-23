package com.servibox.backend.inventory;

import com.jayway.jsonpath.JsonPath;
import com.servibox.backend.auth.entity.Role;
import com.servibox.backend.auth.entity.User;
import com.servibox.backend.auth.repository.UserRepository;
import com.servibox.backend.inventory.entity.Product;
import com.servibox.backend.inventory.entity.ProductCategory;
import com.servibox.backend.inventory.entity.TaxType;
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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Validaciones de categorias y productos, precios a 2 decimales en las respuestas de escritura
 * y rol ADMIN para la configuracion financiera y las categorias.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class InventoryValidationTest {

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

    private String admin;
    private String cajero;
    private ProductCategory llantas;
    private TaxType iva;
    private Product llanta;

    @BeforeEach
    void seed() throws Exception {
        String slug = "taller-inventario-validacion-" + System.nanoTime();
        Tenant tenant = new Tenant();
        tenant.setName(slug);
        tenant.setSlug(slug);
        tenant.setActive(Boolean.TRUE);
        Long tenantId = tenantRepository.save(tenant).getId();

        fixture.usarTenant(tenantId);
        crearUsuario("admin", Role.ADMIN);
        crearUsuario("cajero", Role.CAJERO);
        llantas = fixture.crearCategoriaConIva();
        iva = llantas.getTaxTypes().iterator().next();
        llanta = fixture.crearProducto(llantas, "LLA-001", 100.0, 7);
        TenantContext.clear();

        admin = login(slug, "admin");
        cajero = login(slug, "cajero");
    }

    @AfterEach
    void cleanUp() {
        TenantContext.clear();
    }

    private void crearUsuario(String username, Role rol) {
        User user = new User();
        user.setUsername(username);
        user.setPasswordHash(passwordEncoder.encode(PASSWORD));
        user.setActive(true);
        user.setRole(rol);
        userRepository.save(user);
    }

    private String login(String slug, String username) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"tenantSlug":"%s","username":"%s","password":"%s"}
                                """.formatted(slug, username, PASSWORD)))
                .andExpect(status().isOk())
                .andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(), "$.token");
    }

    private static String categoria(String nombre, int amarillo, int rojo, String margen, String impuestos) {
        return """
                {"name":"%s","color":"#e74c3c","yellowStockMin":%d,"redStockMin":%d,"targetMargin":%s,"taxTypeIds":%s}
                """.formatted(nombre, amarillo, rojo, margen, impuestos);
    }

    private ResultActions enviar(String metodo, String ruta, String token, String cuerpo) throws Exception {
        return mockMvc.perform((metodo.equals("POST") ? post(ruta) : put(ruta))
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(cuerpo));
    }

    private ResultActions leer(String ruta) throws Exception {
        return mockMvc.perform(get(ruta).header("Authorization", "Bearer " + admin)).andExpect(status().isOk());
    }

    private static String producto(String codigo, String descripcion, String costo, String cantidad, Long categoria) {
        return """
                {"code":"%s","description":"%s","purchaseCost":%s,"quantity":%s,"categoryId":%d}
                """.formatted(codigo, descripcion, costo, cantidad, categoria);
    }

    // ---- Categorias ----

    @Test
    void cadaRangoInvalidoDeUnaCategoriaEs400EnElPostYEnElPut() throws Exception {
        Map<String, String> casos = Map.of(
                categoria("ACEITES", -1, 0, "0.1", "[]"), "El umbral amarillo no puede ser negativo",
                categoria("ACEITES", 5, -1, "0.1", "[]"), "El umbral rojo no puede ser negativo",
                categoria("ACEITES", 5, 6, "0.1", "[]"), "El umbral rojo no puede superar al amarillo",
                categoria("ACEITES", 5, 2, "1", "[]"), "El margen debe estar entre 0 y menos de 100%",
                categoria("ACEITES", 5, 2, "1.5", "[]"), "El margen debe estar entre 0 y menos de 100%",
                categoria("ACEITES", 5, 2, "-0.1", "[]"), "El margen debe estar entre 0 y menos de 100%",
                categoria("A".repeat(101), 5, 2, "0.1", "[]"), "El nombre de la categoria admite hasta 100 caracteres");
        for (Map.Entry<String, String> caso : casos.entrySet()) {
            enviar("POST", "/api/inventory/categories", admin, caso.getKey())
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.status").value(400))
                    .andExpect(jsonPath("$.error").value(caso.getValue()));
            enviar("PUT", "/api/inventory/categories/" + llantas.getId(), admin, caso.getKey())
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value(caso.getValue()));
        }

        leer("/api/inventory/categories")
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].name").value("LLANTAS"))
                .andExpect(jsonPath("$[0].yellowStockMin").value(5))
                .andExpect(jsonPath("$[0].redStockMin").value(2))
                .andExpect(jsonPath("$[0].targetMargin").value(0.0));
    }

    @Test
    void losLimitesValidosDeUnaCategoriaSeAceptan() throws Exception {
        enviar("POST", "/api/inventory/categories", admin, categoria("A".repeat(100), 3, 3, "0", "[]"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.redStockMin").value(3));
        enviar("POST", "/api/inventory/categories", admin, categoria("ACEITES", 0, 0, "0.999", "[]"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.targetMargin").value(0.999));

        enviar("PUT", "/api/inventory/categories/" + llantas.getId(), admin, categoria("LLANTAS", 4, 4, "0.999", "[]"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.yellowStockMin").value(4))
                .andExpect(jsonPath("$.redStockMin").value(4));
        enviar("PUT", "/api/inventory/categories/" + llantas.getId(), admin, categoria("LLANTAS", 5, 2, "0", "[]"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.targetMargin").value(0.0));
    }

    @Test
    void elPostDeUnaCategoriaConNombreRepetidoEs409AunqueVengaEnMinusculas() throws Exception {
        enviar("POST", "/api/inventory/categories", admin, categoria("LLANTAS", 5, 2, "0.1", "[]"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.error").value("Ya existe una categoria con el nombre LLANTAS"));
        enviar("POST", "/api/inventory/categories", admin, categoria(" llantas ", 5, 2, "0.1", "[]"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("Ya existe una categoria con el nombre LLANTAS"));

        leer("/api/inventory/categories").andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void elPostDeUnaCategoriaConUnImpuestoAjenoEs400() throws Exception {
        fixture.usarTenant(fixture.crearTenant());
        TaxType ajeno = fixture.crearCategoriaConIva().getTaxTypes().iterator().next();
        TenantContext.clear();

        enviar("POST", "/api/inventory/categories", admin,
                categoria("ACEITES", 5, 2, "0.1", "[%d,%d]".formatted(iva.getId(), ajeno.getId())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Impuesto no encontrado: " + ajeno.getId()));
        leer("/api/inventory/categories").andExpect(jsonPath("$.length()").value(1));

        enviar("POST", "/api/inventory/categories", admin, categoria("ACEITES", 5, 2, "0.1", "[%d]".formatted(iva.getId())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.taxTypes[0].id").value(iva.getId()));
    }

    // ---- Productos ----

    @Test
    void cadaReglaDeUnProductoEs400EnElPostYEnElPut() throws Exception {
        Long cat = llantas.getId();
        Map<String, String> post = Map.of(
                producto("C".repeat(51), "Llanta", "100", "1", cat), "El codigo del producto admite hasta 50 caracteres",
                producto("LLA-002", "D".repeat(256), "100", "1", cat), "La descripcion del producto admite hasta 255 caracteres",
                producto("LLA-002", "Llanta", "-1", "1", cat), "El costo de compra no puede ser negativo",
                producto("LLA-002", "Llanta", "100", "-1", cat), "La cantidad no puede ser negativa");
        for (Map.Entry<String, String> caso : post.entrySet()) {
            enviar("POST", "/api/inventory/products", admin, caso.getKey())
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.status").value(400))
                    .andExpect(jsonPath("$.error").value(caso.getValue()));
        }
        leer("/api/inventory/products").andExpect(jsonPath("$.length()").value(1));

        Map<String, String> put = Map.of(
                producto("C".repeat(51), "Llanta", "100", "1", cat), "El codigo del producto admite hasta 50 caracteres",
                producto("LLA-001", "D".repeat(256), "100", "1", cat), "La descripcion del producto admite hasta 255 caracteres",
                producto("LLA-001", "Llanta", "-1", "1", cat), "El costo de compra no puede ser negativo");
        for (Map.Entry<String, String> caso : put.entrySet()) {
            enviar("PUT", "/api/inventory/products/" + llanta.getId(), admin, caso.getKey())
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value(caso.getValue()));
        }
        leer("/api/inventory/products/" + llanta.getId())
                .andExpect(jsonPath("$.code").value("LLA-001"))
                .andExpect(jsonPath("$.description").value("Producto LLA-001"))
                .andExpect(jsonPath("$.purchaseCost").value(100.0));

        // Los limites exactos, costo y stock en cero, si caben.
        enviar("POST", "/api/inventory/products", admin, producto("C".repeat(50), "D".repeat(255), "0", "0", cat))
                .andExpect(status().isCreated());
        enviar("PUT", "/api/inventory/products/" + llanta.getId(), admin, producto("L".repeat(50), "D".repeat(255), "0", "1", cat))
                .andExpect(status().isOk());
    }

    @Test
    void elPostYElPutDevuelvenLosPreciosConDosDecimalesComoElGet() throws Exception {
        enviar("PUT", "/api/inventory/categories/" + llantas.getId(), admin,
                categoria("LLANTAS", 5, 2, "0.3", "[%d]".formatted(iva.getId())))
                .andExpect(status().isOk());

        // 33333.33 * 0.19 = 6333.3327; 33333.33 / 0.7 = 47619.0428...
        MvcResult creado = enviar("POST", "/api/inventory/products", admin,
                producto("LLA-002", "Llanta", "33333.33", "1", llantas.getId()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.taxAmount").value(6333.33))
                .andExpect(jsonPath("$.suggestedPrice").value(47619.04))
                .andReturn();
        int id = JsonPath.read(creado.getResponse().getContentAsString(), "$.id");
        leer("/api/inventory/products/" + id)
                .andExpect(jsonPath("$.taxAmount").value(6333.33))
                .andExpect(jsonPath("$.suggestedPrice").value(47619.04));

        // 12345.67 * 0.19 = 2345.6773; 12345.67 / 0.7 = 17636.6714...
        enviar("PUT", "/api/inventory/products/" + id, admin, producto("LLA-002", "Llanta", "12345.67", "1", llantas.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.taxAmount").value(2345.68))
                .andExpect(jsonPath("$.suggestedPrice").value(17636.67));
        leer("/api/inventory/products/" + id)
                .andExpect(jsonPath("$.taxAmount").value(2345.68))
                .andExpect(jsonPath("$.suggestedPrice").value(17636.67));
    }

    // ---- Rol ADMIN ----

    @Test
    void unUsuarioSinRolAdminRecibe403SinCambiarNada() throws Exception {
        enviar("PUT", "/api/inventory/financial-settings", cajero,
                "{\"expensesRate\":0.1,\"dianRate\":0.02,\"icaRate\":0.0082}")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.error").value("Solo un administrador puede editar la configuracion financiera"));
        enviar("POST", "/api/inventory/categories", cajero, categoria("ACEITES", 5, 2, "0.1", "[]"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("Solo un administrador puede crear o editar categorias"));
        enviar("PUT", "/api/inventory/categories/" + llantas.getId(), cajero, categoria("ROBADA", 5, 2, "0.1", "[]"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("Solo un administrador puede crear o editar categorias"));

        // El rol se mira antes que el cuerpo: uno invalido tambien es 403.
        enviar("POST", "/api/inventory/categories", cajero, "{}").andExpect(status().isForbidden());
        enviar("PUT", "/api/inventory/financial-settings", cajero, "{\"expensesRate\":5}")
                .andExpect(status().isForbidden());

        leer("/api/inventory/financial-settings").andExpect(jsonPath("$.expensesRate").value(0.0));
        leer("/api/inventory/categories")
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].name").value("LLANTAS"));

        // Leer y el resto de inventario no cambian para el cajero.
        mockMvc.perform(get("/api/inventory/categories").header("Authorization", "Bearer " + cajero))
                .andExpect(status().isOk());
        enviar("POST", "/api/inventory/products", cajero, producto("LLA-002", "Llanta", "100", "1", llantas.getId()))
                .andExpect(status().isCreated());
    }

    @Test
    void unAdminSiguePudiendoYConCuerpoInvalidoRecibeElCuatrocientosDeSiempre() throws Exception {
        enviar("PUT", "/api/inventory/financial-settings", admin,
                "{\"expensesRate\":0.1,\"dianRate\":0.02,\"icaRate\":0.0082}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.expensesRate").value(0.1));
        enviar("POST", "/api/inventory/categories", admin, categoria("ACEITES", 5, 2, "0.1", "[]"))
                .andExpect(status().isCreated());
        enviar("PUT", "/api/inventory/categories/" + llantas.getId(), admin, categoria("RINES", 5, 2, "0.1", "[]"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("RINES"));

        enviar("PUT", "/api/inventory/financial-settings", admin, "{\"expensesRate\":5,\"dianRate\":0,\"icaRate\":0}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Peticion invalida"));
        enviar("POST", "/api/inventory/categories", admin, "{}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Peticion invalida"));
    }
}
