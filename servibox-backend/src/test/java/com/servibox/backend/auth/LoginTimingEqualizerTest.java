package com.servibox.backend.auth;

import com.servibox.backend.auth.entity.Role;
import com.servibox.backend.auth.entity.User;
import com.servibox.backend.auth.repository.UserRepository;
import com.servibox.backend.platform.PlatformAdminRepository;
import com.servibox.backend.platform.PlatformBootstrapService;
import com.servibox.backend.tenant.Tenant;
import com.servibox.backend.tenant.TenantContext;
import com.servibox.backend.tenant.TenantRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * BCrypt de relleno (tarea 51): sin pruebas de tiempo. Con espias se comprueba que cada camino
 * sin contrasena real paga exactamente una comparacion de relleno y una sola llamada a
 * PasswordEncoder.matches, que el camino normal no la paga (una sola comparacion, la real) y
 * que todos los fallos siguen siendo el mismo 401.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class LoginTimingEqualizerTest {

    private static final String UNAUTHORIZED = "{\"error\":\"Credenciales invalidas\",\"status\":401}";
    private static final String CLAVE = "clave-correcta-1";
    /** Exactamente 72 bytes: el maximo que BCrypt evalua. */
    private static final String LARGA = "a".repeat(72);

    @Autowired MockMvc mvc;
    @Autowired TenantRepository tenants;
    @Autowired UserRepository users;
    @Autowired PlatformAdminRepository admins;
    @Autowired PlatformBootstrapService bootstrap;
    @Autowired JdbcTemplate jdbc;
    @MockitoSpyBean LoginTimingEqualizer equalizer;
    @MockitoSpyBean PasswordEncoder passwordEncoder;

    @BeforeEach
    void seed() {
        Long activo = tenant("taller-relleno", true);
        usuario(activo, "activo", true);
        usuario(activo, "inactivo", false);
        usuario(activo, "largo", true, LARGA);
        Long suspendido = tenant("taller-relleno-susp", false);
        usuario(suspendido, "activo", true);
        bootstrap.create("relleno@example.invalid", CLAVE.toCharArray());
        jdbc.update("INSERT INTO administradores_plataforma (id_administrador, email, password_hash, activo)"
                + " VALUES (2, 'inactivo@example.invalid', ?, FALSE)", passwordEncoder.encode(CLAVE));
        Mockito.clearInvocations(equalizer, passwordEncoder);
    }

    @AfterEach
    void cleanup() {
        TenantContext.clear();
        jdbc.update("DELETE FROM usuarios WHERE id_tenant IN (SELECT id_tenant FROM tenants WHERE slug_tenant LIKE 'taller-relleno%')");
        jdbc.update("DELETE FROM tenants WHERE slug_tenant LIKE 'taller-relleno%'");
        admins.deleteAll();
    }

    private Long tenant(String slug, boolean active) {
        Tenant t = new Tenant();
        t.setName("Taller " + slug);
        t.setSlug(slug);
        t.setActive(Boolean.TRUE);
        Long id = tenants.save(t).getId();
        if (!active) {
            jdbc.update("UPDATE tenants SET activo_tenant = FALSE WHERE id_tenant = ?", id);
        }
        return id;
    }

    private void usuario(Long tenantId, String username, boolean active) {
        usuario(tenantId, username, active, CLAVE);
    }

    private void usuario(Long tenantId, String username, boolean active, String clave) {
        TenantContext.setTenantId(tenantId);
        try {
            User u = new User();
            u.setUsername(username);
            u.setPasswordHash(passwordEncoder.encode(clave));
            u.setEmail(username + "@example.invalid");
            u.setActive(active);
            u.setRole(Role.ADMIN);
            users.save(u);
        } finally {
            TenantContext.clear();
        }
    }

    private void tenantLogin(String slug, String username, String password, int expected) throws Exception {
        Mockito.clearInvocations(equalizer, passwordEncoder);
        var r = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content("""
                {"tenantSlug":"%s","username":"%s","password":"%s"}
                """.formatted(slug, username, password))).andExpect(status().is(expected));
        if (expected == 401) {
            r.andExpect(content().json(UNAUTHORIZED, true));
        }
    }

    private void platformLogin(String email, String password, int expected) throws Exception {
        Mockito.clearInvocations(equalizer, passwordEncoder);
        var r = mvc.perform(post("/api/platform/auth/login").contentType(MediaType.APPLICATION_JSON).content("""
                {"email":"%s","password":"%s"}
                """.formatted(email, password))).andExpect(status().is(expected));
        if (expected == 401) {
            r.andExpect(content().json(UNAUTHORIZED, true));
        }
    }

    private void unRelleno() {
        verify(equalizer, times(1)).compareDummy();
        verify(passwordEncoder, times(1)).matches(any(), anyString());
    }

    private void soloLaReal() {
        verify(equalizer, never()).compareDummy();
        verify(passwordEncoder, times(1)).matches(any(), anyString());
    }

    @Test
    void tenantSinContrasenaRealPagaUnRellenoYMismo401() throws Exception {
        tenantLogin("no-existe", "activo", CLAVE, 401);
        unRelleno();
        tenantLogin("taller-relleno-susp", "activo", CLAVE, 401);
        unRelleno();
        tenantLogin("taller-relleno", "no-existe", CLAVE, 401);
        unRelleno();
        tenantLogin("taller-relleno", "inactivo", CLAVE, 401);
        unRelleno();
    }

    /**
     * Tarea 52: con usuario activo y mas de 72 bytes, BCrypt no evaluaria la clave (Spring devuelve
     * false sin calcular); ahora paga el relleno. Nunca autentica, ni con los 72 primeros bytes correctos.
     */
    @Test
    void tenantConClaveDeMasDe72BytesPagaUnRellenoYNuncaAutentica() throws Exception {
        tenantLogin("taller-relleno", "largo", LARGA, 200);
        soloLaReal();
        tenantLogin("taller-relleno", "largo", LARGA + "b", 401);
        unRelleno();
        tenantLogin("taller-relleno", "activo", "ñ".repeat(40), 401);
        unRelleno();
    }

    @Test
    void tenantConContrasenaRealNoPagaRelleno() throws Exception {
        tenantLogin("taller-relleno", "activo", "clave-incorrecta", 401);
        soloLaReal();
        tenantLogin("taller-relleno", "activo", CLAVE, 200);
        soloLaReal();
    }

    @Test
    void plataformaSinContrasenaRealPagaUnRellenoYMismo401() throws Exception {
        platformLogin("no-existe@example.invalid", CLAVE, 401);
        unRelleno();
        platformLogin("inactivo@example.invalid", CLAVE, 401);
        unRelleno();
        // Mas de 72 bytes UTF-8 en menos de 72 caracteres: no se evalua la real, se paga el relleno.
        platformLogin("relleno@example.invalid", "ñ".repeat(40), 401);
        unRelleno();
    }

    @Test
    void plataformaConContrasenaRealNoPagaRelleno() throws Exception {
        platformLogin("relleno@example.invalid", "clave-incorrecta", 401);
        soloLaReal();
        platformLogin("relleno@example.invalid", CLAVE, 200);
        soloLaReal();
    }

    @Test
    void elHashDeRellenoTieneElMismoCostoQueElDeLasCuentas() {
        String real = passwordEncoder.encode(CLAVE);
        String relleno = (String) org.springframework.test.util.ReflectionTestUtils.getField(equalizer, "dummyHash");
        org.assertj.core.api.Assertions.assertThat(relleno).startsWith(real.substring(0, 7));
        org.assertj.core.api.Assertions.assertThat(passwordEncoder.matches("servibox-login-timing-equalizer", relleno))
                .isFalse();
    }
}
