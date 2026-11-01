package com.servibox.backend.platform;

import com.jayway.jsonpath.JsonPath;
import com.servibox.backend.auth.JwtService;
import com.servibox.backend.auth.entity.Role;
import com.servibox.backend.auth.entity.User;
import com.servibox.backend.auth.repository.UserRepository;
import com.servibox.backend.registration.RegistrationRequest;
import com.servibox.backend.registration.RegistrationRequestRepository;
import com.servibox.backend.registration.RegistrationRequestStatus;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Busquedas de texto del panel de plataforma: q en servitecas (nombre o codigo) y tenantName y
 * contactName en solicitudes. Tildes, mayusculas, espacios, ñ y comodines literales, y el
 * mantenimiento de las columnas normalizadas en cada escritura.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "servibox.registration-requests.enabled=true")
class AccentInsensitiveSearchTest {

    private static final String TENANTS = "/api/platform/tenants";
    private static final String SOLICITUDES = "/api/platform/registration-requests";

    @Autowired MockMvc mvc;
    @Autowired TenantRepository tenants;
    @Autowired UserRepository users;
    @Autowired RegistrationRequestRepository requests;
    @Autowired PlatformAdminRepository admins;
    @Autowired PlatformBootstrapService bootstrap;
    @Autowired PlatformJwtService platformJwt;
    @Autowired JwtService tenantJwt;
    @Autowired JdbcTemplate jdbc;

    private String platformToken;
    private final List<Long> tenantIds = new ArrayList<>();
    private Tenant nino;

    @BeforeEach
    void seed() {
        bootstrap.create("acentos@example.invalid", "ephemeral-test-password".toCharArray());
        platformToken = platformJwt.generateToken(admins.findAll().getFirst());
        nino = tenant("Taller Niño Feliz", "acc-nino");
        tenant("Camión   Rápido", "acc-camion");
        tenant("SERVITECA ACENTO", "acc-upper");
        tenant("serviteca acento dos", "acc-lower");
        tenant("Llantas 100% Ñandú", "acc-cien");
        tenant("Llantas 1000 Sur", "acc-mil");
        tenant("Aceite_Uno", "acc-guion");
        tenant("AceiteXUno", "acc-equis");
        tenant("Baterías Niña", "acc-nfd");
        tenant("Sin tilde en el nombre", "acc-camioneta");

        solicitud("a", "Serviteca Niño Dios", "María Gómez");
        solicitud("b", "CAMIÓN  DEL   SUR", "JOSÉ ÑÁÑEZ");
        solicitud("c", "Llantas 100% Norte", "Luis_Perez");
        solicitud("d", "Llantas 1000 Este", "LuisXPerez");
    }

    @AfterEach
    void cleanup() {
        TenantContext.clear();
        jdbc.update("DELETE FROM auditoria_plataforma");
        requests.deleteAll();
        jdbc.update("DELETE FROM usuarios WHERE username = 'admin-acentos'");
        tenants.deleteAllById(tenantIds);
        admins.deleteAll();
    }

    private Tenant tenant(String nombre, String slug) {
        Tenant t = new Tenant();
        t.setName(nombre);
        t.setSlug(slug);
        t = tenants.saveAndFlush(t);
        tenantIds.add(t.getId());
        return t;
    }

    private void solicitud(String letra, String nombre, String responsable) {
        LocalDateTime creada = LocalDateTime.parse("2026-10-15T05:00:00");
        RegistrationRequest r = new RegistrationRequest();
        r.setId("00000000-0000-0000-0000-00000000000" + letra);
        r.setBusinessName(nombre);
        r.setNit("900555666-1");
        r.setPhone("3001234567");
        r.setContactName(responsable);
        r.setContactEmail(letra + "@example.invalid");
        r.setNormalizedEmail(letra + "@example.invalid");
        r.setRequestedSlug("acc-sol-" + letra);
        r.setReservedSlug("acc-sol-" + letra);
        r.setStatus(RegistrationRequestStatus.PENDING);
        r.setBodyHash("0".repeat(64));
        r.setIdempotencyExpiresAt(creada.plusDays(7));
        r.setCreatedAt(creada);
        r.setReservationExpiresAt(creada.plusDays(30));
        requests.saveAndFlush(r);
    }

    private MockHttpServletRequestBuilder plataforma(MockHttpServletRequestBuilder builder) {
        return builder.header("Authorization", "Bearer " + platformToken);
    }

    /** Solo los slugs de esta prueba: el contexto de Spring se comparte y otras clases dejan tenants. */
    private List<String> slugs(String q, String... extra) throws Exception {
        MockHttpServletRequestBuilder b = plataforma(get(TENANTS)).param("q", q).param("size", "100");
        for (int i = 0; i < extra.length; i += 2) {
            b.param(extra[i], extra[i + 1]);
        }
        String json = mvc.perform(b).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        List<String> todos = JsonPath.read(json, "$.items[*].slug");
        return todos.stream().filter(s -> s.startsWith("acc-")).sorted().toList();
    }

    private List<String> letras(String parametro, String valor) throws Exception {
        String json = mvc.perform(plataforma(get(SOLICITUDES)).param(parametro, valor)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        List<String> ids = JsonPath.read(json, "$.items[*].id");
        int total = JsonPath.read(json, "$.totalElements");
        assertThat(total).as("total filtrado").isEqualTo(ids.size());
        return ids.stream().map(id -> id.substring(id.length() - 1)).sorted().toList();
    }

    @Test
    void servitecasSinTildesNiMayusculasEnAmbosSentidos() throws Exception {
        assertThat(slugs("nino")).containsExactly("acc-nino");
        assertThat(slugs("NIÑO")).containsExactly("acc-nino");
        assertThat(slugs("Niño feliz")).containsExactly("acc-nino");
        assertThat(slugs("camion rapido")).containsExactly("acc-camion");
        assertThat(slugs("CAMIÓN RÁPIDO")).containsExactly("acc-camion");
        assertThat(slugs("serviteca")).containsExactly("acc-lower", "acc-upper");
        assertThat(slugs("SERVITECA")).containsExactly("acc-lower", "acc-upper");
        assertThat(slugs("sérvitéca ACENTO")).containsExactly("acc-lower", "acc-upper");
        assertThat(slugs("nandu")).containsExactly("acc-cien");
        assertThat(slugs("ñandú")).containsExactly("acc-cien");
    }

    @Test
    void servitecasConEspaciosDoblesYFormaDescompuesta() throws Exception {
        assertThat(slugs("  camion     rapido  ")).containsExactly("acc-camion");
        assertThat(slugs("taller \t niño")).containsExactly("acc-nino");
        assertThat(slugs("baterias nina")).containsExactly("acc-nfd");
        assertThat(slugs("Baterías")).containsExactly("acc-nfd");
        assertThat(slugs("Baterías")).containsExactly("acc-nfd");
    }

    @Test
    void elCodigoSigueSiendoBuscableTambienEscritoConTilde() throws Exception {
        assertThat(slugs("acc-camioneta")).containsExactly("acc-camioneta");
        assertThat(slugs("ACC-CAMIÓNETA")).containsExactly("acc-camioneta");
        // "camion" esta en el nombre y el codigo del primero y solo en el codigo del segundo.
        assertThat(slugs("camion")).containsExactly("acc-camion", "acc-camioneta");
    }

    @Test
    void servitecasConComodinesLiterales() throws Exception {
        assertThat(slugs("100%")).containsExactly("acc-cien");
        assertThat(slugs("aceite_")).containsExactly("acc-guion");
        assertThat(slugs("%")).containsExactly("acc-cien");
        assertThat(slugs("\\")).isEmpty();
        assertThat(slugs("acc_nino")).isEmpty();
    }

    @Test
    void laBusquedaSeCombinaConElEstadoYNoCambiaAlSuspenderNiReactivar() throws Exception {
        String antes = tenants.findById(nino.getId()).orElseThrow().getNameSearch();
        mvc.perform(plataforma(post(TENANTS + "/" + nino.getId() + "/suspend")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedVersion\":0,\"reason\":\"Mora\"}")).andExpect(status().isOk());
        assertThat(slugs("nino", "status", "SUSPENDED")).containsExactly("acc-nino");
        assertThat(slugs("nino", "status", "ACTIVE")).isEmpty();
        mvc.perform(plataforma(post(TENANTS + "/" + nino.getId() + "/reactivate"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"expectedVersion\":1}")).andExpect(status().isOk());
        assertThat(slugs("nino", "status", "ACTIVE")).containsExactly("acc-nino");
        assertThat(tenants.findById(nino.getId()).orElseThrow().getNameSearch()).isEqualTo(antes);
    }

    @Test
    void editarElPerfilMantieneLaColumnaNormalizada() throws Exception {
        TenantContext.setTenantId(nino.getId());
        User admin;
        try {
            admin = new User();
            admin.setUsername("admin-acentos");
            admin.setPasswordHash("unused");
            admin.setRole(Role.ADMIN);
            admin = users.saveAndFlush(admin);
        } finally {
            TenantContext.clear();
        }
        mvc.perform(put("/api/tenant").header("Authorization", "Bearer " + tenantJwt.generateToken(admin))
                .contentType(MediaType.APPLICATION_JSON).characterEncoding(StandardCharsets.UTF_8)
                .content("{\"name\":\"  Lubricantes Peñón  \",\"nit\":\"900123456-7\",\"phone\":\"3001234567\"}"))
                .andExpect(status().isOk());

        assertThat(slugs("penon")).containsExactly("acc-nino");
        assertThat(slugs("nino feliz")).isEmpty();
        assertThat(tenants.findById(nino.getId()).orElseThrow().getName()).isEqualTo("Lubricantes Peñón");
    }

    @Test
    void solicitudesPorServitecaYResponsable() throws Exception {
        assertThat(letras("tenantName", "nino dios")).containsExactly("a");
        assertThat(letras("tenantName", "NIÑO")).containsExactly("a");
        assertThat(letras("tenantName", "camion del sur")).containsExactly("b");
        assertThat(letras("tenantName", "  Camión    DEL  ")).containsExactly("b");
        assertThat(letras("contactName", "maria gomez")).containsExactly("a");
        assertThat(letras("contactName", "MARÍA   GÓMEZ")).containsExactly("a");
        assertThat(letras("contactName", "jose nanez")).containsExactly("b");
        assertThat(letras("contactName", "ñáñez")).containsExactly("b");
        assertThat(letras("contactName", "María")).containsExactly("a");
    }

    @Test
    void solicitudesConComodinesLiterales() throws Exception {
        assertThat(letras("tenantName", "100%")).containsExactly("c");
        assertThat(letras("contactName", "luis_")).containsExactly("c");
        assertThat(letras("contactName", "%")).isEmpty();
        assertThat(letras("tenantName", "\\")).isEmpty();
    }

    @Test
    void elEnvioPublicoLlenaLasColumnasNormalizadas() throws Exception {
        String json = mvc.perform(post("/api/registration-requests").header("Idempotency-Key", "clave-acentos")
                .contentType(MediaType.APPLICATION_JSON).characterEncoding(StandardCharsets.UTF_8)
                .content("""
                        {"businessName":"  Peñón  Automotriz ","nit":"900123456-7","phone":"3001234567",
                        "slug":"acc-publico","adminFullName":"Óscar  Muñoz","adminEmail":"oscar@example.invalid",
                        "acceptTerms":true}
                        """))
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
        String id = JsonPath.read(json, "$.requestId");

        RegistrationRequest r = requests.findById(id).orElseThrow();
        assertThat(r.getBusinessNameSearch()).isEqualTo("penon automotriz");
        assertThat(r.getContactNameSearch()).isEqualTo("oscar munoz");
        assertThat(r.getBusinessName()).isEqualTo("Peñón  Automotriz");
        assertThat(letras("tenantName", "penon automotriz")).hasSize(1);
        assertThat(letras("contactName", "OSCAR muñoz")).hasSize(1);
    }

    @Test
    void contratoSinCambios() throws Exception {
        // La respuesta conserva el texto original y no expone las columnas de busqueda.
        String lista = mvc.perform(plataforma(get(TENANTS)).param("q", "nino feliz")).andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].name").value("Taller Niño Feliz")).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        String solicitudes = mvc.perform(plataforma(get(SOLICITUDES)).param("tenantName", "nino"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].businessName").value("Serviteca Niño Dios"))
                .andExpect(jsonPath("$.items[0].contactName").value("María Gómez")).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        assertThat(lista + solicitudes).doesNotContainIgnoringCase("search").doesNotContainIgnoringCase("busqueda");

        // Mismo limite y mismo texto: se cuentan los caracteres recibidos, no los normalizados.
        mvc.perform(plataforma(get(TENANTS)).param("q", "á".repeat(100))).andExpect(status().isOk());
        mvc.perform(plataforma(get(TENANTS)).param("q", "á".repeat(101))).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("La busqueda no puede superar 100 caracteres"));
        mvc.perform(plataforma(get(SOLICITUDES)).param("tenantName", "á".repeat(101)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("El filtro de texto no puede superar 100 caracteres"));

        // Solo marcas diacriticas: al normalizar queda vacio y equivale a no filtrar.
        assertThat(letras("tenantName", "́")).containsExactly("a", "b", "c", "d");
        assertThat(slugs("́")).contains("acc-nino");
    }
}
