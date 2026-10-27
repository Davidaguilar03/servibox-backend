package com.servibox.backend.treasury;

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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AccountEditingTest {
    @Autowired private MockMvc mockMvc;
    @Autowired private TenantRepository tenantRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    private String token;
    private int cuenta;

    @BeforeEach
    void seed() throws Exception {
        token = usuario(Role.ADMIN);
        cuenta = crearCuenta(token, "Banco", "100000");
        gasto(60000);
    }

    @AfterEach
    void cleanUp() {
        TenantContext.clear();
    }

    private String usuario(Role role) throws Exception {
        Tenant tenant = new Tenant();
        String slug = "editar-cuenta-" + System.nanoTime();
        tenant.setName(slug);
        tenant.setSlug(slug);
        tenant.setActive(true);
        TenantContext.setTenantId(tenantRepository.save(tenant).getId());
        User user = new User();
        user.setUsername("usuario");
        user.setPasswordHash(passwordEncoder.encode("clave-correcta"));
        user.setActive(true);
        user.setRole(role);
        userRepository.save(user);
        TenantContext.clear();
        String json = mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"tenantSlug":"%s","username":"usuario","password":"clave-correcta"}
                                """.formatted(slug)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(json, "$.token");
    }

    private int crearCuenta(String auth, String name, String initial) throws Exception {
        String json = mockMvc.perform(post("/api/treasury/accounts").header("Authorization", "Bearer " + auth)
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"name":"%s","initialBalance":%s,"type":"BANCO"}
                                """.formatted(name, initial)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(json, "$.id");
    }

    private void gasto(int amount) throws Exception {
        mockMvc.perform(post("/api/treasury/movements").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"accountId":%d,"category":"GASTO_OPERATIVO","concept":"Gasto","amount":%d}
                                """.formatted(cuenta, amount)))
                .andExpect(status().isCreated());
    }

    private ResultActions editar(String body) throws Exception {
        return mockMvc.perform(put("/api/treasury/accounts/" + cuenta)
                .header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private ResultActions alta(String body) throws Exception {
        return mockMvc.perform(post("/api/treasury/accounts").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    @Test
    void altaNormalizaColorYLoDevuelveEnListado() throws Exception {
        alta("{\"name\":\"Colorida\",\"initialBalance\":10,\"type\":\"BANCO\",\"color\":\"#7c3aEd\"}")
                .andExpect(status().isCreated()).andExpect(jsonPath("$.color").value("#7C3AED"));
        mockMvc.perform(get("/api/treasury/accounts").header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$[?(@.name == 'Colorida')].color").value("#7C3AED"));
    }

    @Test
    void altaSinColorYConNullDevuelveNull() throws Exception {
        alta("{\"name\":\"Sin color\",\"type\":\"BANCO\"}")
                .andExpect(status().isCreated()).andExpect(jsonPath("$.color").value((Object) null));
        alta("{\"name\":\"Null\",\"type\":\"BANCO\",\"color\":null}")
                .andExpect(status().isCreated()).andExpect(jsonPath("$.color").value((Object) null));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "red", "#ABC", "#12345678", "#12345G", "#123456 ", "#12345"})
    void altaInvalidaNoPersisteCuenta(String color) throws Exception {
        alta("{\"name\":\"Rechazada\",\"type\":\"BANCO\",\"color\":\"" + color + "\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("El color debe tener el formato #RRGGBB"));
        mockMvc.perform(get("/api/treasury/accounts").header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$[?(@.name == 'Rechazada')]").isEmpty());
    }

    @Test
    void editarSoloColorConservaSaldoYMovimiento() throws Exception {
        editar("{\"name\":\"Banco\",\"initialBalance\":100000,\"color\":\"#aBc123\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.color").value("#ABC123"))
                .andExpect(jsonPath("$.initialBalance").value(100000.0))
                .andExpect(jsonPath("$.currentBalance").value(40000.0));
        mockMvc.perform(get("/api/treasury/accounts").header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$[?(@.id == %d)].color".formatted(cuenta)).value("#ABC123"));
        mockMvc.perform(get("/api/treasury/accounts/" + cuenta + "/movements")
                        .header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void omitirONullEnEdicionConservaColor() throws Exception {
        editar("{\"name\":\"Banco\",\"initialBalance\":100000,\"color\":\"#112233\"}")
                .andExpect(status().isOk());
        editar("{\"name\":\"Banco\",\"initialBalance\":100000}")
                .andExpect(jsonPath("$.color").value("#112233"));
        editar("{\"name\":\"Banco\",\"initialBalance\":100000,\"color\":null}")
                .andExpect(jsonPath("$.color").value("#112233"));
    }

    @Test
    void cuentaHeredadaSinColorSigueEditable() throws Exception {
        editar("{\"name\":\"Heredada\",\"initialBalance\":100000}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.color").value((Object) null));
    }

    @Test
    void colorInvalidoEnEdicionNoCambiaCuenta() throws Exception {
        editar("{\"name\":\"Otro\",\"initialBalance\":120000,\"color\":\"#FFF\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("El color debe tener el formato #RRGGBB"));
        persistido("Banco", 100000, 40000);
    }

    @Test
    void tenantAjenoNoPuedeCambiarColor() throws Exception {
        String otro = usuario(Role.ADMIN);
        mockMvc.perform(put("/api/treasury/accounts/" + cuenta).header("Authorization", "Bearer " + otro)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Banco\",\"initialBalance\":100000,\"color\":\"#112233\"}"))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/treasury/accounts").header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$[?(@.id == %d)].color".formatted(cuenta)).value((Object) null));
    }

    private void persistido(String name, double initial, double balance) throws Exception {
        mockMvc.perform(get("/api/treasury/accounts").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == %d)].name".formatted(cuenta)).value(name))
                .andExpect(jsonPath("$[?(@.id == %d)].initialBalance".formatted(cuenta)).value(initial))
                .andExpect(jsonPath("$[?(@.id == %d)].currentBalance".formatted(cuenta)).value(balance));
    }

    @Test
    void cambiarNombreConservaSaldoYNoCreaMovimientos() throws Exception {
        editar("""
                {"name":"Banco nuevo","initialBalance":100000}
                """).andExpect(status().isOk()).andExpect(jsonPath("$.currentBalance").value(40000.0));
        persistido("Banco nuevo", 100000, 40000);
        mockMvc.perform(get("/api/treasury/accounts/" + cuenta + "/movements")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void duplicadoRechazaSinPersistirNombreNiSaldos() throws Exception {
        crearCuenta(token, "Otra", "0");
        editar("""
                {"name":"Otra","initialBalance":120000}
                """).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("Ya existe una cuenta con el nombre Otra"));
        persistido("Banco", 100000, 40000);
    }

    @Test
    void nombreDeOtroTenantPermitido() throws Exception {
        crearCuenta(usuario(Role.ADMIN), "Ajena", "0");
        editar("""
                {"name":"Ajena","initialBalance":100000}
                """).andExpect(status().isOk());
    }

    @Test
    void nombreNoSeRecortaNiComparaSinMayusculas() throws Exception {
        crearCuenta(token, "OTRA", "0");
        editar("""
                {"name":"otra","initialBalance":100000}
                """).andExpect(status().isOk());
        editar("""
                {"name":" OTRA ","initialBalance":100000}
                """).andExpect(status().isOk()).andExpect(jsonPath("$.name").value(" OTRA "));
    }

    @ParameterizedTest
    @ValueSource(strings = {"\"\"", "\"   \"", "null"})
    void nombreObligatorio(String name) throws Exception {
        editar("{\"name\":" + name + ",\"initialBalance\":100000}").andExpect(status().isBadRequest());
        persistido("Banco", 100000, 40000);
    }

    @Test
    void largoMaximoDeColumna() throws Exception {
        editar("{\"name\":\"" + "a".repeat(100) + "\",\"initialBalance\":100000}")
                .andExpect(status().isOk());
        editar("{\"name\":\"" + "b".repeat(101) + "\",\"initialBalance\":100000}")
                .andExpect(status().isBadRequest());
        persistido("a".repeat(100), 100000, 40000);
    }

    @ParameterizedTest
    @ValueSource(ints = {120000, 70000, 60000})
    void saldoSeMueveExactamenteLaDiferencia(int initial) throws Exception {
        editar("{\"name\":\"Banco\",\"initialBalance\":" + initial + "}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.currentBalance").value(initial - 60000.0));
        persistido("Banco", initial, initial - 60000.0);
    }

    @Test
    void reduccionInsuficienteNoPersisteNingunCampo() throws Exception {
        editar("""
                {"name":"No guardar","initialBalance":50000}
                """).andExpect(status().isConflict()).andExpect(jsonPath("$.error")
                .value("Saldo insuficiente en Banco: faltan $ 10.000 para cambiar el saldo inicial"));
        persistido("Banco", 100000, 40000);
    }

    @Test
    void soloNombreNoRevalidaSaldoNegativo() throws Exception {
        gasto(50000);
        editar("""
                {"name":"Negativa","initialBalance":100000}
                """).andExpect(status().isOk()).andExpect(jsonPath("$.currentBalance").value(-10000.0));
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"initialBalance\":null}", "{\"initialBalance\":-1}"})
    void saldoInicialObligatorioYNoNegativo(String fields) throws Exception {
        editar(fields.replace("{", "{\"name\":\"Banco\"" + (fields.length() > 2 ? "," : "")))
                .andExpect(status().isBadRequest());
        persistido("Banco", 100000, 40000);
    }

    @Test
    void tipoDiferenteRechazado() throws Exception {
        editar("""
                {"name":"No guardar","initialBalance":120000,"type":"EFECTIVO"}
                """).andExpect(status().isBadRequest()).andExpect(jsonPath("$.error")
                .value("El tipo de una cuenta no se puede cambiar"));
        persistido("Banco", 100000, 40000);
    }

    @Test
    void tipoActualAceptadoYCamposDesconocidosNoEditanSaldoActual() throws Exception {
        editar("""
                {"name":"Banco","initialBalance":100000,"type":"BANCO","currentBalance":999999,"extra":1}
                """).andExpect(status().isOk()).andExpect(jsonPath("$.currentBalance").value(40000.0));
        persistido("Banco", 100000, 40000);
    }

    @Test
    void cuentaInexistenteEs404() throws Exception {
        mockMvc.perform(put("/api/treasury/accounts/9223372036854775807")
                        .header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Banco\",\"initialBalance\":0}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void cuentaAjenaEs404() throws Exception {
        String otro = usuario(Role.ADMIN);
        mockMvc.perform(put("/api/treasury/accounts/" + cuenta)
                        .header("Authorization", "Bearer " + otro).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Banco\",\"initialBalance\":0}"))
                .andExpect(status().isNotFound());
        persistido("Banco", 100000, 40000);
    }

    @Test
    void sinAutenticacionEs401() throws Exception {
        mockMvc.perform(put("/api/treasury/accounts/" + cuenta).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Banco\",\"initialBalance\":0}")).andExpect(status().isUnauthorized());
    }

    @ParameterizedTest
    @EnumSource(Role.class)
    void mismosPermisosDeCrearParaTodosLosRoles(Role role) throws Exception {
        String auth = usuario(role);
        int id = crearCuenta(auth, "Permitida", "100");
        mockMvc.perform(put("/api/treasury/accounts/" + id).header("Authorization", "Bearer " + auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Editada\",\"initialBalance\":200}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.currentBalance").value(200.0));
    }
}
