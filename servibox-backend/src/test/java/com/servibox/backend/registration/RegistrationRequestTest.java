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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Solicitudes de registro con la compuerta abierta: envio, idempotencia, reserva de slug y revision. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "servibox.registration-requests.enabled=true")
class RegistrationRequestTest {

    static final String RUTA = "/api/registration-requests";
    static final String PLATAFORMA = "/api/platform/registration-requests";

    @Autowired MockMvc mvc;
    @Autowired RegistrationRequestRepository requests;
    @Autowired TenantRepository tenants;
    @Autowired UserRepository users;
    @Autowired PlatformAdminRepository admins;
    @Autowired PlatformBootstrapService bootstrap;
    @Autowired PlatformJwtService platformJwt;
    @Autowired JwtService tenantJwt;
    @Autowired JdbcTemplate jdbc;

    private String platformToken;
    private Tenant tenant;
    private User user;

    @BeforeEach
    void seed() {
        bootstrap.create("revisor@example.invalid", "ephemeral-test-password".toCharArray());
        platformToken = platformJwt.generateToken(admins.findById(1L).orElseThrow());
        tenant = new Tenant();
        tenant.setName("Taller existente");
        tenant.setSlug("taller-existente");
        tenant.setNit("900111222-3");
        tenant = tenants.save(tenant);
        TenantContext.setTenantId(tenant.getId());
        try {
            user = new User();
            user.setUsername("admin-existente");
            user.setPasswordHash("unused");
            user.setRole(Role.ADMIN);
            user = users.saveAndFlush(user);
        } finally {
            TenantContext.clear();
        }
    }

    @AfterEach
    void cleanup() {
        // Aprobar emite activacion y correo (4B): se borran antes por sus FK.
        jdbc.update("DELETE FROM correos_salientes");
        jdbc.update("DELETE FROM activaciones_registro");
        requests.deleteAll();
        users.deleteById(user.getId());
        tenants.deleteById(tenant.getId());
        admins.deleteAll();
    }

    static String cuerpo(String slug, String nit, String correo) {
        return """
                {"businessName":"Serviteca Uno","nit":"%s","phone":"300 123 4567","slug":"%s",
                 "adminFullName":"Ana Rojas","adminEmail":"%s","acceptTerms":true}
                """.formatted(nit, slug, correo);
    }

    static String cuerpo(String slug) {
        return cuerpo(slug, "900123456-7", "ana@uno.co");
    }

    private ResultActions enviar(String clave, String body) throws Exception {
        var builder = post(RUTA).contentType(MediaType.APPLICATION_JSON).content(body);
        if (clave != null) {
            builder.header("Idempotency-Key", clave);
        }
        return mvc.perform(builder);
    }

    private String crear(String clave, String slug) throws Exception {
        MvcResult r = enviar(clave, cuerpo(slug)).andExpect(status().isAccepted()).andReturn();
        return JsonPath.read(r.getResponse().getContentAsString(), "$.requestId");
    }

    private ResultActions revisar(String id, String accion, String body) throws Exception {
        return mvc.perform(post(PLATAFORMA + "/" + id + "/" + accion).header("Authorization", "Bearer " + platformToken)
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private void esperarError(String clave, String body, int status, String mensaje) throws Exception {
        long antes = requests.count();
        enviar(clave, body).andExpect(status().is(status))
                .andExpect(jsonPath("$.error").value(mensaje))
                .andExpect(jsonPath("$.status").value(status));
        assertThat(requests.count()).isEqualTo(antes);
    }

    @Test
    void solicitudValidaDevuelve202SinTenantUsuarioNiToken() throws Exception {
        long tenantsAntes = tenants.count();
        long usuariosAntes = users.count();
        MvcResult r = enviar("clave-0001", cuerpo("  Taller-Uno ")).andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.message").value("Solicitud recibida, pendiente de revision"))
                .andExpect(jsonPath("$.token").doesNotExist())
                .andExpect(jsonPath("$.tenant").doesNotExist())
                .andReturn();
        String id = JsonPath.read(r.getResponse().getContentAsString(), "$.requestId");

        RegistrationRequest guardada = requests.findById(id).orElseThrow();
        assertThat(guardada.getStatus()).isEqualTo(RegistrationRequestStatus.PENDING);
        assertThat(guardada.getRequestedSlug()).isEqualTo("taller-uno");
        assertThat(guardada.getReservedSlug()).isEqualTo("taller-uno");
        assertThat(guardada.getNormalizedEmail()).isEqualTo("ana@uno.co");
        assertThat(guardada.getVersion()).isZero();
        assertThat(guardada.getReservationExpiresAt())
                .isCloseTo(guardada.getCreatedAt().plusDays(7), within(1, ChronoUnit.SECONDS));
        assertThat(guardada.getIdempotencyExpiresAt())
                .isCloseTo(guardada.getCreatedAt().plusHours(48), within(1, ChronoUnit.SECONDS));
        assertThat(tenants.count()).isEqualTo(tenantsAntes);
        assertThat(users.count()).isEqualTo(usuariosAntes);
        assertThat(tenants.findBySlug("taller-uno")).isEmpty();
    }

    @Test
    void validacionesIgualesAlRegistroSinContrasena() throws Exception {
        esperarError(null, cuerpo("taller-v"), 400, "La clave de reintento es obligatoria");
        esperarError("   ", cuerpo("taller-v"), 400, "La clave de reintento es obligatoria");
        esperarError("corta", cuerpo("taller-v"), 400, "La clave de reintento no es valida");
        esperarError("clave con espacios", cuerpo("taller-v"), 400, "La clave de reintento no es valida");
        esperarError("clave-v001", cuerpo("taller-v").replace("\"acceptTerms\":true",
                "\"acceptTerms\":true,\"password\":\"clave-segura-1\""), 400, "La solicitud no admite contrasena");
        esperarError("clave-v001", cuerpo("taller-v").replace("true", "false"), 400,
                "Debe aceptar los terminos y condiciones");
        esperarError("clave-v001", cuerpo("taller-v").replace("\"Serviteca Uno\"", "\"  \""), 400,
                "El nombre comercial es obligatorio");
        esperarError("clave-v001", cuerpo("taller-v", "", "ana@uno.co"), 400, "El NIT es obligatorio");
        esperarError("clave-v001", cuerpo("taller-v", "900.123", "ana@uno.co"), 400,
                "El NIT solo admite digitos y un guion con el dígito de verificación, por ejemplo 900123456-7");
        esperarError("clave-v001", cuerpo("taller-v").replace("300 123 4567", ""), 400, "El telefono es obligatorio");
        esperarError("clave-v001", cuerpo("taller-v").replace("300 123 4567", "abc"), 400,
                "El telefono solo admite digitos, espacios, +, - y paréntesis");
        esperarError("clave-v001", cuerpo("ta"), 400,
                "El identificador debe tener de 3 a 30 caracteres con solo minúsculas, números y guiones");
        esperarError("clave-v001", cuerpo("-taller"), 400, "El identificador no puede empezar ni terminar en guion");
        esperarError("clave-v001", cuerpo("ADMIN"), 400, "El identificador admin esta reservado");
        esperarError("clave-v001", cuerpo("taller-v").replace("Ana Rojas", ""), 400,
                "El nombre completo del administrador es obligatorio");
        esperarError("clave-v001", cuerpo("taller-v", "900123456-7", "no-es-correo"), 400,
                "El correo del administrador no es valido");
    }

    @Test
    void mismaClaveYMismoCuerpoDevuelveElMismoAcuse() throws Exception {
        String id = crear("clave-idem-1", "taller-idem");
        enviar("clave-idem-1", cuerpo("taller-idem")).andExpect(status().isAccepted())
                .andExpect(jsonPath("$.requestId").value(id))
                .andExpect(jsonPath("$.status").value("PENDING"));
        assertThat(requests.count()).isEqualTo(1);
    }

    @Test
    void mismaClaveConCuerpoDistintoEs409() throws Exception {
        crear("clave-idem-2", "taller-idem");
        esperarError("clave-idem-2", cuerpo("taller-idem", "900123456-8", "ana@uno.co"), 409,
                "La clave de reintento ya se uso con otros datos");
        esperarError("clave-idem-2", cuerpo("otro-taller"), 409, "La clave de reintento ya se uso con otros datos");
    }

    @Test
    void claveVencidaSeLiberaYCuentaComoNueva() throws Exception {
        String id = crear("clave-vieja", "taller-a");
        RegistrationRequest vieja = requests.findById(id).orElseThrow();
        vieja.setIdempotencyExpiresAt(LocalDateTime.now(ZoneOffset.UTC).minusMinutes(1));
        requests.saveAndFlush(vieja);

        String nueva = crear("clave-vieja", "taller-b");
        assertThat(nueva).isNotEqualTo(id);
        assertThat(requests.findById(id).orElseThrow().getIdempotencyKey()).isNull();
        assertThat(requests.findById(nueva).orElseThrow().getIdempotencyKey()).isEqualTo("clave-vieja");
    }

    @Test
    void slugDeUnTenantExistenteNoEstaDisponibleSinImportarMayusculas() throws Exception {
        esperarError("clave-t001", cuerpo("taller-existente"), 409, "El codigo solicitado no esta disponible");
        esperarError("clave-t002", cuerpo("Taller-Existente"), 409, "El codigo solicitado no esta disponible");
    }

    @Test
    void slugReservadoPorOtraSolicitudVigenteEs409YRechazarLoLibera() throws Exception {
        String id = crear("clave-r001", "taller-reserva");
        esperarError("clave-r002", cuerpo("TALLER-RESERVA", "800999888-1", "otro@dos.co"), 409,
                "El codigo solicitado no esta disponible");

        revisar(id, "reject", "{\"expectedVersion\":0,\"reason\":\"Datos incompletos\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("REJECTED"));
        assertThat(requests.findById(id).orElseThrow().getReservedSlug()).isNull();

        String otra = crear("clave-r003", "taller-reserva");
        assertThat(requests.findById(otra).orElseThrow().getReservedSlug()).isEqualTo("taller-reserva");
    }

    @Test
    void reservaPendienteVencidaSeCancelaAlPedirElMismoSlug() throws Exception {
        String id = crear("clave-e001", "taller-vence");
        RegistrationRequest vieja = requests.findById(id).orElseThrow();
        vieja.setReservationExpiresAt(LocalDateTime.now(ZoneOffset.UTC).minusMinutes(1));
        requests.saveAndFlush(vieja);

        mvc.perform(get(PLATAFORMA + "/" + id).header("Authorization", "Bearer " + platformToken))
                .andExpect(status().isOk()).andExpect(jsonPath("$.reservationExpired").value(true));
        revisar(id, "approve", "{\"expectedVersion\":1}").andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("La solicitud ya no permite esta operacion"));

        String nueva = crear("clave-e002", "taller-vence");
        RegistrationRequest cancelada = requests.findById(id).orElseThrow();
        assertThat(cancelada.getStatus()).isEqualTo(RegistrationRequestStatus.CANCELLED);
        assertThat(cancelada.getReservedSlug()).isNull();
        assertThat(cancelada.getRequestedSlug()).isEqualTo("taller-vence");
        assertThat(requests.findById(nueva).orElseThrow().getReservedSlug()).isEqualTo("taller-vence");
    }

    @Test
    void conLasSolicitudesAbiertasElRegistroInmediatoQuedaCerrado() throws Exception {
        long tenantsAntes = tenants.count();
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content("""
                        {"businessName":"Serviteca","nit":"900123456-7","phone":"3001234567",
                         "slug":"taller-inmediato","adminFullName":"Ana Rojas","adminEmail":"ana@uno.co",
                         "adminUsername":"admin","password":"clave-segura-1","acceptTerms":true}
                        """))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("El registro de nuevas servitecas esta deshabilitado"));
        assertThat(tenants.count()).isEqualTo(tenantsAntes);
    }

    @Test
    void listadoPaginadoConFiltroYDetalleSinClaveNiHash() throws Exception {
        String a = crear("clave-l001", "taller-l1");
        crear("clave-l002", "taller-l2");
        enviar("clave-l003", cuerpo("taller-l3", "900111222-3", "ANA@uno.co ")).andExpect(status().isAccepted());
        // Dos solicitudes creadas en el mismo tic del reloj empatan en fecha_creacion y el
        // desempate es por UUID aleatorio: se fija l3 un segundo despues de l2.
        LocalDateTime l2 = jdbc.queryForObject(
                "SELECT fecha_creacion FROM solicitudes_registro WHERE slug_solicitado = 'taller-l2'", LocalDateTime.class);
        jdbc.update("UPDATE solicitudes_registro SET fecha_creacion = ? WHERE slug_solicitado = 'taller-l3'",
                l2.plusSeconds(1));
        revisar(a, "reject", "{\"expectedVersion\":0,\"reason\":\"Duplicada\"}").andExpect(status().isOk());

        mvc.perform(get(PLATAFORMA).param("status", "PENDING").param("size", "1")
                        .header("Authorization", "Bearer " + platformToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].slug").value("taller-l3"))
                .andExpect(jsonPath("$.items[0].matches").doesNotExist())
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(1))
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.totalPages").value(2));
        mvc.perform(get(PLATAFORMA).header("Authorization", "Bearer " + platformToken))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(3));
        mvc.perform(get(PLATAFORMA).param("status", "REJECTED").header("Authorization", "Bearer " + platformToken))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.items[0].rejectionReason").value("Duplicada"));

        String l3 = requests.findAll().stream().filter(r -> r.getRequestedSlug().equals("taller-l3"))
                .findFirst().orElseThrow().getId();
        mvc.perform(get(PLATAFORMA + "/" + l3).header("Authorization", "Bearer " + platformToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.contactEmail").value("ANA@uno.co"))
                .andExpect(jsonPath("$.version").value(0))
                .andExpect(jsonPath("$.reservationExpired").value(false))
                .andExpect(jsonPath("$.matches.requestsWithSameNit").value(0))
                .andExpect(jsonPath("$.matches.requestsWithSameEmail").value(2))
                .andExpect(jsonPath("$.matches.tenantsWithSameNit").value(1))
                .andExpect(jsonPath("$.idempotencyKey").doesNotExist())
                .andExpect(jsonPath("$.bodyHash").doesNotExist())
                .andExpect(jsonPath("$.password").doesNotExist());
    }

    @Test
    void filtrosYPaginacionInvalidosSon400YIdInexistente404() throws Exception {
        mvc.perform(get(PLATAFORMA).param("status", "ARCHIVED").header("Authorization", "Bearer " + platformToken))
                .andExpect(status().isBadRequest());
        mvc.perform(get(PLATAFORMA).param("size", "101").header("Authorization", "Bearer " + platformToken))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("Paginacion invalida"));
        mvc.perform(get(PLATAFORMA).param("page", "-1").header("Authorization", "Bearer " + platformToken))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("Paginacion invalida"));
        mvc.perform(get(PLATAFORMA + "/no-existe").header("Authorization", "Bearer " + platformToken))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.error").value("Solicitud no encontrada"));
        revisar("no-existe", "approve", "{\"expectedVersion\":0}").andExpect(status().isNotFound());
    }

    @Test
    void aprobarNoCreaTenantNiUsuarioYUnReintentoNoCambiaNada() throws Exception {
        String id = crear("clave-a001", "taller-aprobado");
        long tenantsAntes = tenants.count();
        long usuariosAntes = users.count();

        revisar(id, "approve", "{}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("La version esperada es obligatoria"));
        MvcResult r = revisar(id, "approve", "{\"expectedVersion\":0}").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.reviewerId").value(1))
                .andReturn();
        RegistrationRequest aprobada = requests.findById(id).orElseThrow();
        assertThat(aprobada.getReservedSlug()).isEqualTo("taller-aprobado");
        assertThat(aprobada.getReservationExpiresAt())
                .isCloseTo(aprobada.getReviewedAt().plusDays(30), within(1, ChronoUnit.SECONDS));

        revisar(id, "approve", "{\"expectedVersion\":0}").andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.reviewedAt").value(JsonPath.<String>read(
                        r.getResponse().getContentAsString(), "$.reviewedAt")));
        revisar(id, "approve", "{\"expectedVersion\":1}").andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("La solicitud ya no permite esta operacion"));
        revisar(id, "reject", "{\"expectedVersion\":1,\"reason\":\"Tarde\"}").andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("La solicitud ya no permite esta operacion"));

        assertThat(requests.findById(id).orElseThrow().getVersion()).isEqualTo(1);
        assertThat(tenants.count()).isEqualTo(tenantsAntes);
        assertThat(users.count()).isEqualTo(usuariosAntes);
        assertThat(tenants.findBySlug("taller-aprobado")).isEmpty();
        esperarError("clave-a002", cuerpo("taller-aprobado"), 409, "El codigo solicitado no esta disponible");
    }

    @Test
    void expectedVersionViejoEs409SinCambiarElEstado() throws Exception {
        String id = crear("clave-s001", "taller-version");
        revisar(id, "approve", "{\"expectedVersion\":5}").andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("El recurso cambio; actualiza e intenta de nuevo"));
        revisar(id, "reject", "{\"expectedVersion\":0,\"reason\":\"Primero\"}").andExpect(status().isOk());
        revisar(id, "approve", "{\"expectedVersion\":0}").andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("El recurso cambio; actualiza e intenta de nuevo"));
        revisar(id, "reject", "{\"expectedVersion\":0,\"reason\":\"Otro motivo\"}").andExpect(status().isConflict());
        revisar(id, "reject", "{\"expectedVersion\":0,\"reason\":\"Primero\"}").andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1));

        RegistrationRequest r = requests.findById(id).orElseThrow();
        assertThat(r.getStatus()).isEqualTo(RegistrationRequestStatus.REJECTED);
        assertThat(r.getRejectionReason()).isEqualTo("Primero");
        assertThat(r.getVersion()).isEqualTo(1);
    }

    @Test
    void rechazarExigeMotivo() throws Exception {
        String id = crear("clave-m001", "taller-motivo");
        revisar(id, "reject", "{\"expectedVersion\":0}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("El motivo de rechazo es obligatorio"));
        revisar(id, "reject", "{\"expectedVersion\":0,\"reason\":\"   \"}").andExpect(status().isBadRequest());
        revisar(id, "reject", "{\"expectedVersion\":0,\"reason\":\"" + "x".repeat(501) + "\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("El motivo de rechazo no puede superar 500 caracteres"));
        assertThat(requests.findById(id).orElseThrow().getStatus()).isEqualTo(RegistrationRequestStatus.PENDING);
    }

    /** Las defensas de la base, sin pasar por el servicio. */
    @Test
    void laBaseImpideDosReservasVigentesYEstadosIncoherentes() {
        String insert = "INSERT INTO solicitudes_registro (id_solicitud, nombre_comercial, nit, telefono,"
                + " nombre_responsable, correo_responsable, correo_normalizado, slug_solicitado, slug_reservado,"
                + " estado, hash_cuerpo, idempotencia_expira_en, fecha_creacion, reserva_expira_en, motivo_rechazo,"
                + " nombre_comercial_busqueda, nombre_responsable_busqueda)"
                + " VALUES (?, 'n', '1', '1', 'n', 'a@b.co', 'a@b.co', ?, ?, ?, 'h',"
                + " CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, ?, 'n', 'n')";
        jdbc.update(insert, "db-1", "taller-db", "taller-db", "PENDING", null);
        jdbc.update(insert, "db-2", "taller-db", null, "REJECTED", "m");
        jdbc.update(insert, "db-3", "taller-db", null, "CANCELLED", null);

        assertThatThrownBy(() -> jdbc.update(insert, "db-4", "taller-db", "taller-db", "APPROVED", null))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(insert, "db-5", "otro-db", null, "PENDING", null))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(insert, "db-6", "otro-db", "otro-db", "REJECTED", "m"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(insert, "db-7", "otro-db", null, "REJECTED", null))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(insert, "db-8", "otro-db", "taller-db", "PENDING", null))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(insert, "db-9", "Otro-DB", "Otro-DB", "PENDING", null))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(insert, "db-10", "otro-db", "otro-db", "ACTIVATED", null))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(requests.count()).isEqualTo(3);
    }

    @Test
    void autoridadesDeLasRutasDePlataforma() throws Exception {
        String id = crear("clave-p001", "taller-permisos");
        String tenantToken = tenantJwt.generateToken(user);
        String[] lecturas = {PLATAFORMA, PLATAFORMA + "/" + id};
        String[] revisiones = {PLATAFORMA + "/" + id + "/approve", PLATAFORMA + "/" + id + "/reject"};

        for (String ruta : lecturas) {
            mvc.perform(get(ruta)).andExpect(status().isUnauthorized());
            // Resultado real de la cadena de plataforma con un JWT tenant: no autentica, 401.
            mvc.perform(get(ruta).header("Authorization", "Bearer " + tenantToken))
                    .andExpect(status().isUnauthorized());
        }
        for (String ruta : revisiones) {
            mvc.perform(post(ruta).contentType(MediaType.APPLICATION_JSON).content("{\"expectedVersion\":0}"))
                    .andExpect(status().isUnauthorized());
            mvc.perform(post(ruta).header("Authorization", "Bearer " + tenantToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"expectedVersion\":0,\"reason\":\"x\"}"))
                    .andExpect(status().isUnauthorized());
        }
        // Metodos y rutas de plataforma no abiertos: denegados aun con identidad de plataforma.
        mvc.perform(delete(PLATAFORMA + "/" + id).header("Authorization", "Bearer " + platformToken))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value("Acceso denegado"));
        mvc.perform(post(PLATAFORMA).header("Authorization", "Bearer " + platformToken)
                        .contentType(MediaType.APPLICATION_JSON).content(cuerpo("taller-x")))
                .andExpect(status().isForbidden());
        mvc.perform(post(PLATAFORMA + "/" + id + "/cancel").header("Authorization", "Bearer " + platformToken))
                .andExpect(status().isForbidden());
        // GET /api/platform/tenants se abrio en 5A; el borrado (5B) sigue cerrado.
        mvc.perform(delete("/api/platform/tenants/1").header("Authorization", "Bearer " + platformToken))
                .andExpect(status().isForbidden());
        // La plataforma no entra a las rutas comerciales.
        mvc.perform(get("/api/inventory/products").header("Authorization", "Bearer " + platformToken))
                .andExpect(status().isUnauthorized());

        RegistrationRequest r = requests.findById(id).orElseThrow();
        assertThat(r.getStatus()).isEqualTo(RegistrationRequestStatus.PENDING);
        assertThat(r.getVersion()).isZero();
    }
}
