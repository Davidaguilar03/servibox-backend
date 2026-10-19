package com.servibox.backend.reporting;

import com.jayway.jsonpath.JsonPath;
import com.servibox.backend.auth.entity.Role;
import com.servibox.backend.auth.entity.User;
import com.servibox.backend.auth.repository.UserRepository;
import com.servibox.backend.counterparties.entity.Counterparty;
import com.servibox.backend.inventory.entity.Product;
import com.servibox.backend.sales.SalesFixture;
import com.servibox.backend.sales.entity.PaymentType;
import com.servibox.backend.sales.service.SalesService;
import com.servibox.backend.tenant.Tenant;
import com.servibox.backend.tenant.TenantContext;
import com.servibox.backend.tenant.TenantRepository;
import com.servibox.backend.treasury.entity.Account;
import com.servibox.backend.treasury.service.TreasuryService;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * El reporte de movimientos no hace N+1, y el tope de filas. Contexto propio: estadisticas
 * de Hibernate encendidas y el tope bajado a 60 para no crear 20000 movimientos.
 */
@SpringBootTest(properties = {
        "spring.jpa.properties.hibernate.generate_statistics=true",
        "servibox.reporting.max-export-rows=60"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ReportExportQueriesTest {

    private static final String PASSWORD = "clave-correcta";
    private static final String RANGO = "&desde=2026-09-01&hasta=2026-09-30";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private SalesFixture fixture;

    @Autowired
    private SalesService salesService;

    @Autowired
    private TreasuryService treasuryService;

    @Autowired
    private TenantRepository tenantRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @AfterEach
    void cleanUp() {
        TenantContext.clear();
    }

    /**
     * Un tenant con `cantidad` movimientos en septiembre: la mitad ventas de contado (cada una
     * con su cliente y su numero de factura) y la otra mitad ingresos ocasionales.
     * Devuelve el token y el id de la cuenta.
     */
    private Object[] tenantConMovimientos(int cantidad) throws Exception {
        String slug = "taller-consultas-" + System.nanoTime();
        Tenant tenant = new Tenant();
        tenant.setName(slug);
        tenant.setSlug(slug);
        tenant.setActive(Boolean.TRUE);
        Long tenantId = tenantRepository.save(tenant).getId();

        fixture.usarTenant(tenantId);
        User user = new User();
        user.setUsername("admin");
        user.setPasswordHash(passwordEncoder.encode(PASSWORD));
        user.setActive(true);
        user.setRole(Role.ADMIN);
        userRepository.save(user);
        Product llanta = fixture.crearProducto(fixture.crearCategoriaConIva(), "LLA-001", 100.0, 1000);
        Account caja = fixture.crearCuenta("Caja General");
        for (int i = 0; i < cantidad; i++) {
            LocalDate fecha = LocalDate.of(2026, 9, 1 + i % 28);
            if (i % 2 == 0) {
                Counterparty cliente = fixture.crearCliente("Cliente " + i, "9000" + i);
                salesService.crearFactura("FV-" + (1000 + i), cliente, fecha, null, PaymentType.CONTADO, caja,
                        "Efectivo", List.of(new SalesService.LineaFactura(llanta.getId(), 1, 1000.0)));
            } else {
                treasuryService.registrarIngresoOcasional(caja, "Ingreso " + i, 500.0, fecha, null);
            }
        }
        TenantContext.clear();

        MvcResult login = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"tenantSlug":"%s","username":"admin","password":"%s"}
                                """.formatted(slug, PASSWORD)))
                .andExpect(status().isOk())
                .andReturn();
        return new Object[]{JsonPath.read(login.getResponse().getContentAsString(), "$.token"), caja.getId()};
    }

    /** Sentencias SQL preparadas durante una exportacion. */
    private long consultasAlExportar(String token, String query) throws Exception {
        Statistics estadisticas = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        estadisticas.clear();
        mockMvc.perform(get("/api/reporting/export?" + query)
                        .header("Authorization", "Bearer " + token)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());
        return estadisticas.getPrepareStatementCount();
    }

    @Test
    void elReporteDeMovimientosHaceLasMismasConsultasCon5YCon50Movimientos() throws Exception {
        Object[] cinco = tenantConMovimientos(5);
        Object[] cincuenta = tenantConMovimientos(50);

        for (String formato : List.of("PDF", "XLSX")) {
            long con5 = consultasAlExportar((String) cinco[0], "type=MOVIMIENTOS&format=" + formato + RANGO);
            long con50 = consultasAlExportar((String) cincuenta[0], "type=MOVIMIENTOS&format=" + formato + RANGO);
            System.out.println("Consultas SQL, movimientos " + formato + " todas las cuentas: 5 -> " + con5
                    + ", 50 -> " + con50);
            assertThat(con50).isEqualTo(con5);

            long cuenta5 = consultasAlExportar((String) cinco[0],
                    "type=MOVIMIENTOS&format=" + formato + "&accountId=" + cinco[1] + RANGO);
            long cuenta50 = consultasAlExportar((String) cincuenta[0],
                    "type=MOVIMIENTOS&format=" + formato + "&accountId=" + cincuenta[1] + RANGO);
            System.out.println("Consultas SQL, movimientos " + formato + " con accountId: 5 -> " + cuenta5
                    + ", 50 -> " + cuenta50);
            assertThat(cuenta50).isEqualTo(cuenta5);
        }
    }

    @Test
    void masFilasQueElTopeEs400() throws Exception {
        Object[] datos = tenantConMovimientos(61);

        mockMvc.perform(get("/api/reporting/export?type=MOVIMIENTOS&format=XLSX" + RANGO)
                        .header("Authorization", "Bearer " + datos[0]))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("El reporte tiene mas de 60 movimientos: acota el rango de fechas"));

        // Con un rango que deja 60 o menos, sale.
        mockMvc.perform(get("/api/reporting/export?type=MOVIMIENTOS&format=XLSX&desde=2026-09-01&hasta=2026-09-20")
                        .header("Authorization", "Bearer " + datos[0]))
                .andExpect(status().isOk());
    }
}
