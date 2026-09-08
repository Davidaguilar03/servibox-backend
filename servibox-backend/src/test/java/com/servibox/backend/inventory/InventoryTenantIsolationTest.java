package com.servibox.backend.inventory;

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

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Aislamiento del modulo inventory de punta a punta: dos tenants reales, cada uno con su
 * JWT, hablando por HTTP. No toca TenantContext a mano salvo para sembrar.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class InventoryTenantIsolationTest {

    private static final String PASSWORD = "clave-correcta";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TenantRepository tenantRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private String slugUno;
    private String slugDos;
    private String tokenUno;
    private String tokenDos;

    @BeforeEach
    void seed() throws Exception {
        slugUno = "taller-uno-" + System.nanoTime();
        slugDos = "taller-dos-" + System.nanoTime();
        crearTenantConUsuario(slugUno, "admin-uno");
        crearTenantConUsuario(slugDos, "admin-dos");
        tokenUno = login(slugUno, "admin-uno");
        tokenDos = login(slugDos, "admin-dos");
    }

    @AfterEach
    void cleanUp() {
        TenantContext.clear();
    }

    private void crearTenantConUsuario(String slug, String username) {
        Tenant tenant = new Tenant();
        tenant.setName(slug);
        tenant.setSlug(slug);
        tenant.setActive(Boolean.TRUE);
        Long tenantId = tenantRepository.save(tenant).getId();

        TenantContext.setTenantId(tenantId);
        try {
            User user = new User();
            user.setUsername(username);
            user.setPasswordHash(passwordEncoder.encode(PASSWORD));
            user.setActive(true);
            user.setRole(Role.ADMIN);
            userRepository.save(user);
        } finally {
            TenantContext.clear();
        }
    }

    private String login(String slug, String username) throws Exception {
        String body = """
                {"tenantSlug":"%s","username":"%s","password":"%s"}
                """.formatted(slug, username, PASSWORD);
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(), "$.token");
    }

    private Long crearCategoria(String token, String nombre) throws Exception {
        String body = """
                {"name":"%s","color":"#e74c3c","targetMargin":0.5}
                """.formatted(nombre);
        MvcResult result = mockMvc.perform(post("/api/inventory/categories")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn();
        Integer id = JsonPath.read(result.getResponse().getContentAsString(), "$.id");
        return id.longValue();
    }

    private void crearProducto(String token, Long categoriaId, String codigo) throws Exception {
        String body = """
                {"code":"%s","description":"Producto","purchaseCost":25000,"quantity":5,"categoryId":%d}
                """.formatted(codigo, categoriaId);
        mockMvc.perform(post("/api/inventory/products")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated());
    }

    private Long crearProductoYDevolverId(String token, Long categoriaId, String codigo) throws Exception {
        String body = """
                {"code":"%s","description":"Producto","purchaseCost":25000,"quantity":5,"categoryId":%d}
                """.formatted(codigo, categoriaId);
        MvcResult result = mockMvc.perform(post("/api/inventory/products")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn();
        Integer id = JsonPath.read(result.getResponse().getContentAsString(), "$.id");
        return id.longValue();
    }

    private List<String> codigosVisiblesPara(String token) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/inventory/products")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(), "$[*].code");
    }

    @Test
    void unProductoDeUnTenantNoEsVisibleParaOtro() throws Exception {
        Long categoriaUno = crearCategoria(tokenUno, "LLANTAS");
        crearProducto(tokenUno, categoriaUno, "SOLO-TENANT-UNO");

        Long categoriaDos = crearCategoria(tokenDos, "ACEITES");
        crearProducto(tokenDos, categoriaDos, "SOLO-TENANT-DOS");

        assertThat(codigosVisiblesPara(tokenUno)).containsExactly("SOLO-TENANT-UNO");
        assertThat(codigosVisiblesPara(tokenDos)).containsExactly("SOLO-TENANT-DOS");
    }

    @Test
    void lasCategoriasTampocoSeCruzanEntreTenants() throws Exception {
        crearCategoria(tokenUno, "LLANTAS");
        crearCategoria(tokenDos, "ACEITES");

        MvcResult result = mockMvc.perform(get("/api/inventory/categories")
                        .header("Authorization", "Bearer " + tokenUno))
                .andExpect(status().isOk())
                .andReturn();
        List<String> nombres = JsonPath.read(result.getResponse().getContentAsString(), "$[*].name");

        assertThat(nombres).containsExactly("LLANTAS");
    }

    /**
     * El hueco que esto cubre: findProductById usaba el findById heredado de
     * JpaRepository, y el @Filter de Hibernate no se aplica a EntityManager.find(). El
     * GET por id devolvia 200 con el producto de otro tenant. Ver 03-DECISIONS.md.
     */
    @Test
    void unProductoDeOtroTenantNoSeDevuelvePorId() throws Exception {
        Long categoriaUno = crearCategoria(tokenUno, "LLANTAS");
        Long productoDeUno = crearProductoYDevolverId(tokenUno, categoriaUno, "SOLO-TENANT-UNO");

        mockMvc.perform(get("/api/inventory/products/" + productoDeUno)
                        .header("Authorization", "Bearer " + tokenDos))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").doesNotExist());

        // Su dueno si lo ve, para que el 404 no sea por un id inexistente.
        mockMvc.perform(get("/api/inventory/products/" + productoDeUno)
                        .header("Authorization", "Bearer " + tokenUno))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SOLO-TENANT-UNO"));
    }

    @Test
    void unaCategoriaDeOtroTenantNoSirveParaCrearleProductos() throws Exception {
        Long categoriaDeUno = crearCategoria(tokenUno, "LLANTAS");

        // findCategoryById tenia el mismo hueco: sin el fix, el producto se creaba en el
        // tenant dos colgando de una categoria del tenant uno.
        mockMvc.perform(post("/api/inventory/products")
                        .header("Authorization", "Bearer " + tokenDos)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code":"ROBADO","description":"Producto","purchaseCost":25000,"quantity":5,"categoryId":%d}
                                """.formatted(categoriaDeUno)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void elInventarioExigeAutenticacion() throws Exception {
        mockMvc.perform(get("/api/inventory/products")).andExpect(status().isUnauthorized());
    }
}
