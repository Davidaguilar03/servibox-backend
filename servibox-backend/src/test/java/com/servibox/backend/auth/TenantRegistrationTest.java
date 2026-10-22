package com.servibox.backend.auth;

import com.jayway.jsonpath.JsonPath;
import com.servibox.backend.auth.entity.User;
import com.servibox.backend.auth.repository.UserRepository;
import com.servibox.backend.counterparties.repository.CounterpartyRepository;
import com.servibox.backend.inventory.repository.ProductCategoryRepository;
import com.servibox.backend.inventory.repository.TaxTypeRepository;
import com.servibox.backend.tenant.Tenant;
import com.servibox.backend.tenant.TenantContext;
import com.servibox.backend.tenant.TenantRepository;
import com.servibox.backend.treasury.repository.AccountRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * POST /api/auth/register. Corre con test y dev para tener el tenant demo de la siembra
 * (usuario admin / admin123 con datos de demostracion) contra el que probar el aislamiento.
 * La propiedad explicita da un contexto propio: con el de DevDemoDataTest compartiria la
 * base, y esa prueba cuenta los tenants.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles({"test", "dev"})
@TestPropertySource(properties = "servibox.registration.enabled=true")
class TenantRegistrationTest {

    private static final String PASSWORD = "clave-segura-1";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TenantRepository tenantRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ProductCategoryRepository categoryRepository;

    @Autowired
    private TaxTypeRepository taxTypeRepository;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private CounterpartyRepository counterpartyRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @AfterEach
    void cleanUp() {
        TenantContext.clear();
    }

    // ---- Ayudas ----

    private static String cuerpo(String slug, String username) {
        return """
                {"businessName":"Serviteca %s","nit":"900123456-7","phone":"+57 (300) 123-4567",
                 "slug":"%s","adminFullName":"Ana Maria Rojas","adminEmail":"ana@%s.co",
                 "adminUsername":"%s","password":"%s","acceptTerms":true}
                """.formatted(slug, slug, slug, username, PASSWORD);
    }

    /** El cuerpo valido con un campo reemplazado por el JSON crudo que se pase. */
    private static String cuerpoCon(String campo, String valorJson) {
        String base = cuerpo("taller-errores", "ana.rojas");
        return base.replaceFirst("\"" + campo + "\":(\"[^\"]*\"|true)", "\"" + campo + "\":" + valorJson);
    }

    private MvcResult registrar(String body) throws Exception {
        return mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn();
    }

    private String registrarYObtenerToken(String slug, String username) throws Exception {
        MvcResult result = registrar(cuerpo(slug, username));
        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        return JsonPath.read(result.getResponse().getContentAsString(), "$.token");
    }

    private String login(String slug, String username, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"tenantSlug":"%s","username":"%s","password":"%s"}
                                """.formatted(slug, username, password)))
                .andExpect(status().isOk())
                .andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(), "$.token");
    }

    private String getJson(String ruta, String token) throws Exception {
        return mockMvc.perform(get(ruta).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    private long postId(String ruta, String token, String body) throws Exception {
        MvcResult result = mockMvc.perform(post(ruta)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn();
        assertThat(result.getResponse().getStatus())
                .as(ruta + " -> " + result.getResponse().getContentAsString()).isIn(200, 201);
        return ((Number) JsonPath.read(result.getResponse().getContentAsString(), "$.id")).longValue();
    }

    /** Filas de todas las tablas que toca el registro, sumando todos los tenants. */
    private List<Long> conteos() {
        return List.of(tenantRepository.count(), userRepository.count(), categoryRepository.count(),
                taxTypeRepository.count(), accountRepository.count(), counterpartyRepository.count());
    }

    private void esperarError(String body, int status, String mensaje) throws Exception {
        List<Long> antes = conteos();
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().is(status))
                .andExpect(jsonPath("$.error").value(mensaje))
                .andExpect(jsonPath("$.status").value(status))
                .andExpect(jsonPath("$.token").doesNotExist());
        assertThat(conteos()).isEqualTo(antes);
    }

    // ---- Registro correcto ----

    @Test
    void registroCorrectoDevuelveSesionYAprovisionaElTenant() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(cuerpo("taller-nuevo", "ana.rojas")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.token").isNotEmpty())
                .andExpect(jsonPath("$.user.username").value("ana.rojas"))
                .andExpect(jsonPath("$.user.role").value("ADMIN"))
                .andExpect(jsonPath("$.user.fullName").value("Ana Maria Rojas"))
                .andExpect(jsonPath("$.user.passwordHash").doesNotExist())
                .andExpect(jsonPath("$.tenant.name").value("Serviteca taller-nuevo"))
                .andExpect(jsonPath("$.tenant.slug").value("taller-nuevo"))
                .andExpect(jsonPath("$.tenant.id").doesNotExist())
                .andReturn();
        String respuesta = result.getResponse().getContentAsString();
        String token = JsonPath.read(respuesta, "$.token");

        Tenant tenant = tenantRepository.findBySlug("taller-nuevo").orElseThrow();
        assertThat(tenant.getActive()).isTrue();
        assertThat(tenant.getNit()).isEqualTo("900123456-7");
        assertThat(tenant.getPhone()).isEqualTo("+57 (300) 123-4567");

        User admin = userRepository.findByUsernameAndTenantId("ana.rojas", tenant.getId()).orElseThrow();
        assertThat(admin.getEmail()).isEqualTo("ana@taller-nuevo.co");
        assertThat(admin.getPasswordHash()).isNotEqualTo(PASSWORD);
        assertThat(passwordEncoder.matches(PASSWORD, admin.getPasswordHash())).isTrue();
        assertThat(respuesta).doesNotContain(PASSWORD).doesNotContain(admin.getPasswordHash());

        // Categorias base: exactamente tres, con el IVA, umbrales 10 y 3 y margen 0.
        String categorias = getJson("/api/inventory/categories", token);
        assertThat(JsonPath.<List<String>>read(categorias, "$[*].name"))
                .containsExactlyInAnyOrder("LLANTAS", "LUBRICANTES", "BATERIAS");
        assertThat(JsonPath.<List<Integer>>read(categorias, "$[*].yellowStockMin")).containsOnly(10);
        assertThat(JsonPath.<List<Integer>>read(categorias, "$[*].redStockMin")).containsOnly(3);
        assertThat(JsonPath.<List<Double>>read(categorias, "$[*].targetMargin")).containsOnly(0.0);
        assertThat(JsonPath.<List<String>>read(categorias, "$[*].taxTypes[*].name")).containsOnly("IVA");
        assertThat(JsonPath.<List<Double>>read(categorias, "$[*].taxTypes[*].rate")).hasSize(3).containsOnly(0.19);

        String impuestos = getJson("/api/inventory/tax-types", token);
        assertThat(JsonPath.<List<String>>read(impuestos, "$[*].name")).containsExactly("IVA");
        assertThat(JsonPath.<Double>read(impuestos, "$[0].rate")).isEqualTo(0.19);
        assertThat(JsonPath.<Boolean>read(impuestos, "$[0].isVat")).isTrue();

        String cuentas = getJson("/api/treasury/accounts", token);
        assertThat(JsonPath.<List<String>>read(cuentas, "$[*].name")).containsExactly("Caja General");
        assertThat(JsonPath.<String>read(cuentas, "$[0].type")).isEqualTo("EFECTIVO");
        assertThat(JsonPath.<Double>read(cuentas, "$[0].initialBalance")).isEqualTo(0.0);
        assertThat(JsonPath.<Double>read(cuentas, "$[0].currentBalance")).isEqualTo(0.0);

        // El Consumidor final ya existe y un segundo listado no lo duplica.
        assertThat(counterpartyRepository.findByTenantIdOrderByNameAsc(tenant.getId())).hasSize(1);
        for (int i = 0; i < 2; i++) {
            String clientes = getJson("/api/counterparties?role=CLIENTE", token);
            assertThat(JsonPath.<List<String>>read(clientes, "$[*].name")).containsExactly("Consumidor final");
        }
        assertThat(counterpartyRepository.findByTenantIdOrderByNameAsc(tenant.getId())).hasSize(1);

        mockMvc.perform(get("/api/inventory/financial-settings").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.expensesRate").value(0.0))
                .andExpect(jsonPath("$.dianRate").value(0.0))
                .andExpect(jsonPath("$.icaRate").value(0.0))
                .andExpect(jsonPath("$.cardCommissionRate").value(0.0));
    }

    @Test
    void elLoginConLasCredencialesNuevasFuncionaYDevuelveFullName() throws Exception {
        registrarYObtenerToken("taller-login", "jefe_taller");

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"tenantSlug":"taller-login","username":"jefe_taller","password":"%s"}
                                """.formatted(PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isNotEmpty())
                .andExpect(jsonPath("$.user.username").value("jefe_taller"))
                .andExpect(jsonPath("$.user.role").value("ADMIN"))
                .andExpect(jsonPath("$.user.fullName").value("Ana Maria Rojas"))
                .andExpect(jsonPath("$.tenant.slug").value("taller-login"));
    }

    /** Usuarios anteriores al registro (aqui, el admin de la siembra dev): fullName null. */
    @Test
    void elLoginDeUnUsuarioExistenteDevuelveFullNameNull() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"tenantSlug":"demo","username":"admin","password":"admin123"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.username").value("admin"))
                .andExpect(jsonPath("$.user.fullName").value(nullValue()));
    }

    @Test
    void conElTenantNuevoSePuedeComprarYVender() throws Exception {
        String token = registrarYObtenerToken("taller-flujo", "admin");

        String categorias = getJson("/api/inventory/categories", token);
        long llantas = ((Number) JsonPath.<List<Object>>read(categorias, "$[?(@.name == 'LLANTAS')].id").get(0))
                .longValue();
        long caja = ((Number) JsonPath.read(getJson("/api/treasury/accounts", token), "$[0].id")).longValue();

        long producto = postId("/api/inventory/products", token, """
                {"code":"LL-001","description":"Llanta 175/70 R13","purchaseCost":100000,"quantity":0,
                 "categoryId":%d}
                """.formatted(llantas));
        postId("/api/treasury/movements", token, """
                {"accountId":%d,"category":"INGRESO_OCASIONAL","concept":"Aporte inicial","amount":1000000}
                """.formatted(caja));
        long proveedor = postId("/api/counterparties", token, """
                {"role":"PROVEEDOR","name":"Llantas del Norte SAS","documentType":"NIT","documentNumber":"800999111"}
                """);
        postId("/api/purchases", token, """
                {"invoiceNumber":"P-1","supplierId":%d,"paymentType":"CONTADO","accountId":%d,
                 "paymentMethod":"Efectivo","details":[{"productId":%d,"quantity":4,"price":100000}]}
                """.formatted(proveedor, caja, producto));
        long venta = postId("/api/sales", token, """
                {"paymentType":"CONTADO","accountId":%d,"paymentMethod":"Efectivo",
                 "details":[{"productId":%d,"quantity":1,"price":150000}]}
                """.formatted(caja, producto));

        // 1000000 - 4 x 100000 x 1.19 + 150000 x 1.19 = 702500.
        String cuentas = getJson("/api/treasury/accounts", token);
        assertThat(JsonPath.<Double>read(cuentas, "$[0].currentBalance")).isEqualTo(702500.0);
        assertThat(JsonPath.<Integer>read(getJson("/api/inventory/products/" + producto, token), "$.quantity"))
                .isEqualTo(3);
        String detalleVenta = getJson("/api/sales/" + venta, token);
        assertThat(JsonPath.<String>read(detalleVenta, "$.invoiceNumber")).isEqualTo("FV-0001");
        assertThat(JsonPath.<String>read(detalleVenta, "$.customer.name")).isEqualTo("Consumidor final");
        String clientes = getJson("/api/counterparties?role=CLIENTE", token);
        assertThat(JsonPath.<List<String>>read(clientes, "$[*].name")).containsExactly("Consumidor final");
    }

    // ---- Aislamiento ----

    @Test
    void elTenantNuevoYElDemoNoSeVenEntreSi() throws Exception {
        String nuevo = registrarYObtenerToken("taller-aislado", "admin");
        String demo = login("demo", "admin", "admin123");

        assertThat(getJson("/api/inventory/products", nuevo)).isEqualTo("[]");
        assertThat(JsonPath.<List<String>>read(getJson("/api/inventory/categories", nuevo), "$[*].name"))
                .doesNotContain("REPUESTOS DEMO", "FILTROS DE AIRE");
        assertThat(JsonPath.<List<String>>read(getJson("/api/treasury/accounts", nuevo), "$[*].name"))
                .containsExactly("Caja General");
        assertThat(getJson("/api/sales", nuevo)).isEqualTo("[]");
        assertThat(getJson("/api/purchases", nuevo)).isEqualTo("[]");
        assertThat(JsonPath.<List<String>>read(getJson("/api/counterparties", nuevo), "$[*].name"))
                .containsExactly("Consumidor final");

        String productosDemo = getJson("/api/inventory/products", demo);
        assertThat(JsonPath.<List<String>>read(productosDemo, "$[*].code")).containsOnly("DEMO-001", "DEMO-002", "DEMO-003");
        assertThat(JsonPath.<List<String>>read(getJson("/api/inventory/categories", demo), "$[*].name"))
                .doesNotContain("LUBRICANTES");
        assertThat(JsonPath.<List<String>>read(getJson("/api/treasury/accounts", demo), "$[*].name"))
                .containsExactlyInAnyOrder("Caja General", "Bancolombia");
        // El tenant nuevo solo tiene un impuesto: el suyo.
        mockMvc.perform(get("/api/inventory/tax-types").header("Authorization", "Bearer " + nuevo))
                .andExpect(jsonPath("$", hasSize(1)));
    }

    @Test
    void elMismoUsernameDeAdministradorEnDosTenantsEsValido() throws Exception {
        registrarYObtenerToken("taller-uno", "gerente");
        registrarYObtenerToken("taller-dos", "gerente");

        Long uno = tenantRepository.findBySlug("taller-uno").orElseThrow().getId();
        Long dos = tenantRepository.findBySlug("taller-dos").orElseThrow().getId();
        assertThat(userRepository.findByUsernameAndTenantId("gerente", uno)).isPresent();
        assertThat(userRepository.findByUsernameAndTenantId("gerente", dos)).isPresent();
        login("taller-uno", "gerente", PASSWORD);
        login("taller-dos", "gerente", PASSWORD);
    }

    // ---- Errores de validacion: uno por regla ----

    @Test
    void sinAceptarLosTerminosEs400() throws Exception {
        esperarError(cuerpoCon("acceptTerms", "false"), 400, "Debe aceptar los terminos y condiciones");
        esperarError(cuerpoCon("acceptTerms", "null"), 400, "Debe aceptar los terminos y condiciones");
    }

    @Test
    void nombreComercialVacioOLargoEs400() throws Exception {
        esperarError(cuerpoCon("businessName", "\"  \""), 400, "El nombre comercial es obligatorio");
        esperarError(cuerpoCon("businessName", "\"" + "a".repeat(151) + "\""), 400,
                "El nombre comercial no puede superar 150 caracteres");
    }

    @Test
    void nitInvalidoEs400() throws Exception {
        esperarError(cuerpoCon("nit", "\"\""), 400, "El NIT es obligatorio");
        esperarError(cuerpoCon("nit", "\"" + "9".repeat(26) + "\""), 400, "El NIT no puede superar 25 caracteres");
        String formato = "El NIT solo admite digitos y un guion con el digito de verificacion, por ejemplo 900123456-7";
        esperarError(cuerpoCon("nit", "\"900.123.456\""), 400, formato);
        esperarError(cuerpoCon("nit", "\"900123456-78\""), 400, formato);
        esperarError(cuerpoCon("nit", "\"NIT900123456\""), 400, formato);
    }

    @Test
    void telefonoInvalidoEs400() throws Exception {
        esperarError(cuerpoCon("phone", "\" \""), 400, "El telefono es obligatorio");
        esperarError(cuerpoCon("phone", "\"" + "3".repeat(26) + "\""), 400,
                "El telefono no puede superar 25 caracteres");
        esperarError(cuerpoCon("phone", "\"300-ABC-1234\""), 400,
                "El telefono solo admite digitos, espacios, +, - y parentesis");
    }

    @Test
    void slugInvalidoEs400() throws Exception {
        String formato = "El identificador debe tener de 3 a 30 caracteres con solo minusculas, numeros y guiones";
        esperarError(cuerpoCon("slug", "\"Taller-Norte\""), 400, formato);
        esperarError(cuerpoCon("slug", "\"ab\""), 400, formato);
        esperarError(cuerpoCon("slug", "\"" + "a".repeat(31) + "\""), 400, formato);
        esperarError(cuerpoCon("slug", "\"taller_norte\""), 400, formato);
        esperarError(cuerpoCon("slug", "null"), 400, formato);
        esperarError(cuerpoCon("slug", "\"-taller\""), 400, "El identificador no puede empezar ni terminar en guion");
        esperarError(cuerpoCon("slug", "\"taller-\""), 400, "El identificador no puede empezar ni terminar en guion");
        esperarError(cuerpoCon("slug", "\"admin\""), 400, "El identificador admin esta reservado");
        esperarError(cuerpoCon("slug", "\"sales\""), 400, "El identificador sales esta reservado");
    }

    @Test
    void nombreCompletoDelAdministradorVacioOLargoEs400() throws Exception {
        esperarError(cuerpoCon("adminFullName", "\"\""), 400, "El nombre completo del administrador es obligatorio");
        esperarError(cuerpoCon("adminFullName", "\"" + "a".repeat(151) + "\""), 400,
                "El nombre completo del administrador no puede superar 150 caracteres");
    }

    @Test
    void correoInvalidoEs400() throws Exception {
        esperarError(cuerpoCon("adminEmail", "\"ana-sin-arroba.co\""), 400, "El correo del administrador no es valido");
        esperarError(cuerpoCon("adminEmail", "\"ana@dominio\""), 400, "El correo del administrador no es valido");
        esperarError(cuerpoCon("adminEmail", "\"" + "a".repeat(145) + "@x.com\""), 400,
                "El correo del administrador no puede superar 150 caracteres");
    }

    @Test
    void usernameInvalidoEs400() throws Exception {
        String mensaje = "El usuario debe tener de 3 a 50 caracteres con letras, numeros, punto, guion y guion bajo";
        esperarError(cuerpoCon("adminUsername", "\"ab\""), 400, mensaje);
        esperarError(cuerpoCon("adminUsername", "\"" + "a".repeat(51) + "\""), 400, mensaje);
        esperarError(cuerpoCon("adminUsername", "\"ana rojas\""), 400, mensaje);
    }

    @Test
    void contrasenaDe7YDe73CaracteresEs400() throws Exception {
        esperarError(cuerpoCon("password", "\"1234567\""), 400, "La contrasena debe tener al menos 8 caracteres");
        esperarError(cuerpoCon("password", "\"" + "a".repeat(73) + "\""), 400,
                "La contrasena no puede superar 72 caracteres");
        assertThat(registrar(cuerpoCon("password", "\"" + "a".repeat(72) + "\"")
                .replace("taller-errores", "taller-clave-72")).getResponse().getStatus()).isEqualTo(201);
    }

    // ---- Slug repetido ----

    @Test
    void slugRepetidoEs409SinDatosAMedias() throws Exception {
        registrarYObtenerToken("taller-repetido", "admin");

        esperarError(cuerpo("taller-repetido", "otro.admin"), 409,
                "Ya existe una serviteca con el identificador taller-repetido");
        assertThat(tenantRepository.findBySlug("taller-repetido").orElseThrow().getName())
                .isEqualTo("Serviteca taller-repetido");
    }

    /** Un tenant anterior con mayusculas en el slug (creado por fuera del registro). */
    @Test
    void elSlugSeComparaEnMinusculasContraUnoExistente() throws Exception {
        Tenant existente = new Tenant();
        existente.setName("Taller Mayusculas");
        existente.setSlug("Taller-Mayus");
        existente.setActive(Boolean.TRUE);
        tenantRepository.save(existente);

        esperarError(cuerpo("taller-mayus", "admin"), 409,
                "Ya existe una serviteca con el identificador taller-mayus");
    }

    @Test
    void dosRegistrosSimultaneosConElMismoSlugDanUn201YUn409() throws Exception {
        List<Long> antes = conteos();
        CountDownLatch salida = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<MvcResult>> resultados = new ArrayList<>();
            for (String username : List.of("primero", "segundo")) {
                Callable<MvcResult> registro = () -> {
                    salida.await();
                    return registrar(cuerpo("taller-carrera", username));
                };
                resultados.add(pool.submit(registro));
            }
            salida.countDown();

            List<Integer> codigos = new ArrayList<>();
            for (Future<MvcResult> resultado : resultados) {
                MvcResult r = resultado.get();
                codigos.add(r.getResponse().getStatus());
                if (r.getResponse().getStatus() == 409) {
                    assertThat(JsonPath.<String>read(r.getResponse().getContentAsString(), "$.error"))
                            .isEqualTo("Ya existe una serviteca con el identificador taller-carrera");
                }
            }
            assertThat(codigos).containsExactlyInAnyOrder(201, 409);
        } finally {
            pool.shutdownNow();
        }

        // Un solo tenant nuevo, con un admin, 3 categorias, 1 IVA, 1 cuenta y 1 tercero.
        List<Long> despues = conteos();
        assertThat(despues.get(0) - antes.get(0)).isEqualTo(1);
        assertThat(despues.get(1) - antes.get(1)).isEqualTo(1);
        assertThat(despues.get(2) - antes.get(2)).isEqualTo(3);
        assertThat(despues.get(3) - antes.get(3)).isEqualTo(1);
        assertThat(despues.get(4) - antes.get(4)).isEqualTo(1);
        assertThat(despues.get(5) - antes.get(5)).isEqualTo(1);
    }

    // ---- Ruta publica ----

    @Test
    void elEndpointEsPublico() throws Exception {
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(cuerpo("taller-publico", "admin")))
                .andExpect(status().isCreated());
        mockMvc.perform(get("/api/inventory/products"))
                .andExpect(status().isUnauthorized());
    }
}
