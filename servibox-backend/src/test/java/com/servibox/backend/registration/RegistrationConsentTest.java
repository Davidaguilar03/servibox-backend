package com.servibox.backend.registration;

import com.jayway.jsonpath.JsonPath;
import com.servibox.backend.platform.PlatformAdminRepository;
import com.servibox.backend.platform.PlatformBootstrapService;
import com.servibox.backend.platform.PlatformJwtService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Consentimiento de tratamiento de datos en la solicitud publica y su evidencia en el panel (tarea 55). */
@SpringBootTest
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@ActiveProfiles("test")
@TestPropertySource(properties = "servibox.registration-requests.enabled=true")
@ExtendWith(OutputCaptureExtension.class)
class RegistrationConsentTest {

    private static final String VIGENTE = "2026-11-01";
    private static final String ACTUALIZADA = "La politica fue actualizada; recarga la pagina para continuar";
    private static final String OBLIGATORIA = "La version de la politica es obligatoria";
    private static final String NO_VALIDA = "La version de la politica no es valida";
    private static final String CAMPO = "\"policyVersion\":\"" + VIGENTE + "\"";

    @Autowired MockMvc mvc;
    @Autowired RegistrationRequestRepository requests;
    @Autowired RegistrationRequestService service;
    @Autowired PlatformAdminRepository admins;
    @Autowired PlatformBootstrapService bootstrap;
    @Autowired PlatformJwtService platformJwt;
    @Autowired JdbcTemplate jdbc;

    private String platformToken;

    @BeforeEach
    void seed() {
        bootstrap.create("revisor@example.invalid", "ephemeral-test-password".toCharArray());
        platformToken = platformJwt.generateToken(admins.findById(1L).orElseThrow());
    }

    @AfterEach
    void cleanup() {
        jdbc.update("DELETE FROM correos_salientes");
        jdbc.update("DELETE FROM activaciones_registro");
        requests.deleteAll();
        admins.deleteAll();
        ReflectionTestUtils.setField(service, "policyVersion", VIGENTE);
    }

    private static String cuerpoCon(String slug, String version) {
        return RegistrationRequestTest.cuerpo(slug).replace(CAMPO, "\"policyVersion\":" + version);
    }

    private ResultActions enviar(String clave, String cuerpo) throws Exception {
        return mvc.perform(post(RegistrationRequestTest.RUTA).header("Idempotency-Key", clave)
                .contentType(MediaType.APPLICATION_JSON).content(cuerpo));
    }

    private String crear(String clave, String slug) throws Exception {
        String json = enviar(clave, RegistrationRequestTest.cuerpo(slug)).andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(json, "$.requestId");
    }

    private void esperar400(String cuerpo, String mensaje) throws Exception {
        enviar("clave-invalida", cuerpo).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(mensaje)).andExpect(jsonPath("$.status").value(400));
        assertThat(requests.count()).isZero();
    }

    private ResultActions plataforma(String ruta) throws Exception {
        return mvc.perform(get(RegistrationRequestTest.PLATAFORMA + ruta).header("Authorization", "Bearer " + platformToken));
    }

    private ResultActions revisar(String id, String accion, String cuerpo) throws Exception {
        return mvc.perform(post(RegistrationRequestTest.PLATAFORMA + "/" + id + "/" + accion)
                .header("Authorization", "Bearer " + platformToken)
                .contentType(MediaType.APPLICATION_JSON).content(cuerpo));
    }

    private Map<String, Object> detalle(String id) throws Exception {
        return JsonPath.read(plataforma("/" + id).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(), "$");
    }

    private void insertarHistorica(String id, String slug) {
        jdbc.update("INSERT INTO solicitudes_registro (id_solicitud, nombre_comercial, nombre_comercial_busqueda, nit, "
                + "telefono, nombre_responsable, nombre_responsable_busqueda, correo_responsable, correo_normalizado, "
                + "slug_solicitado, slug_reservado, estado, hash_cuerpo, idempotencia_expira_en, fecha_creacion, "
                + "reserva_expira_en) VALUES (?, 'Historica', 'historica', '900123456-7', '3001234567', 'Ana', 'ana', "
                + "'ana@uno.co', 'ana@uno.co', ?, ?, 'PENDING', ?, ?, ?, ?)",
                id, slug, slug, "0".repeat(64), LocalDateTime.now(ZoneOffset.UTC).plusDays(2),
                LocalDateTime.now(ZoneOffset.UTC), LocalDateTime.now(ZoneOffset.UTC).plusDays(7));
    }

    @Test
    void conLaVersionVigenteGuardaLaVersionYLaFechaEnUtc() throws Exception {
        LocalDateTime antes = LocalDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
        enviar("clave-consent-1", RegistrationRequestTest.cuerpo("taller-consent")).andExpect(status().isAccepted())
                .andExpect(jsonPath("$.policyVersion").doesNotExist())
                .andExpect(jsonPath("$.policyAcceptedAt").doesNotExist());

        RegistrationRequest guardada = requests.findAll().getFirst();
        assertThat(guardada.getPolicyVersion()).isEqualTo(VIGENTE);
        assertThat(guardada.getPolicyAcceptedAt()).isEqualTo(guardada.getCreatedAt())
                .isBetween(antes, LocalDateTime.now(ZoneOffset.UTC));
        // Solo esas dos columnas nuevas: ni IP ni agente de usuario.
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE "
                + "LOWER(TABLE_NAME) = 'solicitudes_registro' AND (LOWER(COLUMN_NAME) LIKE '%ip%' "
                + "OR LOWER(COLUMN_NAME) LIKE '%agente%' OR LOWER(COLUMN_NAME) LIKE '%agent%')", Long.class)).isZero();
    }

    @Test
    void sinVersionOVaciaResponde400() throws Exception {
        esperar400(RegistrationRequestTest.cuerpo("taller-sin").replace(",\n " + CAMPO, ""), OBLIGATORIA);
        esperar400(cuerpoCon("taller-sin", "null"), OBLIGATORIA);
        esperar400(cuerpoCon("taller-sin", "\"\""), OBLIGATORIA);
        esperar400(cuerpoCon("taller-sin", "\"   \""), OBLIGATORIA);
    }

    @Test
    void conFormatoInvalidoResponde400() throws Exception {
        for (String version : new String[]{"\"2026/11/01\"", "\"20261101\"", "\"2026-13-01\"", "\"2026-02-30\"",
                "\" 2026-11-01\"", "\"2026-11-01 \"", "\"2026-11-1\"", "\"+2026-11-01\"", "\"v1\"",
                "\"2026-11-01T00:00\"", "20261101"}) {
            esperar400(cuerpoCon("taller-formato", version), NO_VALIDA);
        }
    }

    @Test
    void conOtraVersionResponde400YPideRecargar() throws Exception {
        esperar400(cuerpoCon("taller-vieja", "\"2026-10-01\""), ACTUALIZADA);
        esperar400(cuerpoCon("taller-vieja", "\"2027-01-01\""), ACTUALIZADA);
    }

    @Test
    void repetirConElMismoCuerpoDevuelveLaMismaSolicitudSinCambiarLaFecha() throws Exception {
        String id = crear("clave-consent-2", "taller-repetido");
        LocalDateTime fecha = requests.findById(id).orElseThrow().getPolicyAcceptedAt();

        assertThat(crear("clave-consent-2", "taller-repetido")).isEqualTo(id);

        assertThat(requests.count()).isEqualTo(1);
        assertThat(requests.findById(id).orElseThrow().getPolicyAcceptedAt()).isEqualTo(fecha);
    }

    @Test
    void laMismaClaveConOtraVersionEsCuerpoDistinto() throws Exception {
        String id = crear("clave-consent-3", "taller-otra");

        enviar("clave-consent-3", cuerpoCon("taller-otra", "\"2026-10-01\"")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value(RegistrationRequestConflictException.CLAVE_REUTILIZADA));

        assertThat(requests.count()).isEqualTo(1);
        assertThat(requests.findById(id).orElseThrow().getPolicyVersion()).isEqualTo(VIGENTE);
    }

    /** Tras rotar la politica el reintento conserva su resultado, pero nada nuevo entra con la version vieja. */
    @Test
    void trasActualizarLaPoliticaElReintentoConservaSuResultadoYNoEntraNadaNuevo() throws Exception {
        String id = crear("clave-consent-4", "taller-rotacion");
        ReflectionTestUtils.setField(service, "policyVersion", "2027-01-01");

        assertThat(crear("clave-consent-4", "taller-rotacion")).isEqualTo(id);
        assertThat(requests.findById(id).orElseThrow().getPolicyVersion()).isEqualTo(VIGENTE);

        enviar("clave-consent-5", RegistrationRequestTest.cuerpo("taller-rotacion-2")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(ACTUALIZADA));
        assertThat(requests.count()).isEqualTo(1);
    }

    @Test
    void conClaveVencidaYVersionViejaResponde400SinLiberarLaClave() throws Exception {
        String id = crear("clave-consent-6", "taller-vencida");
        jdbc.update("UPDATE solicitudes_registro SET idempotencia_expira_en = ? WHERE id_solicitud = ?",
                LocalDateTime.now(ZoneOffset.UTC).minusHours(1), id);

        enviar("clave-consent-6", cuerpoCon("taller-vencida-2", "\"2026-10-01\"")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(ACTUALIZADA));

        assertThat(requests.findById(id).orElseThrow().getIdempotencyKey()).isEqualTo("clave-consent-6");
        assertThat(requests.count()).isEqualTo(1);
    }

    @Test
    void elDetalleExponeLaEvidenciaYElListadoNoCambia() throws Exception {
        String id = crear("clave-consent-7", "taller-detalle");
        RegistrationRequest guardada = requests.findById(id).orElseThrow();

        Map<String, Object> detalle = detalle(id);
        assertThat(detalle.get("policyVersion")).isEqualTo(VIGENTE);
        assertThat(LocalDateTime.parse((String) detalle.get("policyAcceptedAt")))
                .isCloseTo(guardada.getPolicyAcceptedAt(), within(1, ChronoUnit.MICROS));

        Map<String, Object> item = JsonPath.read(plataforma("").andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(), "$.items[0]");
        Set<String> esperadas = new HashSet<>(detalle.keySet());
        esperadas.removeAll(Set.of("policyVersion", "policyAcceptedAt"));
        assertThat(item.keySet()).isEqualTo(esperadas).contains("id", "status", "matches", "version");
        assertThat(item.get("matches")).isNull();
    }

    @Test
    void lasSolicitudesHistoricasMuestranNulosYSeSiguenRevisando() throws Exception {
        String aprobar = "11111111-2222-3333-4444-555555555501";
        String rechazar = "11111111-2222-3333-4444-555555555502";
        insertarHistorica(aprobar, "historica-a");
        insertarHistorica(rechazar, "historica-b");

        Map<String, Object> detalle = detalle(aprobar);
        assertThat(detalle).containsKeys("policyVersion", "policyAcceptedAt");
        assertThat(detalle.get("policyVersion")).isNull();
        assertThat(detalle.get("policyAcceptedAt")).isNull();

        revisar(aprobar, "approve", "{\"expectedVersion\":0}").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"));
        revisar(rechazar, "reject", "{\"expectedVersion\":0,\"reason\":\"Datos incompletos\"}").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"));
        assertThat(requests.findById(aprobar).orElseThrow().getPolicyVersion()).isNull();
    }

    @Test
    void aprobarConservaLaEvidencia() throws Exception {
        String id = crear("clave-consent-8", "taller-aprobado");
        LocalDateTime fecha = requests.findById(id).orElseThrow().getPolicyAcceptedAt();

        revisar(id, "approve", "{\"expectedVersion\":0}").andExpect(status().isOk())
                .andExpect(jsonPath("$.policyVersion").value(VIGENTE));

        assertThat(requests.findById(id).orElseThrow().getPolicyAcceptedAt()).isEqualTo(fecha);
    }

    @Test
    void laBaseImpideLaVersionSinFechaYLaFechaSinVersion() throws Exception {
        String id = crear("clave-consent-9", "taller-check");
        assertThatThrownBy(() -> jdbc.update(
                "UPDATE solicitudes_registro SET fecha_aceptacion_politica = NULL WHERE id_solicitud = ?", id))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(
                "UPDATE solicitudes_registro SET version_politica = NULL WHERE id_solicitud = ?", id))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    /** Otra version vigente porque la de las pruebas coincide con la fecha de los logs de hoy. */
    @Test
    void losLogsNoLlevanDatosPersonalesNiLaVersion(CapturedOutput output) throws Exception {
        String vigente = "2031-03-07";
        ReflectionTestUtils.setField(service, "policyVersion", vigente);
        enviar("clave-consent-10", cuerpoCon("taller-logs", "\"" + vigente + "\"")).andExpect(status().isAccepted());
        enviar("clave-consent-10", cuerpoCon("taller-logs", "\"2031-03-08\"")).andExpect(status().isConflict());
        enviar("clave-consent-11", cuerpoCon("taller-logs-2", "\"2031-03-08\"")).andExpect(status().isBadRequest());
        enviar("clave-consent-12", cuerpoCon("taller-logs-3", "\"no-valida\"")).andExpect(status().isBadRequest());

        assertThat(output.getOut() + output.getErr()).doesNotContain("ana@uno.co", "Ana Rojas", "900123456-7",
                "300 123 4567", vigente, "2031-03-08", "no-valida");
    }
}
