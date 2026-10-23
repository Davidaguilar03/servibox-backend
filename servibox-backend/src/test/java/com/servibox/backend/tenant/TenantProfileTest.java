package com.servibox.backend.tenant;

import com.jayway.jsonpath.JsonPath;
import com.servibox.backend.auth.entity.Role;
import com.servibox.backend.auth.entity.User;
import com.servibox.backend.auth.repository.UserRepository;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * GET y PUT /api/tenant, y la linea NIT del encabezado de los reportes. El tenant uno nace
 * con NIT y telefono, como uno del registro publico; el dos sin ellos, como los anteriores.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class TenantProfileTest {

    private static final String PASSWORD = "clave-correcta";
    private static final String RANGO = "&desde=2026-09-01&hasta=2026-09-30";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TenantRepository tenantRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private String slugUno;
    private String slugDos;
    private Long tenantUno;
    private Long tenantDos;
    private String tokenUno;
    private String tokenDos;

    @BeforeEach
    void seed() throws Exception {
        slugUno = "taller-uno-" + System.nanoTime();
        slugDos = "taller-dos-" + System.nanoTime();
        tenantUno = crearTenant(slugUno, "Taller Uno", "900123456-7", "+57 300 111 2233");
        tenantDos = crearTenant(slugDos, "Taller Dos", null, null);
        crearUsuario(tenantUno, "admin", Role.ADMIN);
        crearUsuario(tenantDos, "admin", Role.ADMIN);
        tokenUno = login(slugUno, "admin");
        tokenDos = login(slugDos, "admin");
    }

    @AfterEach
    void cleanUp() {
        TenantContext.clear();
    }

    // ---- Ayudas ----

    private Long crearTenant(String slug, String nombre, String nit, String telefono) {
        Tenant tenant = new Tenant();
        tenant.setName(nombre);
        tenant.setSlug(slug);
        tenant.setNit(nit);
        tenant.setPhone(telefono);
        tenant.setActive(Boolean.TRUE);
        return tenantRepository.save(tenant).getId();
    }

    private void crearUsuario(Long tenantId, String username, Role rol) {
        TenantContext.setTenantId(tenantId);
        try {
            User user = new User();
            user.setUsername(username);
            user.setPasswordHash(passwordEncoder.encode(PASSWORD));
            user.setActive(true);
            user.setRole(rol);
            userRepository.save(user);
        } finally {
            TenantContext.clear();
        }
    }

    private MvcResult loginResult(String slug, String username) throws Exception {
        return mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"tenantSlug":"%s","username":"%s","password":"%s"}
                                """.formatted(slug, username, PASSWORD)))
                .andExpect(status().isOk())
                .andReturn();
    }

    private String login(String slug, String username) throws Exception {
        return JsonPath.read(loginResult(slug, username).getResponse().getContentAsString(), "$.token");
    }

    private ResultActions perfil(String token) throws Exception {
        return mockMvc.perform(get("/api/tenant").header("Authorization", "Bearer " + token));
    }

    private ResultActions editar(String token, String body) throws Exception {
        return mockMvc.perform(put("/api/tenant")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private static String cuerpo(String nombre, String nit, String telefono) {
        return """
                {"name":%s,"nit":%s,"phone":%s}
                """.formatted(json(nombre), json(nit), json(telefono));
    }

    private static String json(String valor) {
        return valor == null ? "null" : "\"" + valor + "\"";
    }

    private byte[] exportar(String token, String format) throws Exception {
        return mockMvc.perform(get("/api/reporting/export?type=ESTADO_RESULTADOS&format=" + format + RANGO)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
    }

    private static List<String> lineasPdf(byte[] pdf) throws IOException {
        try (PDDocument documento = Loader.loadPDF(pdf)) {
            return new PDFTextStripper().getText(documento).lines().map(String::trim).toList();
        }
    }

    /** Las primeras celdas de las filas de encabezado, en orden. */
    private static List<String> encabezadoXlsx(byte[] xlsx) throws IOException {
        Sheet hoja = new XSSFWorkbook(new ByteArrayInputStream(xlsx)).getSheetAt(0);
        return List.of(hoja.getRow(0).getCell(0).getStringCellValue(),
                hoja.getRow(1).getCell(0).getStringCellValue(),
                hoja.getRow(2).getCell(0).getStringCellValue());
    }

    private Tenant enBase(Long id) {
        return tenantRepository.findById(id).orElseThrow();
    }

    // ---- GET ----

    @Test
    void elGetDevuelveElPerfilDelTenantDelToken() throws Exception {
        perfil(tokenUno)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Taller Uno"))
                .andExpect(jsonPath("$.slug").value(slugUno))
                .andExpect(jsonPath("$.nit").value("900123456-7"))
                .andExpect(jsonPath("$.phone").value("+57 300 111 2233"));
        perfil(tokenDos)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Taller Dos"))
                .andExpect(jsonPath("$.slug").value(slugDos))
                .andExpect(jsonPath("$.nit").value(nullValue()))
                .andExpect(jsonPath("$.phone").value(nullValue()));
    }

    // ---- PUT ----

    @Test
    void elPutActualizaYUnGetPosteriorLoRefleja() throws Exception {
        editar(tokenDos, cuerpo("  Serviteca Dos  ", " 800765432-1 ", "(601) 555-1234"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Serviteca Dos"))
                .andExpect(jsonPath("$.slug").value(slugDos))
                .andExpect(jsonPath("$.nit").value("800765432-1"))
                .andExpect(jsonPath("$.phone").value("(601) 555-1234"));

        perfil(tokenDos)
                .andExpect(jsonPath("$.name").value("Serviteca Dos"))
                .andExpect(jsonPath("$.nit").value("800765432-1"))
                .andExpect(jsonPath("$.phone").value("(601) 555-1234"));
    }

    @Test
    void nitYTelefonoEnBlancoSeGuardanComoNull() throws Exception {
        editar(tokenUno, cuerpo("Taller Uno", "   ", ""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nit").value(nullValue()))
                .andExpect(jsonPath("$.phone").value(nullValue()));
        assertThat(enBase(tenantUno).getNit()).isNull();
        assertThat(enBase(tenantUno).getPhone()).isNull();

        // Ausentes en el cuerpo tambien quedan en null.
        editar(tokenUno, cuerpo("Taller Uno", "900123456-7", "3001112233")).andExpect(status().isOk());
        editar(tokenUno, "{\"name\":\"Taller Uno\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nit").value(nullValue()))
                .andExpect(jsonPath("$.phone").value(nullValue()));
    }

    @Test
    void elSlugNoCambiaAunqueLlegueEnElCuerpo() throws Exception {
        editar(tokenUno, """
                {"name":"Taller Uno","nit":"900123456-7","phone":"3001112233","slug":"otro-slug"}
                """)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.slug").value(slugUno));
        assertThat(enBase(tenantUno).getSlug()).isEqualTo(slugUno);
        assertThat(tenantRepository.findBySlug("otro-slug")).isEmpty();
    }

    @Test
    void lasReglasDelNombreSonLasDelRegistro() throws Exception {
        editar(tokenUno, cuerpo("   ", null, null))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("El nombre comercial es obligatorio"))
                .andExpect(jsonPath("$.status").value(400));
        editar(tokenUno, "{\"nit\":\"900123456-7\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("El nombre comercial es obligatorio"));
        editar(tokenUno, cuerpo("x".repeat(151), null, null))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("El nombre comercial no puede superar 150 caracteres"));
        // 150 justos si valen.
        editar(tokenUno, cuerpo("x".repeat(150), null, null)).andExpect(status().isOk());
    }

    @Test
    void lasReglasDelNitSonLasDelRegistro() throws Exception {
        editar(tokenUno, cuerpo("Otro nombre", "90012-34-56", null))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(
                        "El NIT solo admite digitos y un guion con el digito de verificacion, por ejemplo 900123456-7"));
        editar(tokenUno, cuerpo("Otro nombre", "1".repeat(26), null))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("El NIT no puede superar 25 caracteres"));
        // Un error no deja nada a medias.
        assertThat(enBase(tenantUno).getName()).isEqualTo("Taller Uno");
        assertThat(enBase(tenantUno).getNit()).isEqualTo("900123456-7");
        // Sin digito de verificacion tambien vale.
        editar(tokenUno, cuerpo("Taller Uno", "900123456", null)).andExpect(status().isOk());
    }

    @Test
    void lasReglasDelTelefonoSonLasDelRegistro() throws Exception {
        editar(tokenUno, cuerpo("Otro nombre", null, "300-ABC"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("El telefono solo admite digitos, espacios, +, - y parentesis"));
        editar(tokenUno, cuerpo("Otro nombre", null, "3".repeat(26)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("El telefono no puede superar 25 caracteres"));
        assertThat(enBase(tenantUno).getName()).isEqualTo("Taller Uno");
        assertThat(enBase(tenantUno).getPhone()).isEqualTo("+57 300 111 2233");
    }

    // ---- Permisos ----

    @Test
    void soloUnAdminPuedeEditar() throws Exception {
        crearUsuario(tenantUno, "cajero", Role.CAJERO);
        String tokenCajero = login(slugUno, "cajero");

        editar(tokenCajero, cuerpo("Nombre del cajero", "123", "456"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("Solo un administrador puede editar los datos de la serviteca"))
                .andExpect(jsonPath("$.status").value(403));
        // Tambien con un cuerpo invalido: el permiso va primero.
        editar(tokenCajero, cuerpo("", null, null)).andExpect(status().isForbidden());

        Tenant tenant = enBase(tenantUno);
        assertThat(tenant.getName()).isEqualTo("Taller Uno");
        assertThat(tenant.getNit()).isEqualTo("900123456-7");
        assertThat(tenant.getPhone()).isEqualTo("+57 300 111 2233");

        // Leer si puede.
        perfil(tokenCajero).andExpect(status().isOk()).andExpect(jsonPath("$.name").value("Taller Uno"));
    }

    @Test
    void sinTokenEs401() throws Exception {
        mockMvc.perform(get("/api/tenant"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("No autenticado"));
        mockMvc.perform(put("/api/tenant")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(cuerpo("Sin token", null, null)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("No autenticado"));
        assertThat(enBase(tenantUno).getName()).isEqualTo("Taller Uno");
        assertThat(enBase(tenantDos).getName()).isEqualTo("Taller Dos");
    }

    // ---- Aislamiento ----

    @Test
    void editarUnTenantNoCambiaElOtro() throws Exception {
        editar(tokenUno, cuerpo("Uno editado", "111", "222")).andExpect(status().isOk());

        perfil(tokenDos)
                .andExpect(jsonPath("$.name").value("Taller Dos"))
                .andExpect(jsonPath("$.slug").value(slugDos))
                .andExpect(jsonPath("$.nit").value(nullValue()));
        perfil(tokenUno)
                .andExpect(jsonPath("$.name").value("Uno editado"))
                .andExpect(jsonPath("$.slug").value(slugUno));
        Tenant dos = enBase(tenantDos);
        assertThat(dos.getName()).isEqualTo("Taller Dos");
        assertThat(dos.getNit()).isNull();
        assertThat(dos.getPhone()).isNull();
    }

    // ---- Login y reportes ----

    @Test
    void elNombreEditadoSaleEnElLoginYEnElReporte() throws Exception {
        editar(tokenDos, cuerpo("Serviteca Renombrada", null, null)).andExpect(status().isOk());

        MvcResult login = loginResult(slugDos, "admin");
        assertThat((String) JsonPath.read(login.getResponse().getContentAsString(), "$.tenant.name"))
                .isEqualTo("Serviteca Renombrada");

        assertThat(lineasPdf(exportar(tokenDos, "PDF"))).contains("Serviteca Renombrada")
                .doesNotContain("Taller Dos");
        assertThat(encabezadoXlsx(exportar(tokenDos, "XLSX")).get(0)).isEqualTo("Serviteca Renombrada");
    }

    @Test
    void conNitElEncabezadoLlevaLaLineaNitBajoElNombre() throws Exception {
        List<String> lineas = lineasPdf(exportar(tokenUno, "PDF"));
        int nombre = lineas.indexOf("Taller Uno");
        assertThat(nombre).isGreaterThanOrEqualTo(0);
        assertThat(lineas.get(nombre + 1)).isEqualTo("NIT 900123456-7");
        assertThat(lineas.get(nombre + 2)).isEqualTo("Estado de resultados");

        assertThat(encabezadoXlsx(exportar(tokenUno, "XLSX")))
                .containsExactly("Taller Uno", "NIT 900123456-7", "Estado de resultados");
    }

    @Test
    void sinNitElEncabezadoQuedaComoAntes() throws Exception {
        List<String> lineas = lineasPdf(exportar(tokenDos, "PDF"));
        int nombre = lineas.indexOf("Taller Dos");
        assertThat(lineas.get(nombre + 1)).isEqualTo("Estado de resultados");
        assertThat(lineas).noneMatch(l -> l.startsWith("NIT"));

        assertThat(encabezadoXlsx(exportar(tokenDos, "XLSX")))
                .containsExactly("Taller Dos", "Estado de resultados", "Período: 01/09/2026 a 30/09/2026");

        // Y si un ADMIN borra el NIT, la linea desaparece.
        editar(tokenUno, cuerpo("Taller Uno", "", null)).andExpect(status().isOk());
        assertThat(lineasPdf(exportar(tokenUno, "PDF"))).noneMatch(l -> l.startsWith("NIT"));
        assertThat(encabezadoXlsx(exportar(tokenUno, "XLSX")).get(1)).isEqualTo("Estado de resultados");
    }
}
