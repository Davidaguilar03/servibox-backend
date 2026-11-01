package com.servibox.backend.registration;

import com.jayway.jsonpath.JsonPath;
import com.servibox.backend.mail.FakeMailTransport;
import com.servibox.backend.mail.OutboxWorker;
import com.servibox.backend.platform.PlatformAdminRepository;
import com.servibox.backend.platform.PlatformBootstrapService;
import com.servibox.backend.platform.PlatformJwtService;
import com.servibox.backend.tenant.Tenant;
import com.servibox.backend.tenant.TenantRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
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
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Activacion (4B): aprobar encola el correo, el worker lo entrega al transporte falso y el enlace crea el tenant. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(FakeMailTransport.class)
@TestPropertySource(properties = "servibox.registration-requests.enabled=true")
@ExtendWith(OutputCaptureExtension.class)
class RegistrationActivationTest {

    static final String ACTIVAR = "/api/registration-requests/activate";
    private static final Pattern TOKEN = Pattern.compile("/activar#token=([A-Za-z0-9_-]{43})");

    @Autowired MockMvc mvc;
    @Autowired RegistrationRequestRepository requests;
    @Autowired RegistrationActivationRepository activations;
    @Autowired TenantRepository tenants;
    @Autowired PlatformAdminRepository admins;
    @Autowired PlatformBootstrapService bootstrap;
    @Autowired PlatformJwtService platformJwt;
    @Autowired OutboxWorker worker;
    @Autowired RegistrationActivationService activationService;
    @Autowired FakeMailTransport transport;
    @Autowired JdbcTemplate jdbc;

    private String platformToken;

    @BeforeEach
    void seed() {
        transport.reset();
        bootstrap.create("revisor@example.invalid", "ephemeral-test-password".toCharArray());
        platformToken = platformJwt.generateToken(admins.findById(1L).orElseThrow());
    }

    /** Los tenants creados quedan (slugs unicos por prueba); lo demas se borra en orden de FK. */
    @AfterEach
    void cleanup() {
        limpiar(jdbc, requests, admins);
    }

    static void limpiar(JdbcTemplate jdbc, RegistrationRequestRepository requests, PlatformAdminRepository admins) {
        // El reenvio deja auditoria con FK al administrador (V12): se borra antes que el administrador.
        jdbc.update("DELETE FROM auditoria_plataforma");
        jdbc.update("DELETE FROM correos_salientes");
        jdbc.update("DELETE FROM activaciones_registro");
        requests.deleteAll();
        admins.deleteAll();
    }

    static String activacion(String token, String username, String password) {
        return """
                {"token":"%s","username":"%s","password":"%s"}
                """.formatted(token, username, password);
    }

    private String aprobada(String slug) throws Exception {
        MvcResult r = mvc.perform(post(RegistrationRequestTest.RUTA).header("Idempotency-Key", "clave-" + slug)
                .contentType(MediaType.APPLICATION_JSON).content(RegistrationRequestTest.cuerpo(slug)))
                .andExpect(status().isAccepted()).andReturn();
        String id = JsonPath.read(r.getResponse().getContentAsString(), "$.requestId");
        plataforma(id, "approve", "{\"expectedVersion\":0}").andExpect(status().isOk());
        return id;
    }

    private ResultActions plataforma(String id, String accion, String body) throws Exception {
        var builder = post(RegistrationRequestTest.PLATAFORMA + "/" + id + "/" + accion)
                .header("Authorization", "Bearer " + platformToken);
        if (body != null) {
            builder.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        return mvc.perform(builder);
    }

    private ResultActions activar(String token, String username) throws Exception {
        return mvc.perform(post(ACTIVAR).contentType(MediaType.APPLICATION_JSON)
                .content(activacion(token, username, "clave-segura-1")));
    }

    /** Entrega lo pendiente y devuelve el token del ultimo correo aceptado. */
    private String entregarYLeerToken() {
        worker.processBatch();
        assertThat(transport.sent()).isNotEmpty();
        Matcher m = TOKEN.matcher(transport.sent().getLast().body());
        assertThat(m.find()).isTrue();
        return m.group(1);
    }

    private void esperarError(ResultActions r, int status, String mensaje) throws Exception {
        r.andExpect(status().is(status)).andExpect(jsonPath("$.error").value(mensaje))
                .andExpect(jsonPath("$.status").value(status));
    }

    /** Mueve atras la emision de los enlaces de la solicitud para pasar el intervalo de reenvio. */
    private void retrasarEmision(String id) {
        jdbc.update("UPDATE activaciones_registro SET fecha_creacion = ? WHERE id_solicitud = ? AND fecha_creacion > ?",
                ahora().minusMinutes(2), id, ahora().minusMinutes(2));
    }

    private static LocalDateTime ahora() {
        return LocalDateTime.now(ZoneOffset.UTC);
    }

    @Test
    void cicloCompletoCreaTenantConAdminQuePuedeEntrar() throws Exception {
        String id = aprobada("taller-ciclo");
        RegistrationRequest aprobadaSinTenant = requests.findById(id).orElseThrow();
        assertThat(aprobadaSinTenant.getActivatedTenantId()).isNull();
        assertThat(tenants.findBySlug("taller-ciclo")).isEmpty();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM correos_salientes WHERE estado = 'PENDING'", Long.class))
                .isEqualTo(1);

        String token = entregarYLeerToken();
        FakeMailTransport.Sent correo = transport.sent().getFirst();
        assertThat(correo.to()).isEqualTo("ana@uno.co");
        assertThat(correo.subject()).isEqualTo("Activa tu cuenta de ServiBox");
        assertThat(correo.body()).contains("https://app.example.invalid/activar#token=" + token)
                .contains("Serviteca Uno").contains("Ana Rojas");
        Map<String, Object> fila = jdbc.queryForMap("SELECT estado, payload_cifrado, fecha_envio FROM correos_salientes");
        assertThat(fila.get("ESTADO")).isEqualTo("SENT");
        assertThat(fila.get("PAYLOAD_CIFRADO")).isNull();
        assertThat(fila.get("FECHA_ENVIO")).isNotNull();

        activar(token, "dueno").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVATED"))
                .andExpect(jsonPath("$.tenantSlug").value("taller-ciclo"))
                .andExpect(jsonPath("$.message").value("Cuenta activada; inicia sesion"))
                .andExpect(jsonPath("$.token").doesNotExist());

        Tenant tenant = tenants.findBySlug("taller-ciclo").orElseThrow();
        RegistrationRequest r = requests.findById(id).orElseThrow();
        assertThat(r.getStatus()).isEqualTo(RegistrationRequestStatus.ACTIVATED);
        assertThat(r.getReservedSlug()).isNull();
        assertThat(r.getActivatedTenantId()).isEqualTo(tenant.getId());
        assertThat(tenant.getNit()).isEqualTo("900123456-7");
        assertThat(activations.findByRequestIdOrderByGenerationDesc(id).getFirst().getConsumedAt()).isNotNull();
        // Mismo provisionamiento que el registro: ADMIN, IVA, tres categorias, Caja General y Consumidor final.
        assertThat(jdbc.queryForObject("SELECT rol FROM usuarios WHERE id_tenant = ?", String.class, tenant.getId()))
                .isEqualTo("ADMIN");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tipos_impuesto WHERE id_tenant = ?", Long.class,
                tenant.getId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM categoria_productos WHERE id_tenant = ?", Long.class,
                tenant.getId())).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT nombre_cuenta FROM cuentas WHERE id_tenant = ?", String.class,
                tenant.getId())).isEqualTo("Caja General");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM terceros WHERE id_tenant = ?", Long.class,
                tenant.getId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT password_hash FROM usuarios WHERE id_tenant = ?", String.class,
                tenant.getId())).startsWith("$2").doesNotContain("clave-segura-1");

        MvcResult login = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tenantSlug\":\"taller-ciclo\",\"username\":\"dueno\",\"password\":\"clave-segura-1\"}"))
                .andExpect(status().isOk()).andReturn();
        String jwt = JsonPath.read(login.getResponse().getContentAsString(), "$.token");
        mvc.perform(get("/api/tenant").header("Authorization", "Bearer " + jwt))
                .andExpect(status().isOk()).andExpect(jsonPath("$.slug").value("taller-ciclo"));
    }

    @Test
    void tokenAlteradoMalFormadoOAusenteNoEsValido() throws Exception {
        aprobada("taller-alterado");
        String token = entregarYLeerToken();
        char ultimo = token.charAt(42);
        String alterado = token.substring(0, 42) + (ultimo == 'A' ? 'B' : 'A');
        esperarError(activar(alterado, "dueno"), 400, ActivationException.NO_VALIDO);
        esperarError(activar("corto", "dueno"), 400, ActivationException.NO_VALIDO);
        esperarError(mvc.perform(post(ACTIVAR).contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"dueno\",\"password\":\"clave-segura-1\"}")), 400, ActivationException.NO_VALIDO);
        assertThat(tenants.findBySlug("taller-alterado")).isEmpty();
        activar(token, "dueno").andExpect(status().isOk());
    }

    @Test
    void tokenUsadoResponde409YNoCreaOtroTenant() throws Exception {
        aprobada("taller-usado");
        String token = entregarYLeerToken();
        activar(token, "dueno").andExpect(status().isOk());
        long antes = tenants.count();
        esperarError(activar(token, "otro"), 409, ActivationException.USADO);
        assertThat(tenants.count()).isEqualTo(antes);
    }

    @Test
    void tokenVencidoResponde410() throws Exception {
        String id = aprobada("taller-vencido");
        String token = entregarYLeerToken();
        jdbc.update("UPDATE activaciones_registro SET expira_en = ? WHERE id_solicitud = ?", ahora().minusSeconds(1), id);
        esperarError(activar(token, "dueno"), 410, ActivationException.VENCIDO);
        assertThat(tenants.findBySlug("taller-vencido")).isEmpty();
        assertThat(requests.findById(id).orElseThrow().getStatus()).isEqualTo(RegistrationRequestStatus.APPROVED);
    }

    @Test
    void reenviarRevocaElEnlaceAnteriorYCancelaSuCorreoPendiente() throws Exception {
        String id = aprobada("taller-revocado");
        String viejo = entregarYLeerToken();
        retrasarEmision(id);
        plataforma(id, "resend-activation", null).andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("APPROVED"));
        // Un tercer enlace con el segundo aun sin enviar: el pendiente queda cancelado sin cuerpo.
        retrasarEmision(id);
        plataforma(id, "resend-activation", null).andExpect(status().isAccepted());
        assertThat(jdbc.queryForList("SELECT estado, ultimo_error, payload_cifrado FROM correos_salientes"
                + " ORDER BY id_correo")).extracting(f -> f.get("ESTADO") + "/" + f.get("ULTIMO_ERROR") + "/"
                + (f.get("PAYLOAD_CIFRADO") == null ? "sin" : "con"))
                .containsExactly("SENT/null/sin", "FAILED/REVOCADO/sin", "PENDING/null/con");

        esperarError(activar(viejo, "dueno"), 410, ActivationException.VENCIDO);
        String nuevo = entregarYLeerToken();
        assertThat(nuevo).isNotEqualTo(viejo);
        assertThat(transport.sent()).hasSize(2);
        assertThat(activations.findByRequestIdOrderByGenerationDesc(id)).extracting(RegistrationActivation::getGeneration)
                .containsExactly(3, 2, 1);
        activar(nuevo, "dueno").andExpect(status().isOk());
    }

    @Test
    void elTokenSoloActivaSuSolicitudYExigeReservaVigente() throws Exception {
        String a = aprobada("taller-propio-a");
        String tokenA = entregarYLeerToken();
        String b = aprobada("taller-propio-b");
        String tokenB = entregarYLeerToken();

        activar(tokenA, "dueno").andExpect(status().isOk()).andExpect(jsonPath("$.tenantSlug").value("taller-propio-a"));
        assertThat(requests.findById(b).orElseThrow().getStatus()).isEqualTo(RegistrationRequestStatus.APPROVED);
        assertThat(tenants.findBySlug("taller-propio-b")).isEmpty();
        assertThat(requests.findById(a).orElseThrow().getActivatedTenantId())
                .isEqualTo(tenants.findBySlug("taller-propio-a").orElseThrow().getId());

        jdbc.update("UPDATE solicitudes_registro SET reserva_expira_en = ? WHERE id_solicitud = ?",
                ahora().minusSeconds(1), b);
        esperarError(activar(tokenB, "dueno"), 409, RegistrationRequestConflictException.ESTADO_INVALIDO);
        assertThat(tenants.findBySlug("taller-propio-b")).isEmpty();
    }

    @Test
    void slugTomadoPorUnTenantAntesDeActivarResponde409SinCrearNada() throws Exception {
        String id = aprobada("taller-tomado");
        String token = entregarYLeerToken();
        Tenant otro = new Tenant();
        otro.setName("Otro");
        otro.setSlug("taller-tomado");
        tenants.save(otro);
        long antes = tenants.count();
        esperarError(activar(token, "dueno"), 409, RegistrationRequestConflictException.SLUG_NO_DISPONIBLE);
        assertThat(tenants.count()).isEqualTo(antes);
        assertThat(requests.findById(id).orElseThrow().getStatus()).isEqualTo(RegistrationRequestStatus.APPROVED);
        assertThat(activations.findByRequestIdOrderByGenerationDesc(id).getFirst().getConsumedAt()).isNull();
    }

    @Test
    void usuarioOContrasenaInvalidosUsanLosMensajesDelRegistroYNoConsumenElToken() throws Exception {
        aprobada("taller-reglas");
        String token = entregarYLeerToken();
        esperarError(activar(token, "a"), 400,
                "El usuario debe tener de 3 a 50 caracteres con letras, números, punto, guion y guion bajo");
        esperarError(mvc.perform(post(ACTIVAR).contentType(MediaType.APPLICATION_JSON)
                .content(activacion(token, "dueno", "corta"))), 400, "La contrasena debe tener al menos 8 caracteres");
        esperarError(mvc.perform(post(ACTIVAR).contentType(MediaType.APPLICATION_JSON)
                .content(activacion(token, "dueno", "ñ".repeat(40)))), 400, "La contrasena no puede superar 72 caracteres");
        activar(token, "dueno").andExpect(status().isOk());
    }

    @Test
    void elTokenCrudoNoApareceEnLaBaseLogsNiRespuestas(CapturedOutput output) throws Exception {
        MvcResult solicitud = mvc.perform(post(RegistrationRequestTest.RUTA).header("Idempotency-Key", "clave-secreto")
                .contentType(MediaType.APPLICATION_JSON).content(RegistrationRequestTest.cuerpo("taller-secreto")))
                .andReturn();
        String id = JsonPath.read(solicitud.getResponse().getContentAsString(), "$.requestId");
        String aprobar = plataforma(id, "approve", "{\"expectedVersion\":0}").andReturn().getResponse()
                .getContentAsString();
        String detalle = mvc.perform(get(RegistrationRequestTest.PLATAFORMA + "/" + id)
                .header("Authorization", "Bearer " + platformToken)).andReturn().getResponse().getContentAsString();
        // Antes de entregar: el cuerpo pendiente esta cifrado.
        String volcadoPendiente = volcado();
        String token = entregarYLeerToken();
        retrasarEmision(id);
        String reenviar = plataforma(id, "resend-activation", null).andReturn().getResponse().getContentAsString();
        String token2 = entregarYLeerToken();
        String activar = activar(token2, "dueno").andReturn().getResponse().getContentAsString();
        String volcadoFinal = volcado();

        for (String t : new String[]{token, token2}) {
            assertThat(volcadoPendiente).doesNotContain(t);
            assertThat(volcadoFinal).doesNotContain(t);
            assertThat(output.getAll()).doesNotContain(t);
            assertThat(aprobar + detalle + reenviar + activar).doesNotContain(t);
        }
        assertThat(volcadoPendiente).doesNotContain("/activar#token=");
        assertThat(volcadoFinal).contains(RegistrationActivationService.digest(token))
                .contains(RegistrationActivationService.digest(token2));
        assertThat(aprobar + detalle + reenviar).doesNotContain("digest").doesNotContain("activar#");
    }

    @Test
    void reenvioRespetaIntervaloCupoDiarioYEstado() throws Exception {
        String id = aprobada("taller-cupo");
        esperarError(plataforma(id, "resend-activation", null), 429, ResendLimitException.ESPERA);
        for (int i = 0; i < 5; i++) {
            retrasarEmision(id);
            plataforma(id, "resend-activation", null).andExpect(status().isAccepted());
        }
        retrasarEmision(id);
        esperarError(plataforma(id, "resend-activation", null), 429, ResendLimitException.CUPO);
        assertThat(activations.findByRequestIdOrderByGenerationDesc(id)).hasSize(6);
        assertThat(activations.findByRequestIdOrderByGenerationDesc(id).stream()
                .filter(a -> a.getRevokedAt() == null)).hasSize(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM correos_salientes WHERE estado = 'PENDING'", Long.class))
                .isEqualTo(1);

        MvcResult pendiente = mvc.perform(post(RegistrationRequestTest.RUTA).header("Idempotency-Key", "clave-cupo-p")
                .contentType(MediaType.APPLICATION_JSON).content(RegistrationRequestTest.cuerpo("taller-cupo-p")))
                .andReturn();
        String idPendiente = JsonPath.read(pendiente.getResponse().getContentAsString(), "$.requestId");
        esperarError(plataforma(idPendiente, "resend-activation", null), 409,
                RegistrationRequestConflictException.ESTADO_INVALIDO);
        esperarError(plataforma("no-existe", "resend-activation", null), 404, "Solicitud no encontrada");
        mvc.perform(post(RegistrationRequestTest.PLATAFORMA + "/" + id + "/resend-activation"))
                .andExpect(status().isUnauthorized());
        // Solo los 5 reenvios exitosos quedan auditados; los 429, 409, 404 y 401 no dejan fila.
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM auditoria_plataforma", Long.class)).isEqualTo(5);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM auditoria_plataforma WHERE id_solicitud_objetivo = ?",
                Long.class, id)).isEqualTo(5);
    }

    @Test
    void reenvioExitosoQuedaAuditadoConElAdministradorYLaSolicitud() throws Exception {
        String id = aprobada("taller-auditado");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM auditoria_plataforma", Long.class)).isZero();
        retrasarEmision(id);
        LocalDateTime antes = ahora().minusSeconds(1);
        plataforma(id, "resend-activation", null).andExpect(status().isAccepted());

        var fila = jdbc.queryForMap("SELECT accion, id_administrador, id_solicitud_objetivo, id_tenant_objetivo,"
                + " id_usuario_objetivo, motivo, fecha_auditoria FROM auditoria_plataforma");
        assertThat(fila.get("accion")).isEqualTo("RESEND_ACTIVATION");
        assertThat(((Number) fila.get("id_administrador")).longValue()).isEqualTo(1L);
        assertThat(fila.get("id_solicitud_objetivo")).isEqualTo(id);
        assertThat(fila.get("id_tenant_objetivo")).isNull();
        assertThat(fila.get("id_usuario_objetivo")).isNull();
        assertThat(fila.get("motivo")).isNull();
        assertThat(((java.sql.Timestamp) fila.get("fecha_auditoria")).toLocalDateTime()).isAfter(antes);
        // La fecha de la auditoria es la misma de la generacion emitida en esa transaccion.
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM activaciones_registro a JOIN auditoria_plataforma p"
                + " ON p.id_solicitud_objetivo = a.id_solicitud AND p.fecha_auditoria = a.fecha_creacion"
                + " WHERE a.generacion = 2", Long.class)).isEqualTo(1);
    }

    @Test
    void siLaAuditoriaFallaElReenvioSeDeshaceCompleto() throws Exception {
        String id = aprobada("taller-sin-auditoria");
        retrasarEmision(id);
        long correos = jdbc.queryForObject("SELECT COUNT(*) FROM correos_salientes", Long.class);
        // Un administrador inexistente viola la FK de la auditoria: misma transaccion, nada queda.
        assertThatThrownBy(() -> activationService.resend(id, 987654L)).isInstanceOf(RuntimeException.class);
        assertThat(activations.findByRequestIdOrderByGenerationDesc(id)).hasSize(1);
        assertThat(activations.findByRequestIdOrderByGenerationDesc(id).getFirst().getRevokedAt()).isNull();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM correos_salientes", Long.class)).isEqualTo(correos);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM correos_salientes WHERE estado = 'PENDING'", Long.class))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM auditoria_plataforma", Long.class)).isZero();
    }

    @Test
    void falloTemporalReintentaConRetrocesoYLuegoEnvia() throws Exception {
        aprobada("taller-reintento");
        transport.failNext(1, false);
        worker.processBatch();
        Map<String, Object> fila = jdbc.queryForMap("SELECT * FROM correos_salientes");
        assertThat(fila.get("ESTADO")).isEqualTo("PENDING");
        assertThat(fila.get("INTENTOS")).isEqualTo(1);
        assertThat(fila.get("ULTIMO_ERROR")).isEqualTo("TRANSPORTE_TEMPORAL");
        assertThat(fila.get("PAYLOAD_CIFRADO")).isNotNull();
        assertThat(fila.get("RECLAMADO_HASTA")).isNull();
        assertThat(((java.sql.Timestamp) fila.get("PROXIMO_INTENTO")).toLocalDateTime()).isAfter(ahora());
        // Antes del retroceso no se reintenta.
        assertThat(worker.processBatch()).isZero();

        jdbc.update("UPDATE correos_salientes SET proximo_intento = ?", ahora().minusSeconds(1));
        worker.processBatch();
        assertThat(jdbc.queryForObject("SELECT estado FROM correos_salientes", String.class)).isEqualTo("SENT");
        assertThat(jdbc.queryForObject("SELECT intentos FROM correos_salientes", Integer.class)).isEqualTo(2);
        assertThat(transport.sent()).hasSize(1);
    }

    @Test
    void falloPermanenteIntentosAgotadosVencidoYCuerpoIlegibleTerminanEnFailedSinCuerpo() throws Exception {
        aprobada("taller-permanente");
        transport.failNext(1, true);
        worker.processBatch();
        assertThat(jdbc.queryForMap("SELECT estado, ultimo_error, payload_cifrado FROM correos_salientes"))
                .containsEntry("ESTADO", "FAILED").containsEntry("ULTIMO_ERROR", "TRANSPORTE_PERMANENTE")
                .containsEntry("PAYLOAD_CIFRADO", null);

        String agotado = aprobada("taller-agotado");
        transport.failNext(6, false);
        for (int i = 0; i < 6; i++) {
            jdbc.update("UPDATE correos_salientes SET proximo_intento = ? WHERE estado = 'PENDING'", ahora().minusSeconds(1));
            worker.processBatch();
        }
        assertThat(ultimo(agotado)).containsEntry("ESTADO", "FAILED").containsEntry("ULTIMO_ERROR", "INTENTOS_AGOTADOS")
                .containsEntry("INTENTOS", 6).containsEntry("PAYLOAD_CIFRADO", null);

        String vencido = aprobada("taller-correo-vencido");
        jdbc.update("UPDATE correos_salientes SET expira_en = ? WHERE estado = 'PENDING'", ahora().minusSeconds(1));
        worker.processBatch();
        assertThat(ultimo(vencido)).containsEntry("ESTADO", "FAILED").containsEntry("ULTIMO_ERROR", "VENCIDO")
                .containsEntry("PAYLOAD_CIFRADO", null);

        String ilegible = aprobada("taller-ilegible");
        jdbc.update("UPDATE correos_salientes SET payload_cifrado = 'AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA' WHERE estado = 'PENDING'");
        worker.processBatch();
        assertThat(ultimo(ilegible)).containsEntry("ESTADO", "FAILED").containsEntry("ULTIMO_ERROR", "PAYLOAD_INVALIDO");
        assertThat(transport.sent()).isEmpty();
    }

    /** Todas las filas de todas las tablas del esquema como texto; igual en H2 y PostgreSQL. */
    private String volcado() {
        List<String> tablas = jdbc.execute((java.sql.Connection c) -> {
            List<String> nombres = new java.util.ArrayList<>();
            try (var rs = c.getMetaData().getTables(null, c.getSchema(), "%", new String[]{"TABLE"})) {
                while (rs.next()) {
                    nombres.add(rs.getString("TABLE_NAME"));
                }
            }
            return nombres;
        });
        assertThat(tablas).hasSizeGreaterThanOrEqualTo(18);
        StringBuilder texto = new StringBuilder();
        for (String tabla : tablas) {
            jdbc.queryForList("SELECT * FROM \"" + tabla + "\"").forEach(fila -> texto.append(fila.values()).append('\n'));
        }
        return texto.toString();
    }

    private Map<String, Object> ultimo(String requestId) {
        return jdbc.queryForMap("SELECT c.estado, c.ultimo_error, c.intentos, c.payload_cifrado FROM correos_salientes c"
                + " JOIN activaciones_registro a ON a.id_activacion = c.id_activacion WHERE a.id_solicitud = ?", requestId);
    }

    @Test
    void lasRestriccionesDeLaBaseProtegenEstadosYCuerpo() throws Exception {
        String id = aprobada("taller-restricciones");
        Long activacion = activations.findByRequestIdOrderByGenerationDesc(id).getFirst().getId();
        // SENT con cuerpo cifrado y sin fecha de envio.
        assertThatThrownBy(() -> jdbc.update("UPDATE correos_salientes SET estado = 'SENT' WHERE id_activacion = ?",
                activacion)).isInstanceOf(DataIntegrityViolationException.class);
        // ACTIVATED sin tenant, y con la reserva todavia puesta.
        assertThatThrownBy(() -> jdbc.update("UPDATE solicitudes_registro SET estado = 'ACTIVATED', slug_reservado = NULL"
                + " WHERE id_solicitud = ?", id)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE solicitudes_registro SET estado = 'ACTIVATED' WHERE id_solicitud = ?",
                id)).isInstanceOf(DataIntegrityViolationException.class);
        // Consumida y revocada a la vez; digest repetido.
        assertThatThrownBy(() -> jdbc.update("UPDATE activaciones_registro SET fecha_consumo = ?, fecha_revocacion = ?"
                + " WHERE id_activacion = ?", ahora(), ahora(), activacion))
                .isInstanceOf(DataIntegrityViolationException.class);
        String digest = activations.findById(activacion).orElseThrow().getTokenDigest();
        assertThatThrownBy(() -> jdbc.update("INSERT INTO activaciones_registro (id_solicitud, generacion, digest_token,"
                + " fecha_creacion, expira_en) VALUES (?, 2, ?, ?, ?)", id, digest, ahora(), ahora()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
