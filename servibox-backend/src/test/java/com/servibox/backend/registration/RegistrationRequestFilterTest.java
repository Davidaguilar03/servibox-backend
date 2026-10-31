package com.servibox.backend.registration;

import com.jayway.jsonpath.JsonPath;
import com.servibox.backend.auth.JwtService;
import com.servibox.backend.auth.entity.Role;
import com.servibox.backend.auth.entity.User;
import com.servibox.backend.auth.repository.UserRepository;
import com.servibox.backend.platform.PlatformAdminRepository;
import com.servibox.backend.platform.PlatformBootstrapService;
import com.servibox.backend.platform.PlatformJwtService;
import com.servibox.backend.tenant.Tenant;
import com.servibox.backend.tenant.TenantContext;
import com.servibox.backend.tenant.TenantRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Filtros del listado de plataforma: nombre de serviteca, responsable y fecha de recepcion
 * (dia de America/Bogota sobre fecha_creacion en UTC). Datos insertados con fecha exacta.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "servibox.registration-requests.enabled=true")
class RegistrationRequestFilterTest {

    static final String PLATAFORMA = "/api/platform/registration-requests";
    // Bogota es UTC-5 sin horario de verano.
    static final String A = "00000000-0000-0000-0000-00000000000a"; // 15/10 00:00 Bogota
    static final String B = "00000000-0000-0000-0000-00000000000b"; // 15/10 23:59:59.999999 Bogota
    static final String C = "00000000-0000-0000-0000-00000000000c"; // 16/10 00:00 Bogota
    static final String D = "00000000-0000-0000-0000-00000000000d"; // 14/10 23:59:59 Bogota
    static final String E = "00000000-0000-0000-0000-00000000000e"; // misma fecha que C, id mayor

    @Autowired MockMvc mvc;
    @Autowired RegistrationRequestRepository requests;
    @Autowired TenantRepository tenants;
    @Autowired UserRepository users;
    @Autowired PlatformAdminRepository admins;
    @Autowired PlatformBootstrapService bootstrap;
    @Autowired PlatformJwtService platformJwt;
    @Autowired JwtService tenantJwt;

    private String platformToken;
    private Tenant tenant;
    private User user;

    @BeforeEach
    void seed() {
        bootstrap.create("filtros@example.invalid", "ephemeral-test-password".toCharArray());
        platformToken = platformJwt.generateToken(admins.findAll().getFirst());
        tenant = new Tenant();
        tenant.setName("Taller filtros");
        tenant.setSlug("taller-filtros");
        tenant.setNit("900111222-9");
        tenant = tenants.save(tenant);
        TenantContext.setTenantId(tenant.getId());
        try {
            user = new User();
            user.setUsername("admin-filtros");
            user.setPasswordHash("unused");
            user.setRole(Role.ADMIN);
            user = users.saveAndFlush(user);
        } finally {
            TenantContext.clear();
        }
        guardar(A, "Serviteca 100% Llantas", "Ana Rojas", "2026-10-15T05:00:00", RegistrationRequestStatus.PENDING);
        guardar(B, "Taller 1000 Llantas", "Luis_Perez", "2026-10-16T04:59:59.999999",
                RegistrationRequestStatus.CANCELLED);
        guardar(C, "Lubricantes Norte", "LuisXPerez", "2026-10-16T05:00:00", RegistrationRequestStatus.PENDING);
        guardar(D, "Baterias Sur", "María Gómez", "2026-10-15T04:59:59", RegistrationRequestStatus.REJECTED);
        guardar(E, "Lubricantes Este", "Pedro Diaz", "2026-10-16T05:00:00", RegistrationRequestStatus.PENDING);
    }

    @AfterEach
    void cleanup() {
        requests.deleteAll();
        users.deleteById(user.getId());
        tenants.deleteById(tenant.getId());
        admins.deleteAll();
    }

    private void guardar(String id, String nombre, String responsable, String creadaUtc,
            RegistrationRequestStatus estado) {
        LocalDateTime creada = LocalDateTime.parse(creadaUtc);
        String slug = "filtro-" + id.substring(id.length() - 1);
        RegistrationRequest r = new RegistrationRequest();
        r.setId(id);
        r.setBusinessName(nombre);
        r.setNit("900555666-1");
        r.setPhone("3001234567");
        r.setContactName(responsable);
        r.setContactEmail(slug + "@example.invalid");
        r.setNormalizedEmail(slug + "@example.invalid");
        r.setRequestedSlug(slug);
        r.setReservedSlug(estado == RegistrationRequestStatus.PENDING ? slug : null);
        r.setStatus(estado);
        r.setBodyHash("0".repeat(64));
        r.setIdempotencyExpiresAt(creada.plusDays(7));
        r.setCreatedAt(creada);
        r.setReservationExpiresAt(creada.plusDays(30));
        if (estado == RegistrationRequestStatus.REJECTED) {
            r.setRejectionReason("Duplicada");
        }
        requests.saveAndFlush(r);
    }

    private MockHttpServletRequestBuilder listar(String... parametros) {
        MockHttpServletRequestBuilder b = get(PLATAFORMA).header("Authorization", "Bearer " + platformToken);
        for (int i = 0; i < parametros.length; i += 2) {
            b.param(parametros[i], parametros[i + 1]);
        }
        return b;
    }

    private List<String> ids(String... parametros) throws Exception {
        String json = mvc.perform(listar(parametros)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        List<String> ids = JsonPath.read(json, "$.items[*].id");
        int total = JsonPath.read(json, "$.totalElements");
        assertThat(total).as("total filtrado").isEqualTo(ids.size());
        return ids;
    }

    private void error400(String mensaje, String... parametros) throws Exception {
        mvc.perform(listar(parametros)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(mensaje))
                .andExpect(jsonPath("$.status").value(400));
    }

    @Test
    void sinFiltrosConservaOrdenYDesempate() throws Exception {
        assertThat(ids()).containsExactly(E, C, B, A, D);
        // Parametros vacios o solo espacios equivalen a ausencia del filtro.
        assertThat(ids("tenantName", "", "contactName", "   ", "receivedFrom", "", "receivedTo", " "))
                .containsExactly(E, C, B, A, D);
    }

    @Test
    void textoParcialSinMayusculasYRecortado() throws Exception {
        assertThat(ids("tenantName", "  LLANTAS ")).containsExactly(B, A);
        assertThat(ids("tenantName", "lubri")).containsExactly(E, C);
        assertThat(ids("contactName", "ROJAS")).containsExactly(A);
        assertThat(ids("contactName", "maría gó")).containsExactly(D);
        assertThat(ids("tenantName", "no existe")).isEmpty();
    }

    @Test
    void porcentajeYGuionBajoSonLiterales() throws Exception {
        // Sin ESCAPE, "100%" coincidiria tambien con "1000" y "luis_" con "LuisX".
        assertThat(ids("tenantName", "100%")).containsExactly(A);
        assertThat(ids("contactName", "luis_")).containsExactly(B);
        assertThat(ids("tenantName", "%")).containsExactly(A);
        assertThat(ids("contactName", "_")).containsExactly(B);
        assertThat(ids("tenantName", "\\")).isEmpty();
    }

    @Test
    void fechasEnBogotaConLimitesDeDia() throws Exception {
        // 15/10 Bogota: desde 05:00 UTC del 15 incluido hasta 05:00 UTC del 16 excluido.
        assertThat(ids("receivedFrom", "2026-10-15", "receivedTo", "2026-10-15")).containsExactly(B, A);
        assertThat(ids("receivedFrom", "2026-10-16", "receivedTo", "2026-10-16")).containsExactly(E, C);
        assertThat(ids("receivedFrom", "2026-10-14", "receivedTo", "2026-10-14")).containsExactly(D);
        assertThat(ids("receivedFrom", "2026-10-17")).isEmpty();
    }

    @Test
    void fechasAbiertas() throws Exception {
        assertThat(ids("receivedFrom", "2026-10-15")).containsExactly(E, C, B, A);
        assertThat(ids("receivedTo", "2026-10-15")).containsExactly(B, A, D);
        assertThat(ids("receivedTo", "2026-10-14")).containsExactly(D);
    }

    @Test
    void filtrosCombinadosConAnd() throws Exception {
        assertThat(ids("status", "PENDING", "tenantName", "llantas")).containsExactly(A);
        assertThat(ids("status", "PENDING", "receivedFrom", "2026-10-15", "receivedTo", "2026-10-15"))
                .containsExactly(A);
        assertThat(ids("tenantName", "lubricantes", "contactName", "pedro", "receivedFrom", "2026-10-16"))
                .containsExactly(E);
        assertThat(ids("status", "CANCELLED", "tenantName", "lubricantes")).isEmpty();
        assertThat(ids("status", "REJECTED", "contactName", "maría", "receivedTo", "2026-10-14"))
                .containsExactly(D);
        assertThat(ids("status", "REJECTED", "receivedFrom", "2026-10-15")).isEmpty();
    }

    @Test
    void paginaDespuesDeFiltrarYTotalFiltrado() throws Exception {
        mvc.perform(listar("tenantName", "l", "receivedFrom", "2026-10-15", "size", "2", "page", "1"))
                .andExpect(status().isOk())
                // Coinciden E, C, B, A (D "Baterias Sur" no tiene "l"); pagina 1 de tamano 2.
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].id").value(B))
                .andExpect(jsonPath("$.items[1].id").value(A))
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.size").value(2))
                .andExpect(jsonPath("$.totalElements").value(4))
                .andExpect(jsonPath("$.totalPages").value(2))
                .andExpect(jsonPath("$.items[0].matches").doesNotExist());
        mvc.perform(listar("tenantName", "lubricantes", "size", "1", "page", "1"))
                .andExpect(jsonPath("$.items[0].id").value(C))
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.totalPages").value(2));
        mvc.perform(listar("tenantName", "no existe"))
                .andExpect(jsonPath("$.items.length()").value(0))
                .andExpect(jsonPath("$.totalElements").value(0))
                .andExpect(jsonPath("$.totalPages").value(0));
    }

    @Test
    void textoDeMasDe100CaracteresEs400() throws Exception {
        String cien = "a".repeat(100);
        assertThat(ids("tenantName", cien)).isEmpty();
        assertThat(ids("contactName", "  " + cien + "  ")).isEmpty();
        error400("El filtro de texto no puede superar 100 caracteres", "tenantName", cien + "b");
        error400("El filtro de texto no puede superar 100 caracteres", "contactName", cien + "b");
    }

    @Test
    void fechasInvalidasYRangoInvertidoSon400() throws Exception {
        String formato = "La fecha debe tener formato AAAA-MM-DD";
        error400(formato, "receivedFrom", "15/10/2026");
        error400(formato, "receivedFrom", "2026-13-01");
        error400(formato, "receivedTo", "2026-02-30");
        error400(formato, "receivedTo", "2026-1-5");
        error400(formato, "receivedFrom", "2026-10-15T00:00");
        error400("La fecha inicial no puede ser posterior a la final",
                "receivedFrom", "2026-10-16", "receivedTo", "2026-10-15");
        // Mismo dia en ambos extremos es valido.
        assertThat(ids("receivedFrom", "2026-10-15", "receivedTo", "2026-10-15")).hasSize(2);
    }

    @Test
    void soloIdentidadDePlataforma() throws Exception {
        String tenantToken = tenantJwt.generateToken(user);
        mvc.perform(get(PLATAFORMA).param("tenantName", "llantas").param("receivedFrom", "2026-10-15"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get(PLATAFORMA).param("tenantName", "llantas").param("receivedFrom", "2026-10-15")
                        .header("Authorization", "Bearer " + tenantToken))
                .andExpect(status().isUnauthorized());
    }
}
