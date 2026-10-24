package com.servibox.backend.reporting;

import com.jayway.jsonpath.JsonPath;
import com.servibox.backend.auth.entity.Role;
import com.servibox.backend.auth.entity.User;
import com.servibox.backend.auth.repository.UserRepository;
import com.servibox.backend.counterparties.entity.Counterparty;
import com.servibox.backend.inventory.entity.Product;
import com.servibox.backend.purchases.service.PurchasesService;
import com.servibox.backend.reporting.export.ReportModel;
import com.servibox.backend.reporting.service.ReportExportService;
import com.servibox.backend.sales.SalesFixture;
import com.servibox.backend.sales.entity.PaymentType;
import com.servibox.backend.sales.entity.Sale;
import com.servibox.backend.sales.service.SalesService;
import com.servibox.backend.tenant.Tenant;
import com.servibox.backend.tenant.TenantContext;
import com.servibox.backend.tenant.TenantRepository;
import com.servibox.backend.treasury.entity.Account;
import com.servibox.backend.treasury.entity.AccountType;
import com.servibox.backend.treasury.service.TreasuryService;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFCellStyle;
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
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * GET /api/reporting/export: Estado de resultados y Movimientos de cuentas en PDF y Excel.
 *
 * Escenario de septiembre de 2026 (IVA del 19 por ciento):
 * Caja arranca en 1.000.000 y Banco en 500.000. El 20/08 entra un ingreso ocasional de
 * 200.000 a Caja (antes del rango). En el rango: ingreso ocasional 50.000, gasto 30.000,
 * transferencia Caja -> Banco 100.000, venta de contado FV-0100 (total 119.000), venta a
 * credito FV-0101 (total 238.000) con abono de 100.000, compra de contado FAC-500 (total
 * 59.500) y una venta de contado FV-0102 (95.200) que se anula. El 05/10 un gasto de 10.000
 * (despues del rango).
 *
 * Estado de resultados de septiembre: ventas 357.000, compras 59.500, bruta 297.500, otros
 * ingresos 50.000 (el del 20/08 queda fuera), gastos 30.000, neta 297.500 + 50.000 - 30.000 =
 * 317.500. Octubre no tiene ingresos ocasionales: todo en 0 salvo gastos 10.000 y neta -10.000.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ReportExportTest {

    private static final String PASSWORD = "clave-correcta";
    private static final String XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    private static final String NOMBRE_TALLER = "Taller Peñalosa & Cía";
    private static final LocalDate DESDE = LocalDate.of(2026, 9, 1);
    private static final LocalDate HASTA = LocalDate.of(2026, 9, 30);
    private static final String RANGO = "&desde=2026-09-01&hasta=2026-09-30";
    private static final String RANGO_OCTUBRE = "&desde=2026-10-01&hasta=2026-10-31";
    private static final List<String> FILAS_ESTADO = List.of("Ventas totales", "Compras totales", "Utilidad bruta",
            "Otros ingresos", "Gastos operativos", "Utilidad neta");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private SalesFixture fixture;

    @Autowired
    private SalesService salesService;

    @Autowired
    private PurchasesService purchasesService;

    @Autowired
    private TreasuryService treasuryService;

    @Autowired
    private ReportExportService reportExportService;

    @Autowired
    private TenantRepository tenantRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private Clock clock;

    private Long tenantUno;
    private String tokenUno;
    private String tokenDos;
    private Account caja;
    private Account banco;
    private Account cuentaDeDos;

    @BeforeEach
    void seed() throws Exception {
        String slugUno = "taller-uno-" + System.nanoTime();
        String slugDos = "taller-dos-" + System.nanoTime();
        tenantUno = crearTenantConUsuario(slugUno, NOMBRE_TALLER);
        Long tenantDos = crearTenantConUsuario(slugDos, "Otro taller");
        tokenUno = login(slugUno);
        tokenDos = login(slugDos);

        fixture.usarTenant(tenantDos);
        cuentaDeDos = crearCuenta("Caja Ajena", 0.0);
        treasuryService.registrarIngresoOcasional(cuentaDeDos, "Ingreso ajeno", 777000.0,
                LocalDate.of(2026, 9, 15), null);
        TenantContext.clear();
    }

    @AfterEach
    void cleanUp() {
        TenantContext.clear();
    }

    // ---- Semilla ----

    private Long crearTenantConUsuario(String slug, String nombre) {
        Tenant tenant = new Tenant();
        tenant.setName(nombre);
        tenant.setSlug(slug);
        tenant.setActive(Boolean.TRUE);
        Long tenantId = tenantRepository.save(tenant).getId();
        TenantContext.setTenantId(tenantId);
        try {
            User user = new User();
            user.setUsername("admin");
            user.setPasswordHash(passwordEncoder.encode(PASSWORD));
            user.setActive(true);
            user.setRole(Role.ADMIN);
            userRepository.save(user);
        } finally {
            TenantContext.clear();
        }
        return tenantId;
    }

    private String login(String slug) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"tenantSlug":"%s","username":"admin","password":"%s"}
                                """.formatted(slug, PASSWORD)))
                .andExpect(status().isOk())
                .andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(), "$.token");
    }

    private Account crearCuenta(String nombre, double saldoInicial) {
        Account cuenta = new Account();
        cuenta.setName(nombre);
        cuenta.setType(AccountType.EFECTIVO);
        cuenta.setInitialBalance(saldoInicial);
        return treasuryService.saveAccount(cuenta);
    }

    /** El escenario del javadoc de la clase, en el tenant uno. */
    private void escenario() {
        fixture.usarTenant(tenantUno);
        Product llanta = fixture.crearProducto(fixture.crearCategoriaConIva(), "LLA-001", 40000.0, 20);
        Counterparty cliente = fixture.crearCliente("Transportes Andina", "900111222");
        Counterparty proveedor = fixture.crearProveedor("Llantas del Norte", "800333444");
        caja = crearCuenta("Caja General", 1000000.0);
        banco = crearCuenta("Bancolombia", 500000.0);

        treasuryService.registrarIngresoOcasional(caja, "Aporte de capital", 200000.0, LocalDate.of(2026, 8, 20), null);
        treasuryService.registrarIngresoOcasional(caja, "Venta de chatarra", 50000.0, LocalDate.of(2026, 9, 2), null);
        treasuryService.registrarGastoOperativo(caja, "Pago de energía", 30000.0, LocalDate.of(2026, 9, 3), null);
        treasuryService.registrarTransferencia(caja, banco, "Traslado a Bancolombia", 100000.0, LocalDate.of(2026, 9, 4));
        vender("FV-0100", cliente, PaymentType.CONTADO, LocalDate.of(2026, 9, 5), 100000.0, llanta);
        Sale credito = vender("FV-0101", cliente, PaymentType.CREDITO, LocalDate.of(2026, 9, 6), 200000.0, llanta);
        purchasesService.crearFactura("FAC-500", proveedor, LocalDate.of(2026, 9, 7), null,
                com.servibox.backend.purchases.entity.PaymentType.CONTADO, caja, "Efectivo",
                List.of(new PurchasesService.LineaCompra(llanta.getId(), 1, 50000.0)));
        Sale anulada = vender("FV-0102", cliente, PaymentType.CONTADO, LocalDate.of(2026, 9, 8), 80000.0, llanta);
        salesService.registrarAbono(credito.getId(), caja.getId(), 100000.0, LocalDate.of(2026, 9, 10));
        salesService.anularFactura(anulada.getId());
        treasuryService.registrarGastoOperativo(caja, "Gasto de octubre", 10000.0, LocalDate.of(2026, 10, 5), null);
        TenantContext.clear();
    }

    private Sale vender(String numero, Counterparty cliente, PaymentType tipo, LocalDate fecha, double precio,
                        Product producto) {
        return salesService.crearFactura(numero, cliente, fecha, fecha.plusDays(30), tipo, tipo == PaymentType.CONTADO ? caja : null,
                "Efectivo", List.of(new SalesService.LineaFactura(producto.getId(), 1, precio)));
    }

    // ---- Peticiones y lectura de archivos ----

    private ResultActions exportar(String token, String query) throws Exception {
        return mockMvc.perform(get("/api/reporting/export?" + query)
                .header("Authorization", "Bearer " + token)
                .accept(MediaType.APPLICATION_JSON));
    }

    private byte[] descargar(String query, String contentType, String archivo) throws Exception {
        return exportar(tokenUno, query)
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", contentType))
                .andExpect(header().string("Content-Disposition", "attachment; filename=\"" + archivo + "\""))
                .andReturn().getResponse().getContentAsByteArray();
    }

    private static String textoPdf(byte[] pdf) throws IOException {
        assertThat(new String(pdf, 0, 5)).isEqualTo("%PDF-");
        try (PDDocument documento = Loader.loadPDF(pdf)) {
            return new PDFTextStripper().getText(documento);
        }
    }

    private static Sheet hoja(byte[] xlsx) throws IOException {
        return new XSSFWorkbook(new ByteArrayInputStream(xlsx)).getSheetAt(0);
    }

    /** La fila cuya primera celda es la etiqueta. */
    private static Row fila(Sheet hoja, String etiqueta) {
        for (Row row : hoja) {
            Cell primera = row.getCell(0);
            if (primera != null && primera.getCellType() == CellType.STRING
                    && primera.getStringCellValue().equals(etiqueta)) {
                return row;
            }
        }
        throw new AssertionError("No hay fila " + etiqueta);
    }

    private static double monto(Sheet hoja, String etiqueta) {
        Row row = fila(hoja, etiqueta);
        Cell celda = row.getCell(row.getLastCellNum() - 1);
        assertThat(celda.getCellType()).isEqualTo(CellType.NUMERIC);
        return celda.getNumericCellValue();
    }

    private static List<String> textos(Sheet hoja) {
        List<String> todos = new ArrayList<>();
        for (Row row : hoja) {
            for (Cell celda : row) {
                if (celda.getCellType() == CellType.STRING) {
                    todos.add(celda.getStringCellValue());
                }
            }
        }
        return todos;
    }

    /** Las etiquetas de la primera columna que son filas del Estado de resultados, en orden. */
    private static List<String> filasEstado(Sheet hoja) {
        List<String> filas = new ArrayList<>();
        for (Row row : hoja) {
            Cell primera = row.getCell(0);
            if (primera != null && primera.getCellType() == CellType.STRING
                    && FILAS_ESTADO.contains(primera.getStringCellValue())) {
                filas.add(primera.getStringCellValue());
            }
        }
        return filas;
    }

    /** Cada etiqueta seguida de su valor, en ese orden, dentro del texto del PDF. */
    private static void enOrden(String texto, String... etiquetasYValores) {
        int desde = 0;
        for (String parte : etiquetasYValores) {
            int pos = texto.indexOf(parte, desde);
            assertThat(pos).as("'%s' despues de la posicion %d", parte, desde).isGreaterThanOrEqualTo(0);
            desde = pos + parte.length();
        }
    }

    private static String colorTexto(Cell celda) {
        return ((XSSFCellStyle) celda.getCellStyle()).getFont().getXSSFColor().getARGBHex();
    }

    private String kpisJson(String rango) throws Exception {
        return mockMvc.perform(get("/api/reporting/kpis?" + rango.substring(1))
                        .header("Authorization", "Bearer " + tokenUno))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    private static double kpi(String json, String campo) {
        return ((Number) JsonPath.read(json, "$." + campo)).doubleValue();
    }

    private String pieEsperado() {
        return "Generado el " + LocalDateTime.now(clock).format(DateTimeFormatter.ofPattern("dd/MM/yyyy"));
    }

    // ---- Estado de resultados ----

    @Test
    void elEstadoDeResultadosEnElModeloTieneLasCifrasDeKpis() throws Exception {
        escenario();
        MvcResult kpis = mockMvc.perform(get("/api/reporting/kpis?" + RANGO.substring(1))
                        .header("Authorization", "Bearer " + tokenUno))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalSales").value(357000.0))
                .andExpect(jsonPath("$.totalPurchases").value(59500.0))
                .andExpect(jsonPath("$.operatingExpenses").value(30000.0))
                .andExpect(jsonPath("$.otherIncome").value(50000.0))
                .andExpect(jsonPath("$.netProfit").value(317500.0))
                .andReturn();
        String json = kpis.getResponse().getContentAsString();

        fixture.usarTenant(tenantUno);
        ReportModel modelo = reportExportService.estadoDeResultados(DESDE, HASTA);
        assertThat(modelo.rows()).extracting(f -> f.get(0)).containsExactly("Ventas totales", "Compras totales",
                "Utilidad bruta", "Otros ingresos", "Gastos operativos");
        assertThat(modelo.rows()).extracting(f -> f.get(1)).containsExactly(
                ((Number) JsonPath.read(json, "$.totalSales")).doubleValue(),
                -((Number) JsonPath.read(json, "$.totalPurchases")).doubleValue(),
                ((Number) JsonPath.read(json, "$.grossProfit")).doubleValue(),
                ((Number) JsonPath.read(json, "$.otherIncome")).doubleValue(),
                -((Number) JsonPath.read(json, "$.operatingExpenses")).doubleValue());
        assertThat(modelo.result().label()).isEqualTo("Utilidad neta");
        assertThat(modelo.result().amount()).isEqualTo(((Number) JsonPath.read(json, "$.netProfit")).doubleValue());
        assertThat(modelo.tenantName()).isEqualTo(NOMBRE_TALLER);
    }

    @Test
    void elEstadoDeResultadosEnPdf() throws Exception {
        escenario();
        String texto = textoPdf(descargar("type=ESTADO_RESULTADOS&format=PDF" + RANGO,
                "application/pdf", "estado-resultados_2026-09-01_2026-09-30.pdf"));

        assertThat(texto).contains(NOMBRE_TALLER, "Estado de resultados", "Período: 01/09/2026 a 30/09/2026",
                "Las facturas anuladas no se incluyen",
                pieEsperado(), NOMBRE_TALLER + " · Pág. 1 de 1");
        // Las seis filas en orden, cada una con su valor: el ingreso del rango suma, positivo.
        enOrden(texto, "Ventas totales", "$ 357.000", "Compras totales", "-$ 59.500", "Utilidad bruta", "$ 297.500",
                "Otros ingresos", "$ 50.000", "Gastos operativos", "-$ 30.000", "Utilidad neta", "$ 317.500");
        // El ingreso ocasional del 20/08 queda fuera del rango, igual que en kpis.
        assertThat(texto).doesNotContain("200.000", "-$ 50.000");
    }

    @Test
    void elEstadoDeResultadosEnPdfSinIngresosOcasionalesMuestraOtrosIngresosEnCero() throws Exception {
        escenario();
        String json = kpisJson(RANGO_OCTUBRE);
        assertThat(kpi(json, "otherIncome")).isEqualTo(0.0);
        assertThat(kpi(json, "netProfit")).isEqualTo(-10000.0);

        String texto = textoPdf(descargar("type=ESTADO_RESULTADOS&format=PDF" + RANGO_OCTUBRE,
                "application/pdf", "estado-resultados_2026-10-01_2026-10-31.pdf"));
        enOrden(texto, "Ventas totales", "$ 0", "Compras totales", "$ 0", "Utilidad bruta", "$ 0",
                "Otros ingresos", "$ 0", "Gastos operativos", "-$ 10.000", "Utilidad neta", "-$ 10.000");
        assertThat(texto).contains("Las facturas anuladas no se incluyen");
    }

    @Test
    void elEstadoDeResultadosEnExcelTieneCeldasNumericasIgualesAKpis() throws Exception {
        escenario();
        Sheet hoja = hoja(descargar("type=ESTADO_RESULTADOS&format=XLSX" + RANGO,
                XLSX, "estado-resultados_2026-09-01_2026-09-30.xlsx"));

        assertThat(hoja.getSheetName()).isEqualTo("Estado de resultados");
        assertThat(textos(hoja)).contains(NOMBRE_TALLER, "Estado de resultados",
                "Período: 01/09/2026 a 30/09/2026", "Las facturas anuladas no se incluyen");
        assertThat(monto(hoja, "Ventas totales")).isEqualTo(357000.0);
        assertThat(monto(hoja, "Compras totales")).isEqualTo(-59500.0);
        assertThat(filasEstado(hoja)).containsExactlyElementsOf(FILAS_ESTADO);
        String json = kpisJson(RANGO);
        assertThat(monto(hoja, "Ventas totales")).isEqualTo(357000.0).isEqualTo(kpi(json, "totalSales"));
        assertThat(monto(hoja, "Compras totales")).isEqualTo(-59500.0).isEqualTo(-kpi(json, "totalPurchases"));
        assertThat(monto(hoja, "Utilidad bruta")).isEqualTo(297500.0).isEqualTo(kpi(json, "grossProfit"));
        assertThat(monto(hoja, "Otros ingresos")).isEqualTo(50000.0).isEqualTo(kpi(json, "otherIncome"));
        assertThat(monto(hoja, "Gastos operativos")).isEqualTo(-30000.0).isEqualTo(-kpi(json, "operatingExpenses"));
        assertThat(monto(hoja, "Utilidad neta")).isEqualTo(317500.0).isEqualTo(kpi(json, "netProfit"));
        assertThat(fila(hoja, "Ventas totales").getCell(1).getCellStyle().getDataFormatString()).isEqualTo("#,##0");
        // Otros ingresos es una fila mas: mismo formato y texto normal, no el verde de la utilidad.
        Cell otros = fila(hoja, "Otros ingresos").getCell(1);
        assertThat(otros.getCellStyle().getDataFormatString()).isEqualTo("#,##0");
        assertThat(colorTexto(otros)).isEqualTo(colorTexto(fila(hoja, "Ventas totales").getCell(1)))
                .isEqualTo("FF000000");
        assertThat(((XSSFCellStyle) otros.getCellStyle()).getFont().getBold()).isFalse();
        assertThat(hoja.getPaneInformation().isFreezePane()).isTrue();
    }

    @Test
    void elEstadoDeResultadosEnExcelSinIngresosOcasionalesTieneOtrosIngresosEnCero() throws Exception {
        escenario();
        Sheet hoja = hoja(descargar("type=ESTADO_RESULTADOS&format=XLSX" + RANGO_OCTUBRE,
                XLSX, "estado-resultados_2026-10-01_2026-10-31.xlsx"));
        String json = kpisJson(RANGO_OCTUBRE);

        assertThat(filasEstado(hoja)).containsExactlyElementsOf(FILAS_ESTADO);
        assertThat(monto(hoja, "Ventas totales")).isEqualTo(0.0).isEqualTo(kpi(json, "totalSales"));
        assertThat(monto(hoja, "Compras totales")).isEqualTo(0.0);
        assertThat(monto(hoja, "Utilidad bruta")).isEqualTo(0.0).isEqualTo(kpi(json, "grossProfit"));
        assertThat(monto(hoja, "Otros ingresos")).isEqualTo(0.0).isEqualTo(kpi(json, "otherIncome"));
        assertThat(monto(hoja, "Gastos operativos")).isEqualTo(-10000.0).isEqualTo(-kpi(json, "operatingExpenses"));
        assertThat(monto(hoja, "Utilidad neta")).isEqualTo(-10000.0).isEqualTo(kpi(json, "netProfit"));
        assertThat(textos(hoja)).contains("Las facturas anuladas no se incluyen");
    }

    // ---- Movimientos de cuentas ----

    @Test
    void losMovimientosDeTodasLasCuentasEnExcel() throws Exception {
        escenario();
        Sheet hoja = hoja(descargar("type=MOVIMIENTOS&format=XLSX" + RANGO,
                XLSX, "movimientos-cuentas_2026-09-01_2026-09-30.xlsx"));

        assertThat(hoja.getSheetName()).isEqualTo("Movimientos de cuentas");
        Row titulos = fila(hoja, "Fecha");
        assertThat(titulos.getCell(5).getStringCellValue()).isEqualTo("Monto");
        List<String> origenes = new ArrayList<>();
        List<Double> montos = new ArrayList<>();
        List<LocalDate> fechas = new ArrayList<>();
        for (int i = titulos.getRowNum() + 1; ; i++) {
            Row row = hoja.getRow(i);
            if (row == null || row.getCell(0).getCellType() != CellType.NUMERIC) {
                break;
            }
            fechas.add(row.getCell(0).getLocalDateTimeCellValue().toLocalDate());
            origenes.add(row.getCell(4).getStringCellValue());
            montos.add(row.getCell(5).getNumericCellValue());
        }
        // Ascendente; la venta anulada FV-0102 no sale; Banco tambien sale.
        assertThat(fechas).isSorted();
        assertThat(origenes).containsExactly("INGRESO OCASIONAL", "GASTO OPERATIVO", "TRANSFERENCIA",
                "TRANSFERENCIA", "VENTA FV-0100", "COMPRA FAC-500", "VENTA FV-0101");
        assertThat(montos).containsExactly(50000.0, -30000.0, -100000.0, 100000.0, 119000.0, -59500.0, 100000.0);
        assertThat(textos(hoja)).contains("Bancolombia", "Pago de energía", "Todas las cuentas")
                .doesNotContain("Saldo inicial del período", "Saldo final", "Ingreso ajeno");
        assertThat(monto(hoja, "Ingresos")).isEqualTo(369000.0);
        assertThat(monto(hoja, "Egresos")).isEqualTo(-189500.0);
        assertThat(monto(hoja, "Neto")).isEqualTo(179500.0);
        assertThat(((org.apache.poi.xssf.usermodel.XSSFSheet) hoja).getCTWorksheet().isSetAutoFilter()).isTrue();
    }

    @Test
    void losMovimientosDeUnaCuentaLlevanSaldoInicialYFinal() throws Exception {
        escenario();
        Sheet hoja = hoja(descargar("type=MOVIMIENTOS&format=XLSX&accountId=" + caja.getId() + RANGO,
                XLSX, "movimientos-cuentas_2026-09-01_2026-09-30.xlsx"));

        assertThat(textos(hoja)).contains("Cuenta: Caja General").doesNotContain("Bancolombia");
        // 1.000.000 inicial + 200.000 del 20/08.
        assertThat(monto(hoja, "Saldo inicial del período")).isEqualTo(1200000.0);
        assertThat(monto(hoja, "Ingresos")).isEqualTo(269000.0);
        assertThat(monto(hoja, "Egresos")).isEqualTo(-189500.0);
        assertThat(monto(hoja, "Neto")).isEqualTo(79500.0);
        // El gasto del 05/10 no entra en el saldo final del 30/09.
        assertThat(monto(hoja, "Saldo final")).isEqualTo(1279500.0);

        // La invariante: el saldo a una fecha posterior a todo es saldo_actual.
        fixture.usarTenant(tenantUno);
        Account actual = treasuryService.findAccountById(caja.getId()).orElseThrow();
        assertThat(treasuryService.saldoAl(actual, LocalDate.of(2027, 1, 1)))
                .isCloseTo(actual.getCurrentBalance(), within(0.001));
        assertThat(actual.getCurrentBalance()).isEqualTo(1269500.0);
    }

    @Test
    void losMovimientosDeUnaCuentaEnPdf() throws Exception {
        escenario();
        String texto = textoPdf(descargar("type=MOVIMIENTOS&format=PDF&accountId=" + caja.getId() + RANGO,
                "application/pdf", "movimientos-cuentas_2026-09-01_2026-09-30.pdf"));

        assertThat(texto).contains(NOMBRE_TALLER, "Movimientos de cuentas", "Período: 01/09/2026 a 30/09/2026",
                "Cuenta: Caja General", "Fecha", "Cuenta", "Tipo", "Concepto", "Origen", "Monto",
                "02/09/2026", "Venta de chatarra", "Pago de energía", "-$ 30.000", "VENTA FV-0100", "$ 119.000",
                "COMPRA FAC-500", "-$ 59.500", "TRANSFERENCIA", "-$ 100.000",
                "Saldo inicial del período", "$ 1.200.000", "Saldo final", "$ 1.279.500",
                pieEsperado(), "Pág. 1 de 1");
        // Bancolombia solo aparece en el concepto de la transferencia, nunca como cuenta de una fila.
        assertThat(texto).doesNotContain("FV-0102", "Bancolombia Ingreso", "Bancolombia Egreso", "Ingreso ajeno");
        assertThat(texto.indexOf("02/09/2026")).isLessThan(texto.indexOf("10/09/2026"));
    }

    @Test
    void losMovimientosDeTodasLasCuentasEnPdf() throws Exception {
        escenario();
        String texto = textoPdf(descargar("type=MOVIMIENTOS&format=PDF" + RANGO,
                "application/pdf", "movimientos-cuentas_2026-09-01_2026-09-30.pdf"));

        assertThat(texto).contains("Todas las cuentas", "Bancolombia", "Ingresos", "$ 369.000",
                "Egresos", "-$ 189.500", "Neto", "$ 179.500");
        assertThat(texto).doesNotContain("Saldo inicial del período", "Saldo final", "Ingreso ajeno");
    }

    // ---- Sin datos ----

    @Test
    void unPeriodoSinMovimientosDaUnArchivoQueLoDice() throws Exception {
        String rango = "&desde=2026-01-01&hasta=2026-01-31";
        String texto = textoPdf(descargar("type=MOVIMIENTOS&format=PDF" + rango,
                "application/pdf", "movimientos-cuentas_2026-01-01_2026-01-31.pdf"));
        assertThat(texto).contains("Sin movimientos en el período", "Ingresos", "$ 0");

        Sheet hoja = hoja(descargar("type=MOVIMIENTOS&format=XLSX" + rango,
                XLSX, "movimientos-cuentas_2026-01-01_2026-01-31.xlsx"));
        assertThat(textos(hoja)).contains("Sin movimientos en el período");
        assertThat(monto(hoja, "Neto")).isEqualTo(0.0);
    }

    // ---- Errores ----

    @Test
    void sinTokenEs401() throws Exception {
        mockMvc.perform(get("/api/reporting/export?type=ESTADO_RESULTADOS&format=PDF" + RANGO))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void losRangosInvalidosSon400() throws Exception {
        exportar(tokenUno, "type=MOVIMIENTOS&format=PDF&desde=2026-09-30&hasta=2026-09-01")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("La fecha desde no puede ser posterior a la fecha hasta"));
        exportar(tokenUno, "type=ESTADO_RESULTADOS&format=XLSX&desde=2025-01-01&hasta=2026-01-02")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("El rango de un reporte no puede pasar de 366 dias"));
        exportar(tokenUno, "type=MOVIMIENTOS&format=XLSX&desde=2026-09-01")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("El rango necesita fecha desde y fecha hasta"));
        // 366 dias justos si valen.
        exportar(tokenUno, "type=ESTADO_RESULTADOS&format=PDF&desde=2027-01-01&hasta=2028-01-01")
                .andExpect(status().isOk());
    }

    @Test
    void typeYFormatInvalidosOFaltantesSon400() throws Exception {
        exportar(tokenUno, "type=VENTAS&format=PDF" + RANGO)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(
                        "Valor invalido 'VENTAS' en el parametro type. Valores permitidos: ESTADO_RESULTADOS, MOVIMIENTOS"));
        exportar(tokenUno, "type=MOVIMIENTOS&format=CSV" + RANGO)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(
                        "Valor invalido 'CSV' en el parametro format. Valores permitidos: PDF, XLSX"));
        exportar(tokenUno, "format=PDF" + RANGO)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
    }

    @Test
    void unaCuentaDeOtroTenantOUnaCuentaEnElEstadoDeResultadosSon400() throws Exception {
        escenario();
        exportar(tokenUno, "type=MOVIMIENTOS&format=PDF&accountId=" + cuentaDeDos.getId() + RANGO)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Cuenta no encontrada: " + cuentaDeDos.getId()));
        exportar(tokenUno, "type=ESTADO_RESULTADOS&format=PDF&accountId=" + caja.getId() + RANGO)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("accountId solo aplica al reporte MOVIMIENTOS"));
    }

    @Test
    void losMovimientosDeOtroTenantNuncaAparecen() throws Exception {
        escenario();
        Sheet hoja = hoja(exportar(tokenDos, "type=MOVIMIENTOS&format=XLSX" + RANGO)
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray());
        assertThat(textos(hoja)).contains("Otro taller", "Ingreso ajeno")
                .doesNotContain(NOMBRE_TALLER, "Venta de chatarra", "Caja General");
        assertThat(monto(hoja, "Ingresos")).isEqualTo(777000.0);

        String texto = textoPdf(exportar(tokenUno, "type=MOVIMIENTOS&format=PDF" + RANGO)
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray());
        assertThat(texto).doesNotContain("Ingreso ajeno", "Caja Ajena", "777.000");
    }
}
