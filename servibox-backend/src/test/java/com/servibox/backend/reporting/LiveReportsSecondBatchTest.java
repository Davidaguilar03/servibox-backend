package com.servibox.backend.reporting;

import com.jayway.jsonpath.JsonPath;
import com.servibox.backend.auth.entity.Role;
import com.servibox.backend.auth.entity.User;
import com.servibox.backend.auth.repository.UserRepository;
import com.servibox.backend.counterparties.entity.Counterparty;
import com.servibox.backend.inventory.entity.Product;
import com.servibox.backend.inventory.entity.ProductCategory;
import com.servibox.backend.inventory.entity.StockStatus;
import com.servibox.backend.inventory.service.InventoryService;
import com.servibox.backend.purchases.entity.Purchase;
import com.servibox.backend.purchases.service.PurchasesService;
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
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.hamcrest.Matchers.closeTo;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Segunda tanda de reportes en vivo: /inventory, /cash-flow, /income-statement, /vat y
 * /movements-report, su exportacion, el mes por defecto de /kpis, /movements e /iva y el filtro
 * de categoria de Compras por lineas.
 *
 * Escenario (IVA 19 en LLANTAS y SERVICIOS; BATERIAS sin IVA, umbrales amarillo 1 y rojo 0;
 * LLANTAS amarillo 5 y rojo 2). Caja arranca en 1.000.000 y Banco en 500.000.
 * Productos: LLA-001 costo 40.000 stock 20; LLA-002 costo 10.000 stock 4; LLA-003 costo 10.000
 * stock 2; LLA-004 costo 10.000 stock 0; BAT-001 costo 100.000 stock 3; servicio SRV-ALI 35.000.
 * 20/08: ingreso ocasional 200.000 a Caja (antes del rango).
 * Septiembre: 01 ingreso ocasional 50.000 (Caja); 02 gasto 30.000 (Caja); 03 transferencia Caja a
 * Banco 100.000; 05 FV-1 contado (Caja) 2 LLA-001 a 100.000, total 238.000; 06 FV-2 credito
 * BAT-001 a 150.000 mas SRV-ALI, total 191.650; 07 FV-3 contado (Caja) LLA-001 a 100.000,
 * 119.000, anulada; 08 FC-1 contado (Caja) 5 LLA-001 a 50.000 mas BAT-001 a 100.000: subtotal
 * 350.000, IVA 47.500, total 397.500; 09 FC-2 credito LLA-002 a 10.000, total 11.900; 10 abono de
 * FV-2 50.000 a Banco; 12 pago de FC-2 5.000 desde Banco.
 *
 * Stock final: LLA-001 23 (NORMAL), BAT-001 3 (NORMAL), LLA-002 5 (BAJO), LLA-003 2 (CRITICO),
 * LLA-004 0 (SIN_STOCK). Flujo de septiembre sin cuenta: saldo inicial 1.700.000, entradas
 * 338.000, salidas 432.500, neto -94.500, final 1.605.500.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class LiveReportsSecondBatchTest {

    private static final String PASSWORD = "clave-correcta";
    private static final String XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    private static final String RANGO = "desde=2026-09-01&hasta=2026-09-30";
    private static final double DELTA = 0.001;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private SalesFixture fixture;

    @Autowired
    private SalesService salesService;

    @Autowired
    private PurchasesService purchasesService;

    @Autowired
    private InventoryService inventoryService;

    @Autowired
    private TreasuryService treasuryService;

    @Autowired
    private TenantRepository tenantRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private Clock clock;

    private Long tenantUno;
    private Long tenantDos;
    private String tokenUno;

    private Account caja;
    private Account banco;
    private Account cuentaAjena;
    private ProductCategory llantas;
    private ProductCategory baterias;
    private Product lla1;
    private Product lla2;
    private Product bateria;
    private Product alineacion;
    private Counterparty cliente;
    private Counterparty norte;
    private Counterparty sur;

    @BeforeEach
    void seed() throws Exception {
        String slugUno = "taller-uno-" + System.nanoTime();
        String slugDos = "taller-dos-" + System.nanoTime();
        tenantUno = crearTenantConUsuario(slugUno, "Taller Uno");
        tenantDos = crearTenantConUsuario(slugDos, "Taller Dos");
        tokenUno = login(slugUno);

        // Del tenant dos: un producto, una cuenta y una venta de contado en septiembre.
        fixture.usarTenant(tenantDos);
        Product ajeno = fixture.crearProducto(fixture.crearCategoriaConIva(), "AJE-1", 1000.0, 10);
        cuentaAjena = cuenta("Caja Ajena", 900000.0);
        salesService.crearFactura("AJ-1", fixture.crearCliente("Cliente Ajeno", "111"), LocalDate.of(2026, 9, 15),
                null, PaymentType.CONTADO, cuentaAjena, "Efectivo",
                List.of(new SalesService.LineaFactura(ajeno.getId(), 1, 777000.0)));
        treasuryService.registrarGastoOperativo(cuentaAjena, "Gasto ajeno", 12345.0, LocalDate.of(2026, 9, 15), null);
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

    private Account cuenta(String nombre, double saldo) {
        Account cuenta = new Account();
        cuenta.setName(nombre);
        cuenta.setType(AccountType.EFECTIVO);
        cuenta.setInitialBalance(saldo);
        return treasuryService.saveAccount(cuenta);
    }

    private void base() {
        fixture.usarTenant(tenantUno);
        llantas = fixture.crearCategoriaConIva();
        ProductCategory sinIva = new ProductCategory();
        sinIva.setName("BATERIAS");
        sinIva.setColor("#2ecc71");
        sinIva.setYellowStockMin(1);
        sinIva.setRedStockMin(0);
        sinIva.setTargetMargin(0.0);
        sinIva.setTaxTypes(Set.of());
        baterias = inventoryService.saveCategory(sinIva);
        lla1 = fixture.crearProducto(llantas, "LLA-001", 40000.0, 20);
        lla2 = fixture.crearProducto(llantas, "LLA-002", 10000.0, 4);
        fixture.crearProducto(llantas, "LLA-003", 10000.0, 2);
        fixture.crearProducto(llantas, "LLA-004", 10000.0, 0);
        bateria = fixture.crearProducto(baterias, "BAT-001", 100000.0, 3);
        alineacion = inventoryService.crearServicio("SRV-ALI", "Alineacion", 35000.0, true);
        cliente = fixture.crearCliente("Transportes Andina", "900111222");
        norte = fixture.crearProveedor("Llantas del Norte", "800111222");
        sur = fixture.crearProveedor("Llantas del Sur", "800333444");
        caja = cuenta("Caja General", 1000000.0);
        banco = cuenta("Bancolombia", 500000.0);
    }

    /** El escenario del javadoc de la clase. */
    private void escenario() {
        base();
        treasuryService.registrarIngresoOcasional(caja, "Aporte", 200000.0, LocalDate.of(2026, 8, 20), null);
        treasuryService.registrarIngresoOcasional(caja, "Chatarra", 50000.0, LocalDate.of(2026, 9, 1), null);
        treasuryService.registrarGastoOperativo(caja, "Energia", 30000.0, LocalDate.of(2026, 9, 2), null);
        treasuryService.registrarTransferencia(caja, banco, "Traslado", 100000.0, LocalDate.of(2026, 9, 3));
        venta("FV-1", PaymentType.CONTADO, LocalDate.of(2026, 9, 5), linea(lla1, 2, 100000.0));
        Sale fv2 = venta("FV-2", PaymentType.CREDITO, LocalDate.of(2026, 9, 6), linea(bateria, 1, 150000.0),
                linea(alineacion, 1, 35000.0));
        Sale fv3 = venta("FV-3", PaymentType.CONTADO, LocalDate.of(2026, 9, 7), linea(lla1, 1, 100000.0));
        purchasesService.crearFactura("FC-1", norte, LocalDate.of(2026, 9, 8), null,
                com.servibox.backend.purchases.entity.PaymentType.CONTADO, caja, "Efectivo",
                List.of(compra(lla1, 5, 50000.0), compra(bateria, 1, 100000.0)));
        Purchase fc2 = purchasesService.crearFactura("FC-2", sur, LocalDate.of(2026, 9, 9), LocalDate.of(2026, 10, 9),
                com.servibox.backend.purchases.entity.PaymentType.CREDITO, null, null, List.of(compra(lla2, 1, 10000.0)));
        salesService.registrarAbono(fv2.getId(), banco.getId(), 50000.0, LocalDate.of(2026, 9, 10));
        purchasesService.registrarPago(fc2.getId(), banco.getId(), 5000.0, LocalDate.of(2026, 9, 12));
        salesService.anularFactura(fv3.getId());
        TenantContext.clear();
    }

    private static SalesService.LineaFactura linea(Product producto, int cantidad, double precio) {
        return new SalesService.LineaFactura(producto.getId(), cantidad, precio);
    }

    private static PurchasesService.LineaCompra compra(Product producto, int cantidad, double precio) {
        return new PurchasesService.LineaCompra(producto.getId(), cantidad, precio);
    }

    private Sale venta(String numero, PaymentType tipo, LocalDate fecha, SalesService.LineaFactura... lineas) {
        return salesService.crearFactura(numero, cliente, fecha, fecha.plusDays(30), tipo,
                tipo == PaymentType.CONTADO ? caja : null, "Efectivo", List.of(lineas));
    }

    // ---- Peticiones ----

    private ResultActions pedir(String ruta) throws Exception {
        return mockMvc.perform(get("/api/reporting/" + ruta).header("Authorization", "Bearer " + tokenUno)
                .accept(MediaType.APPLICATION_JSON));
    }

    private ResultActions ok(String ruta) throws Exception {
        return pedir(ruta).andExpect(status().isOk());
    }

    private String json(String ruta) throws Exception {
        return ok(ruta).andReturn().getResponse().getContentAsString();
    }

    private static double num(String json, String ruta) {
        return ((Number) JsonPath.read(json, ruta)).doubleValue();
    }

    private void error(String ruta, String mensaje) throws Exception {
        pedir(ruta).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value(mensaje));
    }

    private byte[] descargar(String query, String contentType, String archivo) throws Exception {
        return pedir("export?" + query)
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

    private static double numero(Row row, int columna) {
        Cell celda = row.getCell(columna);
        assertThat(celda.getCellType()).as("columna %d de %s", columna, row.getCell(0)).isEqualTo(CellType.NUMERIC);
        return celda.getNumericCellValue();
    }

    private static double ultimo(Sheet hoja, String etiqueta) {
        Row row = fila(hoja, etiqueta);
        return numero(row, row.getLastCellNum() - 1);
    }

    // ---- Estado de stock ----

    @Test
    void elEstadoDeStockEsLaReglaDelFrontend() {
        // Umbrales amarillo 5 y rojo 2, como LLANTAS.
        assertThat(StockStatus.of(0, 5, 2)).isEqualTo(StockStatus.SIN_STOCK);
        assertThat(StockStatus.of(-1, 5, 2)).isEqualTo(StockStatus.SIN_STOCK);
        assertThat(StockStatus.of(1, 5, 2)).isEqualTo(StockStatus.CRITICO);
        assertThat(StockStatus.of(2, 5, 2)).isEqualTo(StockStatus.CRITICO);
        assertThat(StockStatus.of(3, 5, 2)).isEqualTo(StockStatus.BAJO);
        assertThat(StockStatus.of(5, 5, 2)).isEqualTo(StockStatus.BAJO);
        assertThat(StockStatus.of(6, 5, 2)).isEqualTo(StockStatus.NORMAL);
        // Sin umbrales, los por defecto del frontend (5 y 2); sin cantidad, 0.
        assertThat(StockStatus.of(2, null, null)).isEqualTo(StockStatus.CRITICO);
        assertThat(StockStatus.of(null, 5, 2)).isEqualTo(StockStatus.SIN_STOCK);
    }

    // ---- Inventario ----

    @Test
    void elInventarioValorizaElStockYCuentaLaRotacionSinServicios() throws Exception {
        escenario();
        LocalDate hoy = LocalDate.now(clock);
        String json = json("inventory?" + RANGO);
        assertThat(JsonPath.<String>read(json, "$.corte")).isEqualTo(hoy.toString());
        assertThat(JsonPath.<List<String>>read(json, "$.rows[*].code"))
                .containsExactly("LLA-001", "BAT-001", "LLA-002", "LLA-003", "LLA-004");
        assertThat(JsonPath.<List<String>>read(json, "$.rows[*].stockStatus"))
                .containsExactly("NORMAL", "NORMAL", "BAJO", "CRITICO", "SIN_STOCK");
        assertThat(num(json, "$.rows[0].stock")).isEqualTo(23.0);
        assertThat(num(json, "$.rows[0].unitCost")).isEqualTo(50000.0);
        assertThat(num(json, "$.rows[0].costValue")).isEqualTo(1150000.0);
        assertThat(num(json, "$.rows[0].unitsSold")).isEqualTo(2.0);
        assertThat(JsonPath.<String>read(json, "$.rows[0].lastSaleDate")).isEqualTo("2026-09-05");
        assertThat(num(json, "$.rows[0].daysWithoutSale"))
                .isEqualTo((double) ChronoUnit.DAYS.between(LocalDate.of(2026, 9, 5), hoy));
        assertThat(JsonPath.<Object>read(json, "$.rows[4].lastSaleDate")).isNull();
        assertThat(JsonPath.<Object>read(json, "$.rows[4].daysWithoutSale")).isNull();

        assertThat(num(json, "$.summary.productCount")).isEqualTo(5.0);
        assertThat(num(json, "$.summary.unitsInStock")).isEqualTo(33.0);
        assertThat(num(json, "$.summary.costValue")).isEqualTo(1520000.0);
        assertThat(num(json, "$.summary.lowStockCount")).isEqualTo(1.0);
        assertThat(num(json, "$.summary.criticalStockCount")).isEqualTo(1.0);
        assertThat(num(json, "$.summary.outOfStockCount")).isEqualTo(1.0);
        assertThat(num(json, "$.summary.unitsSoldInPeriod")).isEqualTo(3.0);
        double precioPorStock = 0.0;
        for (int i = 0; i < 5; i++) {
            precioPorStock += num(json, "$.rows[" + i + "].salePrice") * num(json, "$.rows[" + i + "].stock");
        }
        assertThat(num(json, "$.summary.saleValue")).isCloseTo(precioPorStock, within(0.01));
        assertThat(JsonPath.<List<String>>read(json, "$.series[*].label"))
                .containsExactly("Producto LLA-001", "Producto BAT-001");
    }

    @Test
    void losFiltrosDelInventarioAcotanPorCategoriaYEstado() throws Exception {
        escenario();
        ok("inventory?estadoStock=CRITICO")
                .andExpect(jsonPath("$.rows[*].code", contains("LLA-003")))
                .andExpect(jsonPath("$.appliedFilters[0]").value("Estado de stock: Crítico"))
                .andExpect(jsonPath("$.summary.productCount").value(1));
        ok("inventory?estadoStock=SIN_STOCK").andExpect(jsonPath("$.rows[*].code", contains("LLA-004")));
        ok("inventory?estadoStock=BAJO").andExpect(jsonPath("$.rows[*].code", contains("LLA-002")));
        ok("inventory?categoriaId=" + baterias.getId())
                .andExpect(jsonPath("$.rows[*].code", contains("BAT-001")))
                .andExpect(jsonPath("$.appliedFilters[0]").value("Categoría: BATERIAS"));
        // Sin fechas: rotacion del mes en curso, sin ventas en el escenario.
        ok("inventory").andExpect(jsonPath("$.summary.unitsSoldInPeriod").value(0))
                .andExpect(jsonPath("$.rows[*].code", not(hasItem("SRV-ALI"))));
    }

    // ---- Flujo de caja ----

    @Test
    void elFlujoDeCajaSinCuentaExcluyeTransferenciasYCuadraElSaldo() throws Exception {
        escenario();
        String json = json("cash-flow?" + RANGO);
        assertThat(num(json, "$.summary.openingBalance")).isEqualTo(1700000.0);
        assertThat(num(json, "$.summary.inflows")).isEqualTo(338000.0);
        assertThat(num(json, "$.summary.outflows")).isEqualTo(432500.0);
        assertThat(num(json, "$.summary.netFlow")).isEqualTo(-94500.0);
        assertThat(num(json, "$.summary.closingBalance")).isEqualTo(1605500.0);
        assertThat(num(json, "$.summary.openingBalance") + num(json, "$.summary.inflows")
                - num(json, "$.summary.outflows")).isCloseTo(num(json, "$.summary.closingBalance"), within(DELTA));
        assertThat(JsonPath.<List<String>>read(json, "$.rows[*].concept")).containsExactly("VENTAS_CONTADO",
                "ABONOS_CLIENTES", "INGRESOS_OCASIONALES", "COMPRAS_CONTADO", "PAGOS_PROVEEDORES", "GASTOS_OPERATIVOS");
        assertThat(num(json, "$.rows[0].amount")).isEqualTo(238000.0);
        assertThat(num(json, "$.rows[0].movementCount")).isEqualTo(1.0);
        assertThat(JsonPath.<String>read(json, "$.rows[0].label")).isEqualTo("Ventas de contado");
        assertThat(JsonPath.<String>read(json, "$.rows[3].direction")).isEqualTo("SALIDA");
        assertThat(num(json, "$.rows[3].amount")).isEqualTo(397500.0);
        assertThat(num(json, "$.rows[4].amount")).isEqualTo(5000.0);
        assertThat(JsonPath.<List<Object>>read(json, "$.series")).hasSize(30);
        assertThat(num(json, "$.series[2].value")).isEqualTo(0.0);
        assertThat(num(json, "$.series[4].value")).isEqualTo(238000.0);
        // El saldo final es el de las cuentas.
        fixture.usarTenant(tenantUno);
        double saldos = treasuryService.saldoAl(caja, LocalDate.of(2026, 9, 30))
                + treasuryService.saldoAl(banco, LocalDate.of(2026, 9, 30));
        TenantContext.clear();
        assertThat(saldos).isCloseTo(1605500.0, within(DELTA));
    }

    @Test
    void elFlujoDeCajaDeUnaCuentaIncluyeSusTransferencias() throws Exception {
        escenario();
        String json = json("cash-flow?" + RANGO + "&cuentaId=" + banco.getId());
        assertThat(JsonPath.<List<String>>read(json, "$.appliedFilters")).containsExactly("Cuenta: Bancolombia");
        assertThat(num(json, "$.summary.openingBalance")).isEqualTo(500000.0);
        assertThat(num(json, "$.summary.inflows")).isEqualTo(150000.0);
        assertThat(num(json, "$.summary.outflows")).isEqualTo(5000.0);
        assertThat(num(json, "$.summary.closingBalance")).isEqualTo(645000.0);
        assertThat(JsonPath.<List<String>>read(json, "$.rows[*].concept")).contains("TRANSFERENCIAS_RECIBIDAS",
                "TRANSFERENCIAS_ENVIADAS");
        assertThat(JsonPath.<List<Double>>read(json, "$.rows[?(@.concept == 'TRANSFERENCIAS_RECIBIDAS')].amount"))
                .containsExactly(100000.0);
        assertThat(num(json, "$.series[2].value")).isEqualTo(100000.0);

        String caja = json("cash-flow?" + RANGO + "&cuentaId=" + this.caja.getId());
        assertThat(num(caja, "$.summary.openingBalance") + num(caja, "$.summary.inflows")
                - num(caja, "$.summary.outflows")).isCloseTo(num(caja, "$.summary.closingBalance"), within(DELTA));
        assertThat(num(caja, "$.summary.closingBalance")).isEqualTo(960500.0);
    }

    // ---- Estado de resultados ----

    @Test
    void elEstadoDeResultadosEnVivoCuadraConKpisYConLaExportacion() throws Exception {
        escenario();
        String json = json("income-statement?" + RANGO);
        String kpis = json("kpis?" + RANGO);
        assertThat(num(json, "$.summary.revenue")).isEqualTo(num(kpis, "$.netSales")).isEqualTo(385000.0);
        assertThat(num(json, "$.summary.costOfSales")).isEqualTo(num(kpis, "$.costOfSales")).isEqualTo(180000.0);
        assertThat(num(json, "$.summary.grossProfit")).isEqualTo(num(kpis, "$.grossProfit")).isEqualTo(205000.0);
        assertThat(num(json, "$.summary.otherIncome")).isEqualTo(num(kpis, "$.otherIncome")).isEqualTo(50000.0);
        assertThat(num(json, "$.summary.operatingExpenses")).isEqualTo(num(kpis, "$.operatingExpenses"));
        assertThat(num(json, "$.summary.netProfit")).isEqualTo(num(kpis, "$.netProfit")).isEqualTo(225000.0);
        assertThat(num(json, "$.summary.grossMarginPercent")).isEqualTo(53.25);
        assertThat(num(json, "$.summary.netMarginPercent")).isEqualTo(58.44);
        assertThat(JsonPath.<List<String>>read(json, "$.rows[*].line")).containsExactly("VENTAS", "COSTO_VENTAS",
                "UTILIDAD_BRUTA", "OTROS_INGRESOS", "GASTOS_OPERATIVOS", "UTILIDAD_NETA");
        assertThat(num(json, "$.rows[1].amount")).isEqualTo(-180000.0);
        assertThat(num(json, "$.rows[0].percentOfRevenue")).isEqualTo(100.0);
        assertThat(num(json, "$.rows[1].percentOfRevenue")).isEqualTo(-46.75);

        // Un solo mes: un punto por dia.
        assertThat(JsonPath.<List<Object>>read(json, "$.series")).hasSize(30);
        assertThat(JsonPath.<String>read(json, "$.series[4].label")).isEqualTo("2026-09-05");
        assertThat(num(json, "$.series[4].revenue")).isEqualTo(200000.0);
        assertThat(num(json, "$.series[4].cost")).isEqualTo(80000.0);
        assertThat(num(json, "$.series[4].profit")).isEqualTo(120000.0);
        assertThat(num(json, "$.series[1].profit")).isEqualTo(-30000.0);

        Sheet hoja = hoja(descargar("type=ESTADO_RESULTADOS&format=XLSX&" + RANGO, XLSX,
                "estado-resultados_2026-09-01_2026-09-30.xlsx"));
        assertThat(ultimo(hoja, "Ventas sin IVA")).isEqualTo(num(json, "$.rows[0].amount"));
        assertThat(ultimo(hoja, "Costo de lo vendido")).isEqualTo(num(json, "$.rows[1].amount"));
        assertThat(ultimo(hoja, "Utilidad bruta")).isEqualTo(num(json, "$.rows[2].amount"));
        assertThat(ultimo(hoja, "Otros ingresos")).isEqualTo(num(json, "$.rows[3].amount"));
        assertThat(ultimo(hoja, "Gastos operativos")).isEqualTo(num(json, "$.rows[4].amount"));
        assertThat(ultimo(hoja, "Utilidad neta")).isEqualTo(num(json, "$.rows[5].amount"));
    }

    @Test
    void elEstadoDeResultadosDeVariosMesesAgrupaPorMes() throws Exception {
        escenario();
        String json = json("income-statement?desde=2026-08-01&hasta=2026-09-30");
        assertThat(JsonPath.<List<String>>read(json, "$.series[*].label")).containsExactly("2026-08", "2026-09");
        assertThat(num(json, "$.series[0].profit")).isEqualTo(200000.0);
        assertThat(num(json, "$.series[1].revenue")).isEqualTo(385000.0);
        assertThat(num(json, "$.series[1].profit")).isEqualTo(225000.0);
        assertThat(num(json, "$.summary.netProfit")).isEqualTo(425000.0);
    }

    // ---- IVA ----

    @Test
    void elIvaEnVivoDesglosaPorCategoriaYTarifaConLosTotalesDeIva() throws Exception {
        escenario();
        String json = json("vat?" + RANGO);
        String iva = json("iva?" + RANGO);
        assertThat(num(json, "$.summary.ivaGenerado")).isEqualTo(num(iva, "$.totalIvaGenerado")).isEqualTo(44650.0);
        assertThat(num(json, "$.summary.ivaDescontable")).isEqualTo(num(iva, "$.totalIvaDescontable"))
                .isEqualTo(15200.0);
        assertThat(num(json, "$.summary.ivaNeto")).isEqualTo(num(iva, "$.totalIvaNeto")).isEqualTo(29450.0);
        assertThat(num(json, "$.summary.invoicesWithIva")).isEqualTo(num(iva, "$.invoicesWithIva")).isEqualTo(2.0);
        assertThat(JsonPath.<List<String>>read(json, "$.rows[*].categoryName")).containsExactly("LLANTAS", "SERVICIOS");
        assertThat(JsonPath.<List<Double>>read(json, "$.rows[*].ratePercent")).containsExactly(19.0, 19.0);
        assertThat(num(json, "$.rows[0].taxableBase")).isEqualTo(200000.0);
        assertThat(num(json, "$.rows[0].ivaDescontable")).isEqualTo(15200.0);
        assertThat(num(json, "$.rows[1].ivaNeto")).isEqualTo(6650.0);

        Sheet hoja = hoja(descargar("type=IVA&format=XLSX&" + RANGO, XLSX, "reporte-iva_2026-09-01_2026-09-30.xlsx"));
        assertThat(ultimo(hoja, "Saldo de IVA por pagar")).isEqualTo(29450.0);
        assertThat(numero(fila(hoja, "LLANTAS"), 1)).isCloseTo(0.19, within(DELTA));
        assertThat(numero(fila(hoja, "Totales"), 3)).isEqualTo(44650.0);
        assertThat(textoPdf(descargar("type=IVA&format=PDF&" + RANGO, "application/pdf",
                "reporte-iva_2026-09-01_2026-09-30.pdf"))).contains("Reporte de IVA", "LLANTAS", "19,00 %", "$ 29.450");
    }

    // ---- Movimientos ----

    @Test
    void losMovimientosEnVivoFiltranPorCuentaYTipo() throws Exception {
        escenario();
        String json = json("movements-report?" + RANGO);
        assertThat(JsonPath.<List<Object>>read(json, "$.rows")).hasSize(8);
        assertThat(num(json, "$.summary.inflows")).isEqualTo(438000.0);
        assertThat(num(json, "$.summary.outflows")).isEqualTo(532500.0);
        assertThat(num(json, "$.summary.net")).isEqualTo(-94500.0);
        assertThat(num(json, "$.summary.movementCount")).isEqualTo(8.0);
        assertThat(JsonPath.<List<String>>read(json, "$.rows[*].reference")).contains("VENTA FV-1", "COMPRA FC-1",
                "TRANSFERENCIA", "GASTO OPERATIVO", "INGRESO OCASIONAL").doesNotContain("VENTA FV-3");
        assertThat(num(json, "$.series[4].value")).isEqualTo(238000.0);

        ok("movements-report?" + RANGO + "&tipo=EGRESO")
                .andExpect(jsonPath("$.rows", hasSize(4)))
                .andExpect(jsonPath("$.summary.inflows", closeTo(0.0, DELTA)))
                .andExpect(jsonPath("$.appliedFilters[0]").value("Tipo: Egreso"));
        ok("movements-report?" + RANGO + "&cuentaId=" + banco.getId())
                .andExpect(jsonPath("$.rows", hasSize(3)))
                .andExpect(jsonPath("$.summary.net", closeTo(145000.0, DELTA)));

        Sheet hoja = hoja(descargar("type=MOVIMIENTOS&format=XLSX&" + RANGO + "&tipo=EGRESO", XLSX,
                "movimientos-cuentas_2026-09-01_2026-09-30.xlsx"));
        assertThat(ultimo(hoja, "Egresos")).isEqualTo(-532500.0);
        assertThat(ultimo(hoja, "Ingresos")).isEqualTo(0.0);
    }

    // ---- Mes por defecto en /kpis, /movements e /iva ----

    @Test
    void kpisMovementsEIvaSinFechasUsanElMesEnCurso() throws Exception {
        base();
        LocalDate hoy = LocalDate.now(clock);
        venta("HOY-1", PaymentType.CONTADO, hoy, linea(lla1, 1, 100000.0));
        TenantContext.clear();
        String mes = "desde=" + hoy.withDayOfMonth(1) + "&hasta=" + hoy.withDayOfMonth(hoy.lengthOfMonth());

        String sinFechas = json("kpis");
        assertThat(JsonPath.<String>read(sinFechas, "$.desde")).isEqualTo(hoy.withDayOfMonth(1).toString());
        assertThat(sinFechas).isEqualTo(json("kpis?" + mes));
        assertThat(num(sinFechas, "$.totalSales")).isEqualTo(119000.0);
        assertThat(json("movements")).isEqualTo(json("movements?" + mes));
        assertThat(json("iva")).isEqualTo(json("iva?" + mes));
        assertThat(num(json("iva"), "$.totalIvaGenerado")).isEqualTo(19000.0);
        // Una sola fecha sigue siendo 400.
        error("kpis?desde=" + hoy, "El rango necesita fecha desde y fecha hasta");
        error("iva?hasta=" + hoy, "El rango necesita fecha desde y fecha hasta");
    }

    // ---- Compras por categoria ----

    @Test
    void comprasConCategoriaSumaSoloLasLineasDeEsaCategoria() throws Exception {
        escenario();
        ok("purchases?" + RANGO)
                .andExpect(jsonPath("$.rows[0].total", closeTo(397500.0, DELTA)));
        ok("purchases?" + RANGO + "&categoriaId=" + llantas.getId())
                .andExpect(jsonPath("$.rows[*].invoiceNumber", contains("FC-1", "FC-2")))
                .andExpect(jsonPath("$.rows[0].subtotal", closeTo(250000.0, DELTA)))
                .andExpect(jsonPath("$.rows[0].iva", closeTo(47500.0, DELTA)))
                .andExpect(jsonPath("$.rows[0].total", closeTo(297500.0, DELTA)))
                .andExpect(jsonPath("$.rows[0].pendingBalance", closeTo(0.0, DELTA)))
                .andExpect(jsonPath("$.rows[1].total", closeTo(11900.0, DELTA)))
                .andExpect(jsonPath("$.rows[1].pendingBalance", closeTo(6900.0, DELTA)))
                .andExpect(jsonPath("$.summary.totalPurchased", closeTo(309400.0, DELTA)))
                .andExpect(jsonPath("$.summary.deductibleVat", closeTo(49400.0, DELTA)))
                .andExpect(jsonPath("$.summary.pendingTotal", closeTo(6900.0, DELTA)));
        ok("purchases?" + RANGO + "&categoriaId=" + baterias.getId())
                .andExpect(jsonPath("$.rows[*].invoiceNumber", contains("FC-1")))
                .andExpect(jsonPath("$.rows[0].total", closeTo(100000.0, DELTA)))
                .andExpect(jsonPath("$.summary.deductibleVat", closeTo(0.0, DELTA)));
    }

    // ---- Aislamiento y errores ----

    @Test
    void ningunReporteNuevoMuestraDatosDeOtraServiteca() throws Exception {
        escenario();
        ok("inventory?" + RANGO).andExpect(jsonPath("$.rows[*].code", not(hasItem("AJE-1"))));
        String flujo = json("cash-flow?" + RANGO);
        assertThat(num(flujo, "$.summary.openingBalance")).isEqualTo(1700000.0);
        assertThat(num(flujo, "$.summary.inflows")).isEqualTo(338000.0);
        ok("movements-report?" + RANGO).andExpect(jsonPath("$.rows[*].accountName", not(hasItem("Caja Ajena"))));
        ok("income-statement?" + RANGO).andExpect(jsonPath("$.summary.revenue", closeTo(385000.0, DELTA)));
        ok("vat?" + RANGO).andExpect(jsonPath("$.summary.invoicesWithIva").value(2));
        error("cash-flow?cuentaId=" + cuentaAjena.getId(), "Cuenta no encontrada: " + cuentaAjena.getId());
        error("movements-report?cuentaId=" + cuentaAjena.getId(), "Cuenta no encontrada: " + cuentaAjena.getId());
    }

    @Test
    void losFiltrosInvalidosSon400() throws Exception {
        error("inventory?estadoStock=AGOTADO",
                "Valor invalido 'AGOTADO' en el parametro estadoStock. Valores permitidos: NORMAL, BAJO, CRITICO, SIN_STOCK");
        error("movements-report?tipo=ENTRADA",
                "Valor invalido 'ENTRADA' en el parametro tipo. Valores permitidos: INGRESO, EGRESO");
        error("cash-flow?cuentaId=999999", "Cuenta no encontrada: 999999");
        error("inventory?categoriaId=999999", "Categoria no encontrada: 999999");
        error("vat?desde=2026-09-01", "El rango necesita fecha desde y fecha hasta");
        error("income-statement?desde=2026-09-30&hasta=2026-09-01",
                "La fecha desde no puede ser posterior a la fecha hasta");
        error("cash-flow?desde=2025-01-01&hasta=2026-01-02", "El rango de un reporte no puede pasar de 366 dias");
        error("export?type=FLUJO_CAJA&format=PDF&estadoStock=BAJO",
                "El parametro estadoStock no aplica al reporte FLUJO_CAJA");
        error("export?type=IVA&format=PDF&cuentaId=1", "El parametro cuentaId no aplica al reporte IVA");
        error("export?type=INVENTARIO&format=XLSX&accountId=1", "accountId solo aplica al reporte MOVIMIENTOS");
    }

    @Test
    void sinTokenEs401() throws Exception {
        for (String ruta : List.of("inventory", "cash-flow", "income-statement", "vat", "movements-report")) {
            mockMvc.perform(get("/api/reporting/" + ruta)).andExpect(status().isUnauthorized());
        }
    }

    // ---- Exportaciones ----

    @Test
    void elInventarioSeExportaEnPdfYExcelConTotalesNumericos() throws Exception {
        escenario();
        Sheet hoja = hoja(descargar("type=INVENTARIO&format=XLSX&" + RANGO, XLSX,
                "inventario-rotacion_2026-09-01_2026-09-30.xlsx"));
        assertThat(ultimo(hoja, "Valor del inventario al costo")).isEqualTo(1520000.0);
        assertThat(ultimo(hoja, "Productos sin stock")).isEqualTo(1.0);
        Row totales = fila(hoja, "Totales");
        assertThat(numero(totales, 3)).isEqualTo(33.0);
        assertThat(numero(totales, 6)).isEqualTo(1520000.0);
        assertThat(numero(totales, 8)).isEqualTo(3.0);
        assertThat(numero(fila(hoja, "LLA-001"), 6)).isEqualTo(1150000.0);

        String texto = textoPdf(descargar("type=INVENTARIO&format=PDF&" + RANGO + "&estadoStock=CRITICO",
                "application/pdf", "inventario-rotacion_2026-09-01_2026-09-30.pdf"));
        assertThat(texto).contains("Inventario valorizado y rotación", "Filtros: Estado de stock: Crítico", "LLA-003",
                "Crítico", "ServiBox ").doesNotContain("LLA-001", "SRV-ALI");
    }

    @Test
    void elFlujoDeCajaSeExportaEnPdfYExcelConTotalesNumericos() throws Exception {
        escenario();
        Sheet hoja = hoja(descargar("type=FLUJO_CAJA&format=XLSX&" + RANGO, XLSX, "flujo-caja_2026-09-01_2026-09-30.xlsx"));
        assertThat(ultimo(hoja, "Saldo inicial")).isEqualTo(1700000.0);
        assertThat(ultimo(hoja, "Entradas")).isEqualTo(338000.0);
        assertThat(ultimo(hoja, "Salidas")).isEqualTo(-432500.0);
        assertThat(ultimo(hoja, "Flujo neto")).isEqualTo(-94500.0);
        assertThat(ultimo(hoja, "Saldo final")).isEqualTo(1605500.0);
        assertThat(numero(fila(hoja, "Compras de contado"), 3)).isEqualTo(-397500.0);
        assertThat(numero(fila(hoja, "Compras de contado"), 2)).isEqualTo(1.0);

        String texto = textoPdf(descargar("type=FLUJO_CAJA&format=PDF&" + RANGO + "&cuentaId=" + banco.getId(),
                "application/pdf", "flujo-caja_2026-09-01_2026-09-30.pdf"));
        assertThat(texto).contains("Flujo de caja", "Filtros: Cuenta: Bancolombia", "Transferencias recibidas",
                "Saldo final", "$ 645.000", "ServiBox ");
    }
}
