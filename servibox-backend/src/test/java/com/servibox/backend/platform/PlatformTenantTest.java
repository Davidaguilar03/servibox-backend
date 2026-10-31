package com.servibox.backend.platform;

import com.jayway.jsonpath.JsonPath;
import com.servibox.backend.auth.entity.Role;
import com.servibox.backend.auth.entity.User;
import com.servibox.backend.auth.repository.UserRepository;
import com.servibox.backend.registration.RegistrationRequest;
import com.servibox.backend.registration.RegistrationRequestRepository;
import com.servibox.backend.registration.RegistrationRequestStatus;
import com.servibox.backend.tenant.Tenant;
import com.servibox.backend.tenant.TenantContext;
import com.servibox.backend.tenant.TenantRepository;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Panel de plataforma sobre tenants y usuarios (5A): lectura, suspension y reactivacion con
 * version y transiciones, revocacion de sesiones, metricas, auditoria y aislamiento de las
 * dos identidades. Los tokens tenant salen del login real.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PlatformTenantTest {

    private static final String PASSWORD = "clave-de-prueba-5a";

    @Autowired MockMvc mvc;
    @Autowired PlatformBootstrapService bootstrap;
    @Autowired PlatformAdminRepository admins;
    @Autowired PlatformJwtService platformJwt;
    @Autowired PlatformAuditRepository audits;
    @Autowired TenantRepository tenants;
    @Autowired UserRepository users;
    @Autowired RegistrationRequestRepository requests;
    @Autowired PasswordEncoder passwordEncoder;
    @Value("${servibox.jwt.secret}") String tenantSecret;

    private String platformToken;
    private final List<Long> tenantIds = new ArrayList<>();
    private final List<String> requestIds = new ArrayList<>();
    private Tenant tallerA;
    private Tenant tallerB;
    private User adminA;
    private User cajeroA;
    private User adminB;

    @BeforeEach
    void seed() {
        bootstrap.create("panel@example.invalid", "ephemeral-test-password".toCharArray());
        platformToken = platformJwt.generateToken(admins.findById(1L).orElseThrow());
        tallerA = tenant("Taller Alfa 5A", "pt5a-alfa");
        tallerB = tenant("Taller Beta 5A", "pt5a-beta");
        adminA = usuario(tallerA, "admin", Role.ADMIN);
        cajeroA = usuario(tallerA, "cajero", Role.CAJERO);
        adminB = usuario(tallerB, "admin", Role.ADMIN);
    }

    @AfterEach
    void cleanup() {
        TenantContext.clear();
        for (Long id : tenantIds) {
            audits.deleteAll(audits.findByTenantIdOrderByIdAsc(id));
        }
        requestIds.forEach(requests::deleteById);
        for (Long id : tenantIds) {
            users.deleteAll(users.findByTenantId(id, org.springframework.data.domain.Pageable.unpaged()).getContent());
            tenants.deleteById(id);
        }
        admins.deleteAll();
    }

    // ---- Lectura ----

    @Test
    void listaPaginadaConOrdenEstableBusquedaYFiltroDeEstado() throws Exception {
        Tenant gamma = tenant("Taller Gamma 5A", "pt5a-gamma");

        String pagina0 = leer(plataforma(get("/api/platform/tenants?q=PT5A&size=2")), 200);
        assertThat((Integer) JsonPath.read(pagina0, "$.totalElements")).isEqualTo(3);
        assertThat((Integer) JsonPath.read(pagina0, "$.totalPages")).isEqualTo(2);
        List<Integer> ids0 = JsonPath.read(pagina0, "$.items[*].id");
        String pagina1 = leer(plataforma(get("/api/platform/tenants?q=pt5a&size=2&page=1")), 200);
        List<Integer> ids1 = JsonPath.read(pagina1, "$.items[*].id");
        List<Integer> todos = new ArrayList<>(ids0);
        todos.addAll(ids1);
        // Mas reciente primero (fecha y luego id descendente), sin repetir entre paginas.
        assertThat(todos).containsExactly(gamma.getId().intValue(), tallerB.getId().intValue(),
                tallerA.getId().intValue());

        // Busqueda por nombre sin distinguir mayusculas; comodines de LIKE tomados literal.
        mvc.perform(plataforma(get("/api/platform/tenants").param("q", "TALLER beta")))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.items[0].slug").value("pt5a-beta"))
                .andExpect(jsonPath("$.items[0].status").value("ACTIVE"))
                .andExpect(jsonPath("$.items[0].version").value(0));
        mvc.perform(plataforma(get("/api/platform/tenants").param("q", "pt5a%"))).andExpect(jsonPath("$.totalElements").value(0));
        mvc.perform(plataforma(get("/api/platform/tenants").param("q", "pt5_"))).andExpect(jsonPath("$.totalElements").value(0));

        suspender(tallerA.getId(), 0, "Mora").andExpect(status().isOk());
        mvc.perform(plataforma(get("/api/platform/tenants?q=pt5a&status=SUSPENDED")))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.items[0].id").value(tallerA.getId()))
                .andExpect(jsonPath("$.items[0].suspensionReason").value("Mora"));
        mvc.perform(plataforma(get("/api/platform/tenants?q=pt5a&status=ACTIVE")))
                .andExpect(jsonPath("$.totalElements").value(2));

        error(plataforma(get("/api/platform/tenants?size=0")), 400, "Paginacion invalida");
        error(plataforma(get("/api/platform/tenants?size=101")), 400, "Paginacion invalida");
        error(plataforma(get("/api/platform/tenants?page=-1")), 400, "Paginacion invalida");
        error(plataforma(get("/api/platform/tenants?q=" + "x".repeat(101))), 400,
                "La busqueda no puede superar 100 caracteres");
        mvc.perform(plataforma(get("/api/platform/tenants?status=DELETED"))).andExpect(status().isBadRequest());
    }

    @Test
    void detalleConConteosPorRolYEstadoYSolicitudDeOrigen() throws Exception {
        User inactivo = usuario(tallerA, "mecanico", Role.MECANICO);
        inactivo.setActive(false);
        TenantContext.setTenantId(tallerA.getId());
        try {
            users.saveAndFlush(inactivo);
        } finally {
            TenantContext.clear();
        }

        mvc.perform(plataforma(get("/api/platform/tenants/" + tallerA.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tenant.slug").value("pt5a-alfa"))
                .andExpect(jsonPath("$.tenant.status").value("ACTIVE"))
                .andExpect(jsonPath("$.tenant.version").value(0))
                .andExpect(jsonPath("$.tenant.createdAt").exists())
                .andExpect(jsonPath("$.totalUsers").value(3))
                .andExpect(jsonPath("$.userCounts.length()").value(3))
                .andExpect(jsonPath("$.userCounts[?(@.role=='ADMIN' && @.active==true)].count").value(1))
                .andExpect(jsonPath("$.userCounts[?(@.role=='CAJERO' && @.active==true)].count").value(1))
                .andExpect(jsonPath("$.userCounts[?(@.role=='MECANICO' && @.active==false)].count").value(1))
                .andExpect(jsonPath("$.registrationRequest").doesNotExist());

        String solicitud = solicitudActivada(tallerB);
        mvc.perform(plataforma(get("/api/platform/tenants/" + tallerB.getId())))
                .andExpect(jsonPath("$.totalUsers").value(1))
                .andExpect(jsonPath("$.registrationRequest.id").value(solicitud))
                .andExpect(jsonPath("$.registrationRequest.status").value("ACTIVATED"));

        error(plataforma(get("/api/platform/tenants/999999")), 404, "Tenant no encontrado");
        error(plataforma(get("/api/platform/tenants/999999/users")), 404, "Tenant no encontrado");
    }

    @Test
    void usuariosPaginadosDelTenantSinSecretos() throws Exception {
        String pagina0 = leer(plataforma(get("/api/platform/tenants/" + tallerA.getId() + "/users?size=1")), 200);
        String pagina1 = leer(plataforma(get("/api/platform/tenants/" + tallerA.getId() + "/users?size=1&page=1")), 200);
        assertThat((Integer) JsonPath.read(pagina0, "$.totalElements")).isEqualTo(2);
        assertThat((Integer) JsonPath.read(pagina0, "$.totalPages")).isEqualTo(2);
        assertThat((String) JsonPath.read(pagina0, "$.items[0].username")).isEqualTo("admin");
        assertThat((String) JsonPath.read(pagina1, "$.items[0].username")).isEqualTo("cajero");
        assertThat((String) JsonPath.read(pagina1, "$.items[0].role")).isEqualTo("CAJERO");
        // Solo los usuarios de ese tenant: el admin de B no aparece.
        assertThat(pagina0 + pagina1).doesNotContain("\"id\":" + adminB.getId() + ",");
        error(plataforma(get("/api/platform/tenants/" + tallerA.getId() + "/users?size=0")), 400,
                "Paginacion invalida");
    }

    // ---- Estados ----

    @Test
    void suspenderYReactivarExigenVersionMotivoYTransicionValida() throws Exception {
        String ruta = "/api/platform/tenants/" + tallerA.getId();
        error(plataforma(post(ruta + "/suspend")).contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"x\"}"),
                400, "La version esperada es obligatoria");
        error(suspenderPeticion(tallerA.getId(), 0, "   "), 400, "El motivo de suspension es obligatorio");
        error(suspenderPeticion(tallerA.getId(), 0, null), 400, "El motivo de suspension es obligatorio");
        error(suspenderPeticion(tallerA.getId(), 0, "m".repeat(501)), 400, "El motivo no puede superar 500 caracteres");
        error(suspenderPeticion(tallerA.getId(), 7, "Mora"), 409, "El recurso cambio; actualiza e intenta de nuevo");
        error(suspenderPeticion(999999L, 0, "Mora"), 404, "Tenant no encontrado");
        error(reactivarPeticion(tallerA.getId(), 0), 409, "El tenant ya esta activo");

        suspender(tallerA.getId(), 0, "  Mora de tres meses  ")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tenant.status").value("SUSPENDED"))
                .andExpect(jsonPath("$.tenant.version").value(1))
                .andExpect(jsonPath("$.tenant.suspensionReason").value("Mora de tres meses"))
                .andExpect(jsonPath("$.tenant.suspendedAt").exists());
        error(suspenderPeticion(tallerA.getId(), 1, "Otra vez"), 409, "El tenant ya esta suspendido");
        error(suspenderPeticion(tallerA.getId(), 0, "Version vieja"), 409,
                "El recurso cambio; actualiza e intenta de nuevo");
        error(reactivarPeticion(tallerA.getId(), 0), 409, "El recurso cambio; actualiza e intenta de nuevo");

        mvc.perform(reactivarPeticion(tallerA.getId(), 1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tenant.status").value("ACTIVE"))
                .andExpect(jsonPath("$.tenant.version").value(2))
                .andExpect(jsonPath("$.tenant.suspendedAt").doesNotExist())
                .andExpect(jsonPath("$.tenant.suspensionReason").doesNotExist());
        error(reactivarPeticion(tallerA.getId(), 2), 409, "El tenant ya esta activo");

        // Los rechazos no dejaron rastro: solo las dos acciones aplicadas, con actor y motivo.
        List<PlatformAuditEntry> rastro = audits.findByTenantIdOrderByIdAsc(tallerA.getId());
        assertThat(rastro).extracting(PlatformAuditEntry::getAction)
                .containsExactly(PlatformAuditAction.SUSPEND_TENANT, PlatformAuditAction.REACTIVATE_TENANT);
        assertThat(rastro).extracting(PlatformAuditEntry::getAdminId).containsOnly(1L);
        assertThat(rastro.get(0).getReason()).isEqualTo("Mora de tres meses");
        assertThat(rastro.get(1).getReason()).isEqualTo("Pago recibido");
        assertThat(rastro).allSatisfy(a -> assertThat(a.getCreatedAt()).isNotNull());
        assertThat(audits.findByTenantIdOrderByIdAsc(tallerB.getId())).isEmpty();
    }

    @Test
    void laSuspensionBloqueaTokensYaEmitidosYElLoginYNoRevivenAlReactivar() throws Exception {
        String tokenA = login("pt5a-alfa", "cajero");
        String tokenB = login("pt5a-beta", "admin");
        tenantApi(tokenA).andExpect(status().isOk());

        suspender(tallerA.getId(), 0, "Mora").andExpect(status().isOk());

        error(tenantGet(tokenA), 401, "No autenticado");
        error(loginPeticion("pt5a-alfa", "cajero"), 401, "Credenciales invalidas");
        tenantApi(tokenB).andExpect(status().isOk());

        mvc.perform(reactivarPeticion(tallerA.getId(), 1)).andExpect(status().isOk());
        tenantApi(tokenA).andExpect(status().isUnauthorized());
        tenantApi(login("pt5a-alfa", "cajero")).andExpect(status().isOk());
    }

    // ---- Revocacion ----

    @Test
    void revocarUnUsuarioInvalidaSusTokensDeInmediatoYEsIdempotente() throws Exception {
        String admin = login("pt5a-alfa", "admin");
        String cajero = login("pt5a-alfa", "cajero");
        String cajeroHeredado = tokenSinVersion(cajeroA);
        tenantApi(cajeroHeredado).andExpect(status().isOk());
        String ruta = "/api/platform/tenants/" + tallerA.getId() + "/users/" + cajeroA.getId() + "/revoke";

        String primera = leer(plataforma(post(ruta)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"Equipo extraviado\"}"), 200);
        tenantApi(cajero).andExpect(status().isUnauthorized());
        tenantApi(cajeroHeredado).andExpect(status().isUnauthorized());
        tenantApi(admin).andExpect(status().isOk());

        // Sin cuerpo tambien sirve; misma respuesta, los tokens viejos siguen invalidos.
        String segunda = leer(plataforma(post(ruta)), 200);
        assertThat(segunda).isEqualTo(primera);
        assertThat((String) JsonPath.read(segunda, "$.username")).isEqualTo("cajero");
        tenantApi(cajero).andExpect(status().isUnauthorized());
        tenantApi(login("pt5a-alfa", "cajero")).andExpect(status().isOk());

        assertThat(audits.findByTenantIdOrderByIdAsc(tallerA.getId()))
                .extracting(PlatformAuditEntry::getAction, PlatformAuditEntry::getUserId, PlatformAuditEntry::getReason)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(PlatformAuditAction.REVOKE_USER_SESSIONS, cajeroA.getId(),
                                "Equipo extraviado"),
                        org.assertj.core.groups.Tuple.tuple(PlatformAuditAction.REVOKE_USER_SESSIONS, cajeroA.getId(),
                                null));

        // Un usuario de otro tenant no se alcanza por la ruta de este: 404, sin revocar nada.
        String adminBToken = login("pt5a-beta", "admin");
        error(plataforma(post("/api/platform/tenants/" + tallerA.getId() + "/users/" + adminB.getId() + "/revoke")),
                404, "Usuario no encontrado");
        tenantApi(adminBToken).andExpect(status().isOk());
        error(plataforma(post("/api/platform/tenants/999999/users/" + adminB.getId() + "/revoke")), 404,
                "Tenant no encontrado");
        error(plataforma(post(ruta)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"" + "m".repeat(501) + "\"}"), 400, "El motivo no puede superar 500 caracteres");
    }

    @Test
    void revocarLasSesionesDelTenantInvalidaATodosSusUsuariosYSoloAEllos() throws Exception {
        String admin = login("pt5a-alfa", "admin");
        String cajero = login("pt5a-alfa", "cajero");
        String otroTenant = login("pt5a-beta", "admin");

        mvc.perform(plataforma(post("/api/platform/tenants/" + tallerA.getId() + "/revoke-sessions"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"Incidente\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tenantId").value(tallerA.getId()))
                .andExpect(jsonPath("$.revokedUsers").value(2));
        tenantApi(admin).andExpect(status().isUnauthorized());
        tenantApi(cajero).andExpect(status().isUnauthorized());
        tenantApi(otroTenant).andExpect(status().isOk());

        mvc.perform(plataforma(post("/api/platform/tenants/" + tallerA.getId() + "/revoke-sessions")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revokedUsers").value(2));
        error(plataforma(post("/api/platform/tenants/999999/revoke-sessions")), 404, "Tenant no encontrado");
        assertThat(audits.findByTenantIdOrderByIdAsc(tallerA.getId())).extracting(PlatformAuditEntry::getAction)
                .containsExactly(PlatformAuditAction.REVOKE_TENANT_SESSIONS, PlatformAuditAction.REVOKE_TENANT_SESSIONS);
    }

    @Test
    void unClaimAuthVersionMalFormadoNoAutentica() throws Exception {
        for (Object version : new Object[]{-1, "0", 1.5}) {
            String token = Jwts.builder().subject("cajero").claim("tenantId", tallerA.getId()).claim("role", "CAJERO")
                    .claim("authVersion", version)
                    .issuer("servibox-tenant").audience().add("tenant").and().claim("identityType", "TENANT")
                    .issuedAt(new Date(System.currentTimeMillis() - 2000))
                    .expiration(new Date(System.currentTimeMillis() + 60000))
                    .signWith(Keys.hmacShaKeyFor(tenantSecret.getBytes(StandardCharsets.UTF_8))).compact();
            tenantApi(token).andExpect(status().isUnauthorized());
        }
    }

    // ---- Metricas ----

    @Test
    void metricasSoloConConteosAgregados() throws Exception {
        String antes = leer(plataforma(get("/api/platform/metrics")), 200);
        suspender(tallerA.getId(), 0, "Mora").andExpect(status().isOk());
        String despues = leer(plataforma(get("/api/platform/metrics")), 200);

        assertThat(numero(despues, "$.tenants.SUSPENDED")).isEqualTo(numero(antes, "$.tenants.SUSPENDED") + 1);
        assertThat(numero(despues, "$.tenants.ACTIVE")).isEqualTo(numero(antes, "$.tenants.ACTIVE") - 1);
        assertThat(numero(despues, "$.users.total")).isEqualTo(users.count());
        assertThat(numero(despues, "$.users.total"))
                .isEqualTo(numero(despues, "$.users.active") + numero(despues, "$.users.inactive"));
        assertThat((java.util.Map<String, Object>) JsonPath.read(despues, "$.registrationRequests"))
                .containsOnlyKeys("PENDING", "APPROVED", "REJECTED", "CANCELLED", "ACTIVATED");
        assertThat((java.util.Map<String, Object>) JsonPath.read(despues, "$.outboxMessages"))
                .containsOnlyKeys("PENDING", "SENT", "FAILED");
        assertThat(despues).doesNotContain("pt5a", "Taller", "admin", "@");
    }

    // ---- Seguridad ----

    @Test
    void ningunTokenTenantNiAnonimoEntraAlPanel() throws Exception {
        String tenantToken = login("pt5a-alfa", "admin");
        String t = "/api/platform/tenants/" + tallerB.getId();
        List<MockHttpServletRequestBuilder> rutas = List.of(get("/api/platform/tenants"), get(t), get(t + "/users"),
                get("/api/platform/metrics"), post(t + "/suspend"), post(t + "/reactivate"),
                post(t + "/revoke-sessions"), post(t + "/users/" + adminB.getId() + "/revoke"));
        for (MockHttpServletRequestBuilder ruta : rutas) {
            mvc.perform(ruta.header("Authorization", "Bearer " + tenantToken)
                            .contentType(MediaType.APPLICATION_JSON).content("{\"expectedVersion\":0,\"reason\":\"x\"}"))
                    .andExpect(status().isUnauthorized());
        }
        for (MockHttpServletRequestBuilder ruta : List.of(get("/api/platform/tenants"), post(t + "/suspend"))) {
            mvc.perform(ruta.contentType(MediaType.APPLICATION_JSON).content("{\"expectedVersion\":0,\"reason\":\"x\"}"))
                    .andExpect(status().isUnauthorized());
        }
        // Nada cambio en B.
        Tenant b = tenants.findById(tallerB.getId()).orElseThrow();
        assertThat(b.getActive()).isTrue();
        assertThat(b.getVersion()).isZero();
        tenantApi(login("pt5a-beta", "admin")).andExpect(status().isOk());
        assertThat(audits.findByTenantIdOrderByIdAsc(tallerB.getId())).isEmpty();
        // Y el token de plataforma no entra a la API comercial.
        mvc.perform(get("/api/tenant").header("Authorization", "Bearer " + platformToken))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void ningunaRespuestaDelPanelLlevaHashesNiSecretos() throws Exception {
        String token = login("pt5a-alfa", "cajero");
        String hash = users.findById(cajeroA.getId()).orElseThrow().getPasswordHash();
        String t = "/api/platform/tenants/" + tallerA.getId();
        List<String> respuestas = new ArrayList<>();
        respuestas.add(leer(plataforma(get("/api/platform/tenants?q=pt5a")), 200));
        respuestas.add(leer(plataforma(get(t)), 200));
        respuestas.add(leer(plataforma(get(t + "/users")), 200));
        respuestas.add(leer(plataforma(get("/api/platform/metrics")), 200));
        respuestas.add(leer(plataforma(post(t + "/users/" + cajeroA.getId() + "/revoke")), 200));
        respuestas.add(leer(plataforma(post(t + "/revoke-sessions")), 200));
        respuestas.add(leer(suspenderPeticion(tallerA.getId(), 0, "Mora"), 200));
        respuestas.add(leer(reactivarPeticion(tallerA.getId(), 1), 200));
        for (String r : respuestas) {
            assertThat(r).doesNotContain(hash, "$2a$", "$2b$", "password", "Hash", "authVersion", token,
                    platformToken, "secret");
        }
    }

    // ---- Apoyo ----

    private Tenant tenant(String nombre, String slug) {
        Tenant t = new Tenant();
        t.setName(nombre);
        t.setSlug(slug);
        t.setActive(true);
        t = tenants.saveAndFlush(t);
        tenantIds.add(t.getId());
        return t;
    }

    private User usuario(Tenant tenant, String username, Role role) {
        TenantContext.setTenantId(tenant.getId());
        try {
            User u = new User();
            u.setUsername(username);
            u.setPasswordHash(passwordEncoder.encode(PASSWORD));
            u.setEmail(username + "@" + tenant.getSlug() + ".invalid");
            u.setRole(role);
            return users.saveAndFlush(u);
        } finally {
            TenantContext.clear();
        }
    }

    private String solicitudActivada(Tenant tenant) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        RegistrationRequest r = new RegistrationRequest();
        r.setId(UUID.randomUUID().toString());
        r.setBusinessName(tenant.getName());
        r.setNit("900123456-7");
        r.setPhone("3001234567");
        r.setContactName("Responsable");
        r.setContactEmail("responsable@example.invalid");
        r.setNormalizedEmail("responsable@example.invalid");
        r.setRequestedSlug(tenant.getSlug());
        r.setStatus(RegistrationRequestStatus.ACTIVATED);
        r.setBodyHash("0".repeat(64));
        r.setIdempotencyExpiresAt(now);
        r.setCreatedAt(now);
        r.setReservationExpiresAt(now);
        r.setReviewedAt(now);
        r.setActivatedTenantId(tenant.getId());
        requests.saveAndFlush(r);
        requestIds.add(r.getId());
        return r.getId();
    }

    /** Formato tenant vigente pero emitido antes de V11: sin claim authVersion. */
    private String tokenSinVersion(User u) {
        return Jwts.builder().subject(u.getUsername()).claim("tenantId", u.getTenantId())
                .claim("role", u.getRole().name())
                .issuer("servibox-tenant").audience().add("tenant").and().claim("identityType", "TENANT")
                .issuedAt(new Date(System.currentTimeMillis() - 2000))
                .expiration(new Date(System.currentTimeMillis() + 60000))
                .signWith(Keys.hmacShaKeyFor(tenantSecret.getBytes(StandardCharsets.UTF_8))).compact();
    }

    private MockHttpServletRequestBuilder plataforma(MockHttpServletRequestBuilder builder) {
        return builder.header("Authorization", "Bearer " + platformToken);
    }

    private MockHttpServletRequestBuilder suspenderPeticion(Long id, long version, String reason) {
        String motivo = reason == null ? "null" : "\"" + reason + "\"";
        return plataforma(post("/api/platform/tenants/" + id + "/suspend")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedVersion\":" + version + ",\"reason\":" + motivo + "}");
    }

    private org.springframework.test.web.servlet.ResultActions suspender(Long id, long version, String reason)
            throws Exception {
        return mvc.perform(suspenderPeticion(id, version, reason));
    }

    private MockHttpServletRequestBuilder reactivarPeticion(Long id, long version) {
        return plataforma(post("/api/platform/tenants/" + id + "/reactivate")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedVersion\":" + version + ",\"reason\":\"Pago recibido\"}");
    }

    private MockHttpServletRequestBuilder loginPeticion(String slug, String username) {
        return post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"tenantSlug\":\"" + slug + "\",\"username\":\"" + username + "\",\"password\":\""
                        + PASSWORD + "\"}");
    }

    private String login(String slug, String username) throws Exception {
        return JsonPath.read(leer(loginPeticion(slug, username), 200), "$.token");
    }

    private MockHttpServletRequestBuilder tenantGet(String token) {
        return get("/api/tenant").header("Authorization", "Bearer " + token);
    }

    private org.springframework.test.web.servlet.ResultActions tenantApi(String token) throws Exception {
        return mvc.perform(tenantGet(token));
    }

    private String leer(RequestBuilder peticion, int esperado) throws Exception {
        var respuesta = mvc.perform(peticion).andReturn().getResponse();
        String cuerpo = respuesta.getContentAsString(StandardCharsets.UTF_8);
        assertThat(respuesta.getStatus()).as(cuerpo).isEqualTo(esperado);
        return cuerpo;
    }

    private void error(RequestBuilder peticion, int esperado, String mensaje) throws Exception {
        String cuerpo = leer(peticion, esperado);
        assertThat((String) JsonPath.read(cuerpo, "$.error")).isEqualTo(mensaje);
        assertThat((Integer) JsonPath.read(cuerpo, "$.status")).isEqualTo(esperado);
    }

    private static long numero(String json, String path) {
        return ((Number) JsonPath.read(json, path)).longValue();
    }
}
