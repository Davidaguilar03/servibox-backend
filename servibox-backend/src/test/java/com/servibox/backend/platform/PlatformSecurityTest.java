package com.servibox.backend.platform;

import com.jayway.jsonpath.JsonPath;
import com.servibox.backend.auth.JwtService;
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
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = "servibox.platform-test=true")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PlatformSecurityTest {
    private static final String PASSWORD = "ephemeral-test-password";
    @Autowired MockMvc mvc;
    @Autowired PlatformBootstrapService bootstrap;
    @Autowired PlatformAdminRepository admins;
    @Autowired PlatformJwtService platformJwt;
    @Autowired JwtService tenantJwt;
    @Autowired TenantRepository tenants;
    @Autowired UserRepository users;
    @Value("${servibox.platform.jwt.secret}") String platformSecret;
    @Value("${servibox.jwt.secret}") String tenantSecret;
    private User user;
    private Long tenantId;

    @BeforeEach
    void seed() {
        admins.deleteAll();
        bootstrap.create("  PLATFORM@example.invalid ", PASSWORD.toCharArray());
        Tenant tenant = new Tenant();
        tenant.setName("Platform security fixture");
        tenant.setSlug("platform-security-fixture");
        tenant.setActive(true);
        tenantId = tenants.save(tenant).getId();
        TenantContext.setTenantId(tenantId);
        try {
            user = new User();
            user.setUsername("tenant-fixture");
            user.setPasswordHash("unused");
            user.setRole(Role.ADMIN);
            user = users.saveAndFlush(user);
        } finally {
            TenantContext.clear();
        }
    }

    @AfterEach
    void cleanup() {
        TenantContext.clear();
        users.deleteById(user.getId());
        tenants.deleteById(tenantId);
        admins.deleteAll();
    }

    private PlatformAdmin admin() {
        return admins.findById(1L).orElseThrow();
    }

    private Map<String, Object> platformClaims() {
        Map<String, Object> claims = new HashMap<>();
        claims.put("sub", "1");
        claims.put("iss", "servibox-platform");
        claims.put("aud", java.util.List.of("platform"));
        claims.put("identityType", "PLATFORM");
        claims.put("authVersion", 0);
        claims.put("iat", new Date(System.currentTimeMillis() - 2000));
        claims.put("exp", new Date(System.currentTimeMillis() + 60000));
        return claims;
    }

    private Map<String, Object> legacyClaims() {
        Map<String, Object> claims = new HashMap<>();
        claims.put("sub", user.getUsername());
        claims.put("tenantId", tenantId);
        claims.put("role", "ADMIN");
        claims.put("iat", new Date(System.currentTimeMillis() - 2000));
        claims.put("exp", new Date(System.currentTimeMillis() + 60000));
        return claims;
    }

    private String sign(Map<String, Object> claims, String secret) {
        try {
            Map<String, Object> raw = new HashMap<>(claims);
            raw.replaceAll((k, v) -> v instanceof Date date ? date.getTime() / 1000 : v);
            var base64 = java.util.Base64.getUrlEncoder().withoutPadding();
            String input = base64.encodeToString("{\"alg\":\"HS256\"}".getBytes(StandardCharsets.UTF_8)) + "."
                    + base64.encodeToString(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsBytes(raw));
            var mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new javax.crypto.spec.SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return input + "." + base64.encodeToString(mac.doFinal(input.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new AssertionError(ex);
        }
    }

    private void rejected(String route, String token) throws Exception {
        mvc.perform(get(route).header("Authorization", "Bearer " + token)).andExpect(status().isUnauthorized());
        assertThat(TenantContext.getTenantId()).isNull();
    }

    @Test
    void bootstrapNormalizaGuardaHashYSinTenant() {
        PlatformAdmin a = admin();
        assertThat(a.getEmail()).isEqualTo("platform@example.invalid");
        assertThat(a.getPasswordHash()).startsWith("$2").doesNotContain(PASSWORD);
        assertThat(a.isActive()).isTrue();
        assertThat(a.getAuthVersion()).isZero();
        assertThat(TenantContext.getTenantId()).isNull();
    }

    @Test
    void bootstrapRepetidoNoReemplazaCuentaNiHash() {
        String hash = admin().getPasswordHash();
        assertThatThrownBy(() -> bootstrap.create("other@example.invalid", PASSWORD.toCharArray()))
                .isInstanceOf(IllegalStateException.class);
        assertThat(admin().getPasswordHash()).isEqualTo(hash);
        assertThat(admins.count()).isEqualTo(1);
    }

    @Test
    void bootstrapRechazaEntradasInvalidasSinCrearCuenta() {
        admins.deleteAll();
        for (String email : java.util.List.of("", "invalid", "a".repeat(151) + "@test.invalid")) {
            assertThatThrownBy(() -> bootstrap.create(email, PASSWORD.toCharArray())).isInstanceOf(IllegalArgumentException.class);
        }
        for (String password : java.util.List.of("short", " ".repeat(12), "ñ".repeat(37))) {
            assertThatThrownBy(() -> bootstrap.create("a@test.invalid", password.toCharArray())).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> bootstrap.create(null, null)).isInstanceOf(IllegalArgumentException.class);
        assertThat(admins.count()).isZero();
    }

    @Test
    void loginNormalizaYDtoExponeSoloIdentidadYToken() throws Exception {
        String json = mvc.perform(post("/api/platform/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\" PLATFORM@example.invalid \",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        Map<String, Object> body = JsonPath.read(json, "$");
        assertThat(body.keySet()).containsExactlyInAnyOrder("token", "admin");
        Map<String, Object> identity = JsonPath.read(json, "$.admin");
        assertThat(identity.keySet()).containsExactlyInAnyOrder("id", "email");
        String token = JsonPath.read(json, "$.token");
        var claims = platformJwt.validateAndParse(token);
        assertThat(claims.getExpiration().getTime() - claims.getIssuedAt().getTime()).isEqualTo(900000);
        mvc.perform(get("/api/platform/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andExpect(content().json("{\"id\":1,\"email\":\"platform@example.invalid\"}", true));
        assertThat(TenantContext.getTenantId()).isNull();
    }

    @Test
    void loginInvalidoOInactivoEsGenerico() throws Exception {
        for (String email : java.util.List.of("platform@example.invalid", "absent@example.invalid")) {
            mvc.perform(post("/api/platform/auth/login").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"email\":\"" + email + "\",\"password\":\"incorrect\"}"))
                    .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error").value("Credenciales invalidas"));
        }
        PlatformAdmin a = admin();
        a.setActive(false);
        admins.saveAndFlush(a);
        mvc.perform(post("/api/platform/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"platform@example.invalid\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error").value("Credenciales invalidas"));
    }

    @Test
    void tokenPrevioEsRechazadoAlDesactivarYAlIncrementarVersion() throws Exception {
        PlatformAdmin a = admin();
        String token = platformJwt.generateToken(a);
        a.setActive(false);
        admins.saveAndFlush(a);
        rejected("/api/platform/auth/me", token);
        a.setActive(true);
        a.setAuthVersion(1);
        admins.saveAndFlush(a);
        rejected("/api/platform/auth/me", token);
        mvc.perform(get("/api/platform/auth/me").header("Authorization", "Bearer " + platformJwt.generateToken(a)))
                .andExpect(status().isOk());
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/tenant", "/api/inventory/products", "/api/inventory/categories", "/api/inventory/tax-types",
            "/api/inventory/financial-settings", "/api/treasury/accounts", "/api/treasury/movements", "/api/treasury/balance",
            "/api/counterparties", "/api/sales", "/api/purchases", "/api/reporting/kpis", "/api/reporting/export"})
    void plataformaNoAccedeAFamiliasComerciales(String path) throws Exception {
        String token = platformJwt.generateToken(admin());
        for (String method : java.util.List.of("GET", "POST", "PUT", "DELETE")) {
            mvc.perform(request(org.springframework.http.HttpMethod.valueOf(method), path)
                            .header("Authorization", "Bearer " + token)).andExpect(status().isUnauthorized());
            assertThat(TenantContext.getTenantId()).isNull();
        }
    }

    @Test
    void tenantActualYHeredadoSoloEnSuAmbito() throws Exception {
        for (String token : java.util.List.of(tenantJwt.generateToken(user), sign(legacyClaims(), tenantSecret))) {
            rejected("/api/platform/auth/me", token);
            mvc.perform(get("/api/tenant").header("Authorization", "Bearer " + token)).andExpect(status().isOk());
            assertThat(TenantContext.getTenantId()).isNull();
        }
    }

    @Test
    void firmasTiposYAudienciasNoSonIntercambiables() throws Exception {
        rejected("/api/platform/auth/me", sign(platformClaims(), tenantSecret));
        rejected("/api/tenant", sign(legacyClaims(), platformSecret));
        for (String claim : java.util.List.of("identityType", "aud", "iss")) {
            Map<String, Object> p = platformClaims();
            p.put(claim, "tenant");
            rejected("/api/platform/auth/me", sign(p, platformSecret));
            Map<String, Object> t = legacyClaims();
            t.put(claim, "platform");
            rejected("/api/tenant", sign(t, tenantSecret));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"sub", "iss", "aud", "identityType", "iat", "exp", "authVersion"})
    void plataformaRechazaClaimsAusentesOMalTipados(String claim) throws Exception {
        Map<String, Object> p = platformClaims();
        p.remove(claim);
        rejected("/api/platform/auth/me", sign(p, platformSecret));
        p.put(claim, java.util.List.of("invalid"));
        rejected("/api/platform/auth/me", sign(p, platformSecret));
    }

    @ParameterizedTest
    @ValueSource(strings = {"sub", "tenantId", "role", "iat", "exp"})
    void tenantRechazaClaimsAusentesOMalTipados(String claim) throws Exception {
        Map<String, Object> t = legacyClaims();
        t.remove(claim);
        rejected("/api/tenant", sign(t, tenantSecret));
        t.put(claim, java.util.List.of("invalid"));
        rejected("/api/tenant", sign(t, tenantSecret));
    }

    @Test
    void rechazaExpiracionVersionFraccionalTenantIdYContextoResidual() throws Exception {
        Map<String, Object> p = platformClaims();
        p.put("exp", new Date(System.currentTimeMillis() - 1000));
        rejected("/api/platform/auth/me", sign(p, platformSecret));
        p = platformClaims();
        p.put("authVersion", 0.5);
        rejected("/api/platform/auth/me", sign(p, platformSecret));
        p = platformClaims();
        p.put("tenantId", tenantId);
        rejected("/api/platform/auth/me", sign(p, platformSecret));
        Map<String, Object> t = legacyClaims();
        t.put("tenantId", 1.5);
        rejected("/api/tenant", sign(t, tenantSecret));
        TenantContext.setTenantId(tenantId);
        mvc.perform(get("/api/platform/auth/me").header("Authorization", "Bearer " + platformJwt.generateToken(admin())))
                .andExpect(status().isOk());
        assertThat(TenantContext.getTenantId()).isNull();
        mvc.perform(get("/api/tenant")).andExpect(status().isUnauthorized());
    }

    @Test
    void rutasYMetodosNoEnumeradosSeDenieganSinSesion() throws Exception {
        String token = platformJwt.generateToken(admin());
        mvc.perform(post("/api/platform/auth/me").header("Authorization", "Bearer " + token)).andExpect(status().isForbidden());
        mvc.perform(get("/api/platform/unknown").header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden()).andExpect(content().json("{\"error\":\"Acceso denegado\",\"status\":403}", true));
        mvc.perform(get("/api/platform/auth/login")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/platform/auth/register")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/auth/unknown").header("Authorization", "Bearer " + tenantJwt.generateToken(user)))
                .andExpect(status().isForbidden());
        var result = mvc.perform(get("/api/health")).andExpect(status().isOk()).andReturn();
        assertThat(result.getRequest().getSession(false)).isNull();
        assertThat(result.getResponse().getHeader("Set-Cookie")).isNull();
    }

    @Test
    void tenantHeredadoRespetaExpiracionUsuarioActivoYRolActual() throws Exception {
        Map<String, Object> expired = legacyClaims();
        expired.put("exp", new Date(System.currentTimeMillis() - 1000));
        rejected("/api/tenant", sign(expired, tenantSecret));
        String token = sign(legacyClaims(), tenantSecret);
        user.setRole(Role.MECANICO);
        users.saveAndFlush(user);
        mvc.perform(put("/api/tenant").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
        user.setActive(false);
        users.saveAndFlush(user);
        rejected("/api/tenant", token);
    }
}
