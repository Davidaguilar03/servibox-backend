package com.servibox.backend.reporting;

import com.jayway.jsonpath.JsonPath;
import com.servibox.backend.auth.entity.Role;
import com.servibox.backend.auth.entity.User;
import com.servibox.backend.auth.repository.UserRepository;
import com.servibox.backend.counterparties.entity.Counterparty;
import com.servibox.backend.inventory.entity.Product;
import com.servibox.backend.inventory.entity.ProductCategory;
import com.servibox.backend.inventory.service.InventoryService;
import com.servibox.backend.purchases.entity.Purchase;
import com.servibox.backend.purchases.service.PurchasesService;
import com.servibox.backend.reporting.service.AgingRange;
import com.servibox.backend.sales.SalesFixture;
import com.servibox.backend.sales.entity.PaymentType;
import com.servibox.backend.sales.entity.Sale;
import com.servibox.backend.sales.repository.SaleRepository;
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
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.closeTo;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Reportes en vivo: GET /api/reporting/sales, /products-sold, /purchases, /receivables y
 * /payables, y su exportacion por /export.
 *
 * Escenario de septiembre de 2026 (IVA del 19 por ciento en LLANTAS y SERVICIOS, BATERIAS sin
 * IVA). Llanta de costo 40.000, bateria de costo 100.000, servicio de alineacion a 35.000.
 * Ventas, en este orden y antes de cualquier compra:
 * FV-1 05/09 contado Andina: 2 llantas a 100.000, total 238.000 (costo 80.000).
 * FV-2 06/09 credito Bogota: bateria a 150.000 mas alineacion, total 191.650, abono 50.000.
 * FV-3 06/09 contado Andina: llanta a 100.000, total 119.000, anulada.
 * FV-4 10/09 credito Andina: 2 alineaciones, total 83.300, sin abonos.
 * Compras: FC-1 07/09 contado Norte, 10 llantas a 50.000, total 595.000; FC-2 08/09 credito
 * Baterias SAS, 2 baterias a 100.000, total 200.000, pago 50.000; FC-3 09/09 contado Norte,
 * llanta a 50.000, total 59.500, anulada.
 *
 * La cartera usa otro escenario con fechas relativas a hoy (ver escenarioCartera).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class LiveReportsTest {

    private static final String PASSWORD = "clave-correcta";
    private static final String XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    private static final String RANGO = "desde=2026-09-01&hasta=2026-09-30";
    private static final double DELTA = 0.001;
    private static final DateTimeFormatter FECHA = DateTimeFormatter.ofPattern("dd/MM/yyyy");

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
    private SaleRepository saleRepository;

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
    private ProductCategory llantas;
    private ProductCategory baterias;
    private Product llanta;
    private Product bateria;
    private Product alineacion;
    private Counterparty andina;
    private Counterparty bogota;
    private Counterparty norte;
    private Counterparty bateriasSas;

    /** Del tenant dos: un cliente con una factura pendiente que el tenant uno nunca debe ver. */
    private Counterparty clienteAjeno;

    @BeforeEach
    void seed() throws Exception {
        String slugUno = "taller-uno-" + System.nanoTime();
        String slugDos = "taller-dos-" + System.nanoTime();
        tenantUno = crearTenantConUsuario(slugUno, "Taller Uno");
        tenantDos = crearTenantConUsuario(slugDos, "Taller Dos");
        tokenUno = login(slugUno);

        fixture.usarTenant(tenantDos);
        Product ajena = fixture.crearProducto(fixture.crearCategoriaConIva(), "AJE-1", 1000.0, 10);
        clienteAjeno = fixture.crearCliente("Cliente Ajeno", "111");
        Counterparty proveedorAjeno = fixture.crearProveedor("Proveedor Ajeno", "222");
        LocalDate hoy = LocalDate.now(clock);
        salesService.crearFactura("AJ-1", clienteAjeno, LocalDate.of(2026, 9, 15), hoy.plusDays(5),
                PaymentType.CREDITO, null, null, List.of(new SalesService.LineaFactura(ajena.getId(), 1, 777000.0)));
        purchasesService.crearFactura("AJC-1", proveedorAjeno, LocalDate.of(2026, 9, 15), hoy.plusDays(5),
                com.servibox.backend.purchases.entity.PaymentType.CREDITO, null, null,
                List.of(new PurchasesService.LineaCompra(ajena.getId(), 1, 888000.0)));
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

    /** Catalogo, terceros y caja del tenant uno, comunes a los dos escenarios. */
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
        llanta = fixture.crearProducto(llantas, "LLA-001", 40000.0, 50);
        bateria = fixture.crearProducto(baterias, "BAT-001", 100000.0, 20);
        alineacion = inventoryService.crearServicio("SRV-ALI", "Alineacion", 35000.0, true);
        andina = fixture.crearCliente("Transportes Andina", "900111222");
        bogota = fixture.crearCliente("Taxis Bogota", "900333444");
        norte = fixture.crearProveedor("Llantas del Norte", "800111222");
        bateriasSas = fixture.crearProveedor("Baterias SAS", "800333444");
        Account cuenta = new Account();
        cuenta.setName("Caja General");
        cuenta.setType(AccountType.EFECTIVO);
        cuenta.setInitialBalance(10000000.0);
        caja = treasuryService.saveAccount(cuenta);
    }

    /** El escenario de septiembre del javadoc de la clase. */
    private void escenario() {
        base();
        vender("FV-1", andina, PaymentType.CONTADO, LocalDate.of(2026, 9, 5), linea(llanta, 2, 100000.0));
        Sale fv2 = vender("FV-2", bogota, PaymentType.CREDITO, LocalDate.of(2026, 9, 6),
                linea(bateria, 1, 150000.0), linea(alineacion, 1, 35000.0));
        Sale fv3 = vender("FV-3", andina, PaymentType.CONTADO, LocalDate.of(2026, 9, 6), linea(llanta, 1, 100000.0));
        vender("FV-4", andina, PaymentType.CREDITO, LocalDate.of(2026, 9, 10), linea(alineacion, 2, 35000.0));
        salesService.registrarAbono(fv2.getId(), caja.getId(), 50000.0, LocalDate.of(2026, 9, 12));
        salesService.anularFactura(fv3.getId());

        comprar("FC-1", norte, true, LocalDate.of(2026, 9, 7), null, compra(llanta, 10, 50000.0));
        Purchase fc2 = comprar("FC-2", bateriasSas, false, LocalDate.of(2026, 9, 8), LocalDate.of(2026, 10, 8),
                compra(bateria, 2, 100000.0));
        Purchase fc3 = comprar("FC-3", norte, true, LocalDate.of(2026, 9, 9), null, compra(llanta, 1, 50000.0));
        purchasesService.registrarPago(fc2.getId(), caja.getId(), 50000.0, LocalDate.of(2026, 9, 12));
        purchasesService.anularFactura(fc3.getId());
        TenantContext.clear();
    }

    /**
     * Cartera con fechas relativas a hoy. Por cobrar:
     * R1 Andina, vence hace 10 dias, 119.000 con abono de 19.000: saldo 100.000 (0-30).
     * R2 Andina, vence hace 45 dias, 119.000 (31-60).
     * R3 Bogota, vence hace 75 dias, 150.000 (61-90).
     * R4 Bogota, vence hace 120 dias, 150.000 (90+).
     * R5 Bogota, de hoy y vence en 30 dias, 41.650 (0 dias, 0-30).
     * Fuera: R6 a credito anulada, R7 a credito pagada por completo y R8 de contado.
     * Por pagar: C1 Norte, vence hace 10 dias, 119.000 con pago de 19.000 (0-30); C2 Baterias SAS,
     * vence hace 95 dias, 100.000 (90+); fuera C3 a credito anulada.
     */
    private void escenarioCartera() {
        base();
        LocalDate hoy = LocalDate.now(clock);
        Sale r1 = credito("R1", andina, hoy.minusDays(40), hoy.minusDays(10), linea(llanta, 1, 100000.0));
        credito("R2", andina, hoy.minusDays(75), hoy.minusDays(45), linea(llanta, 1, 100000.0));
        credito("R3", bogota, hoy.minusDays(105), hoy.minusDays(75), linea(bateria, 1, 150000.0));
        credito("R4", bogota, hoy.minusDays(150), hoy.minusDays(120), linea(bateria, 1, 150000.0));
        credito("R5", bogota, hoy, hoy.plusDays(30), linea(alineacion, 1, 35000.0));
        Sale r6 = credito("R6", bogota, hoy.minusDays(10), hoy, linea(llanta, 1, 100000.0));
        Sale r7 = credito("R7", andina, hoy.minusDays(10), hoy, linea(bateria, 1, 10000.0));
        vender("R8", andina, PaymentType.CONTADO, hoy.minusDays(3), linea(llanta, 1, 100000.0));
        salesService.registrarAbono(r1.getId(), caja.getId(), 19000.0, hoy);
        salesService.registrarAbono(r7.getId(), caja.getId(), 10000.0, hoy);
        salesService.anularFactura(r6.getId());

        Purchase c1 = comprar("C1", norte, false, hoy.minusDays(40), hoy.minusDays(10), compra(llanta, 2, 50000.0));
        comprar("C2", bateriasSas, false, hoy.minusDays(150), hoy.minusDays(95), compra(bateria, 1, 100000.0));
        Purchase c3 = comprar("C3", norte, false, hoy.minusDays(5), hoy, compra(llanta, 1, 50000.0));
        purchasesService.registrarPago(c1.getId(), caja.getId(), 19000.0, hoy);
        purchasesService.anularFactura(c3.getId());
        TenantContext.clear();
    }

    private static SalesService.LineaFactura linea(Product producto, int cantidad, double precio) {
        return new SalesService.LineaFactura(producto.getId(), cantidad, precio);
    }

    private static PurchasesService.LineaCompra compra(Product producto, int cantidad, double precio) {
        return new PurchasesService.LineaCompra(producto.getId(), cantidad, precio);
    }

    private Sale vender(String numero, Counterparty cliente, PaymentType tipo, LocalDate fecha,
                        SalesService.LineaFactura... lineas) {
        return salesService.crearFactura(numero, cliente, fecha, fecha.plusDays(30), tipo,
                tipo == PaymentType.CONTADO ? caja : null, "Efectivo", List.of(lineas));
    }

    private Sale credito(String numero, Counterparty cliente, LocalDate fecha, LocalDate vence,
                         SalesService.LineaFactura... lineas) {
        return salesService.crearFactura(numero, cliente, fecha, vence, PaymentType.CREDITO, null, null,
                List.of(lineas));
    }

    private Purchase comprar(String numero, Counterparty proveedor, boolean contado, LocalDate fecha, LocalDate vence,
                             PurchasesService.LineaCompra... lineas) {
        return purchasesService.crearFactura(numero, proveedor, fecha, vence,
                contado ? com.servibox.backend.purchases.entity.PaymentType.CONTADO
                        : com.servibox.backend.purchases.entity.PaymentType.CREDITO,
                contado ? caja : null, contado ? "Efectivo" : null, List.of(lineas));
    }

    // ---- Peticiones ----

    private ResultActions pedir(String ruta) throws Exception {
        return mockMvc.perform(get("/api/reporting/" + ruta).header("Authorization", "Bearer " + tokenUno)
                .accept(MediaType.APPLICATION_JSON));
    }

    private ResultActions ok(String ruta) throws Exception {
        return pedir(ruta).andExpect(status().isOk());
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

    private static boolean tieneTexto(Sheet hoja, String prefijo) {
        for (Row row : hoja) {
            for (Cell celda : row) {
                if (celda.getCellType() == CellType.STRING && celda.getStringCellValue().startsWith(prefijo)) {
                    return true;
                }
            }
        }
        return false;
    }

    // ---- Ventas ----

    @Test
    void lasVentasTienenTotalesFilasYSerieSinContarAnuladas() throws Exception {
        escenario();
        ok("sales?" + RANGO)
                .andExpect(jsonPath("$.desde").value("2026-09-01"))
                .andExpect(jsonPath("$.hasta").value("2026-09-30"))
                .andExpect(jsonPath("$.appliedFilters", hasSize(0)))
                .andExpect(jsonPath("$.summary.totalSold", closeTo(512950.0, DELTA)))
                .andExpect(jsonPath("$.summary.invoiceCount").value(3))
                .andExpect(jsonPath("$.summary.averageTicket", closeTo(170983.33, DELTA)))
                .andExpect(jsonPath("$.summary.cashTotal", closeTo(238000.0, DELTA)))
                .andExpect(jsonPath("$.summary.creditTotal", closeTo(274950.0, DELTA)))
                .andExpect(jsonPath("$.summary.annulledCount").value(1))
                .andExpect(jsonPath("$.rows[*].invoiceNumber", contains("FV-1", "FV-2", "FV-3", "FV-4")))
                .andExpect(jsonPath("$.rows[1].customerName").value("Taxis Bogota"))
                .andExpect(jsonPath("$.rows[1].paymentType").value("CREDITO"))
                .andExpect(jsonPath("$.rows[1].status").value("PENDIENTE"))
                .andExpect(jsonPath("$.rows[1].subtotal", closeTo(185000.0, DELTA)))
                .andExpect(jsonPath("$.rows[1].iva", closeTo(6650.0, DELTA)))
                .andExpect(jsonPath("$.rows[1].total", closeTo(191650.0, DELTA)))
                .andExpect(jsonPath("$.rows[1].pendingBalance", closeTo(141650.0, DELTA)))
                .andExpect(jsonPath("$.rows[2].status").value("ANULADA"))
                .andExpect(jsonPath("$.rows[2].pendingBalance", closeTo(0.0, DELTA)))
                .andExpect(jsonPath("$.series", hasSize(30)))
                .andExpect(jsonPath("$.series[4].label").value("2026-09-05"))
                .andExpect(jsonPath("$.series[4].value", closeTo(238000.0, DELTA)))
                .andExpect(jsonPath("$.series[5].value", closeTo(191650.0, DELTA)))
                .andExpect(jsonPath("$.series[9].value", closeTo(83300.0, DELTA)))
                .andExpect(jsonPath("$.series[0].value", closeTo(0.0, DELTA)));
    }

    @Test
    void cadaFiltroDeVentasAcotaLasFacturas() throws Exception {
        escenario();
        ok("sales?" + RANGO + "&clienteId=" + andina.getId())
                .andExpect(jsonPath("$.appliedFilters[0]").value("Cliente: Transportes Andina"))
                .andExpect(jsonPath("$.rows[*].invoiceNumber", contains("FV-1", "FV-3", "FV-4")))
                .andExpect(jsonPath("$.summary.totalSold", closeTo(321300.0, DELTA)))
                .andExpect(jsonPath("$.summary.invoiceCount").value(2))
                .andExpect(jsonPath("$.summary.annulledCount").value(1));
        ok("sales?" + RANGO + "&formaPago=CREDITO")
                .andExpect(jsonPath("$.appliedFilters[0]").value("Forma de pago: Crédito"))
                .andExpect(jsonPath("$.rows[*].invoiceNumber", contains("FV-2", "FV-4")))
                .andExpect(jsonPath("$.summary.totalSold", closeTo(274950.0, DELTA)))
                .andExpect(jsonPath("$.summary.cashTotal", closeTo(0.0, DELTA)));
        ok("sales?" + RANGO + "&estado=PENDIENTE")
                .andExpect(jsonPath("$.rows[*].invoiceNumber", contains("FV-2", "FV-4")));
        ok("sales?" + RANGO + "&estado=ANULADA")
                .andExpect(jsonPath("$.rows[*].invoiceNumber", contains("FV-3")))
                .andExpect(jsonPath("$.summary.totalSold", closeTo(0.0, DELTA)))
                .andExpect(jsonPath("$.summary.invoiceCount").value(0))
                .andExpect(jsonPath("$.summary.averageTicket", closeTo(0.0, DELTA)))
                .andExpect(jsonPath("$.summary.annulledCount").value(1));
        ok("sales?" + RANGO + "&clienteId=" + andina.getId() + "&formaPago=CONTADO&estado=PAGADA")
                .andExpect(jsonPath("$.rows[*].invoiceNumber", contains("FV-1")))
                .andExpect(jsonPath("$.appliedFilters", hasSize(3)));
    }

    @Test
    void sinFechasLasVentasUsanElMesEnCurso() throws Exception {
        LocalDate hoy = LocalDate.now(clock);
        ok("sales")
                .andExpect(jsonPath("$.desde").value(hoy.withDayOfMonth(1).toString()))
                .andExpect(jsonPath("$.hasta").value(hoy.withDayOfMonth(hoy.lengthOfMonth()).toString()))
                .andExpect(jsonPath("$.series", hasSize(hoy.lengthOfMonth())))
                .andExpect(jsonPath("$.rows", hasSize(0)));
    }

    // ---- Productos y servicios vendidos ----

    @Test
    void losProductosVendidosIncluyenServiciosConCostoCeroYOrdenanPorIngresos() throws Exception {
        escenario();
        ok("products-sold?" + RANGO)
                .andExpect(jsonPath("$.rows[*].code", contains("LLA-001", "BAT-001", "SRV-ALI")))
                .andExpect(jsonPath("$.rows[0].units").value(2))
                .andExpect(jsonPath("$.rows[0].revenue", closeTo(200000.0, DELTA)))
                .andExpect(jsonPath("$.rows[0].cost", closeTo(80000.0, DELTA)))
                .andExpect(jsonPath("$.rows[0].margin", closeTo(120000.0, DELTA)))
                .andExpect(jsonPath("$.rows[0].marginPercent", closeTo(60.0, DELTA)))
                .andExpect(jsonPath("$.rows[0].categoryName").value("LLANTAS"))
                .andExpect(jsonPath("$.rows[1].marginPercent", closeTo(33.33, DELTA)))
                .andExpect(jsonPath("$.rows[2].itemType").value("SERVICIO"))
                .andExpect(jsonPath("$.rows[2].units").value(3))
                .andExpect(jsonPath("$.rows[2].revenue", closeTo(105000.0, DELTA)))
                .andExpect(jsonPath("$.rows[2].cost", closeTo(0.0, DELTA)))
                .andExpect(jsonPath("$.rows[2].marginPercent", closeTo(100.0, DELTA)))
                .andExpect(jsonPath("$.summary.unitsSold").value(6))
                .andExpect(jsonPath("$.summary.revenue", closeTo(455000.0, DELTA)))
                .andExpect(jsonPath("$.summary.cost", closeTo(180000.0, DELTA)))
                .andExpect(jsonPath("$.summary.grossMargin", closeTo(275000.0, DELTA)))
                .andExpect(jsonPath("$.summary.marginPercent", closeTo(60.44, DELTA)))
                .andExpect(jsonPath("$.series[*].label", contains("Producto LLA-001", "Producto BAT-001", "Alineacion")));
    }

    @Test
    void cadaFiltroDeProductosVendidosAcotaLasFilas() throws Exception {
        escenario();
        ok("products-sold?" + RANGO + "&itemType=SERVICIO")
                .andExpect(jsonPath("$.rows[*].code", contains("SRV-ALI")))
                .andExpect(jsonPath("$.appliedFilters[0]").value("Tipo: Servicio"));
        ok("products-sold?" + RANGO + "&itemType=PRODUCTO")
                .andExpect(jsonPath("$.rows[*].code", contains("LLA-001", "BAT-001")));
        ok("products-sold?" + RANGO + "&categoriaId=" + baterias.getId())
                .andExpect(jsonPath("$.rows[*].code", contains("BAT-001")))
                .andExpect(jsonPath("$.appliedFilters[0]").value("Categoría: BATERIAS"));
        ok("products-sold?" + RANGO + "&productoId=" + llanta.getId())
                .andExpect(jsonPath("$.rows[*].code", contains("LLA-001")))
                .andExpect(jsonPath("$.summary.unitsSold").value(2));
    }

    // ---- Compras ----

    @Test
    void lasComprasTienenTotalesFilasYSerieSinContarAnuladas() throws Exception {
        escenario();
        ok("purchases?" + RANGO)
                .andExpect(jsonPath("$.summary.totalPurchased", closeTo(795000.0, DELTA)))
                .andExpect(jsonPath("$.summary.invoiceCount").value(2))
                .andExpect(jsonPath("$.summary.deductibleVat", closeTo(95000.0, DELTA)))
                .andExpect(jsonPath("$.summary.pendingTotal", closeTo(150000.0, DELTA)))
                .andExpect(jsonPath("$.rows[*].invoiceNumber", contains("FC-1", "FC-2", "FC-3")))
                .andExpect(jsonPath("$.rows[0].iva", closeTo(95000.0, DELTA)))
                .andExpect(jsonPath("$.rows[1].pendingBalance", closeTo(150000.0, DELTA)))
                .andExpect(jsonPath("$.rows[2].status").value("ANULADA"))
                .andExpect(jsonPath("$.series[*].label", contains("Llantas del Norte", "Baterias SAS")))
                .andExpect(jsonPath("$.series[0].value", closeTo(595000.0, DELTA)));
    }

    @Test
    void cadaFiltroDeComprasAcotaLasFacturas() throws Exception {
        escenario();
        ok("purchases?" + RANGO + "&proveedorId=" + bateriasSas.getId())
                .andExpect(jsonPath("$.rows[*].invoiceNumber", contains("FC-2")))
                .andExpect(jsonPath("$.appliedFilters[0]").value("Proveedor: Baterias SAS"));
        ok("purchases?" + RANGO + "&categoriaId=" + baterias.getId())
                .andExpect(jsonPath("$.rows[*].invoiceNumber", contains("FC-2")));
        ok("purchases?" + RANGO + "&estado=ANULADA")
                .andExpect(jsonPath("$.rows[*].invoiceNumber", contains("FC-3")))
                .andExpect(jsonPath("$.summary.totalPurchased", closeTo(0.0, DELTA)));
    }

    // ---- Cartera ----

    @Test
    void laCarteraPorCobrarReparteElSaldoEnLosCuatroRangos() throws Exception {
        escenarioCartera();
        LocalDate hoy = LocalDate.now(clock);
        ok("receivables")
                .andExpect(jsonPath("$.corte").value(hoy.toString()))
                .andExpect(jsonPath("$.desde").doesNotExist())
                // Por nombre de cliente ("Taxis Bogota" antes que "Transportes Andina") y fecha.
                .andExpect(jsonPath("$.rows[*].invoiceNumber", contains("R4", "R3", "R5", "R2", "R1")))
                .andExpect(jsonPath("$.rows[*].daysOld", contains(120, 75, 0, 45, 10)))
                .andExpect(jsonPath("$.rows[*].agingBucket", contains("90+", "61-90", "0-30", "31-60", "0-30")))
                .andExpect(jsonPath("$.rows[4].total", closeTo(119000.0, DELTA)))
                .andExpect(jsonPath("$.rows[4].paid", closeTo(19000.0, DELTA)))
                .andExpect(jsonPath("$.rows[4].balance", closeTo(100000.0, DELTA)))
                .andExpect(jsonPath("$.rows[4].dueDate").value(hoy.minusDays(10).toString()))
                .andExpect(jsonPath("$.summary.totalBalance", closeTo(560650.0, DELTA)))
                .andExpect(jsonPath("$.summary.invoiceCount").value(5))
                .andExpect(jsonPath("$.summary.balance0To30", closeTo(141650.0, DELTA)))
                .andExpect(jsonPath("$.summary.balance31To60", closeTo(119000.0, DELTA)))
                .andExpect(jsonPath("$.summary.balance61To90", closeTo(150000.0, DELTA)))
                .andExpect(jsonPath("$.summary.balanceOver90", closeTo(150000.0, DELTA)))
                .andExpect(jsonPath("$.series[*].label", contains("0-30", "31-60", "61-90", "90+")))
                .andExpect(jsonPath("$.series[3].value", closeTo(150000.0, DELTA)));
    }

    @Test
    void losFiltrosDeLaCarteraPorCobrarAcotanPorClienteYAntiguedad() throws Exception {
        escenarioCartera();
        ok("receivables?clienteId=" + andina.getId())
                .andExpect(jsonPath("$.rows[*].invoiceNumber", contains("R2", "R1")))
                .andExpect(jsonPath("$.summary.totalBalance", closeTo(219000.0, DELTA)));
        ok("receivables?antiguedad=61-90")
                .andExpect(jsonPath("$.rows[*].invoiceNumber", contains("R3")))
                .andExpect(jsonPath("$.appliedFilters[0]").value("Antigüedad: 61-90 días"))
                .andExpect(jsonPath("$.summary.balance0To30", closeTo(0.0, DELTA)))
                .andExpect(jsonPath("$.summary.totalBalance", closeTo(150000.0, DELTA)));
        // MockMvc codifica el valor: llega "90+" como si el cliente mandara %2B.
        mockMvc.perform(get("/api/reporting/receivables").param("antiguedad", "90+")
                        .header("Authorization", "Bearer " + tokenUno))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows[*].invoiceNumber", contains("R4")));
        ok("receivables?antiguedad=0-30&clienteId=" + bogota.getId())
                .andExpect(jsonPath("$.rows[*].invoiceNumber", contains("R5")));
    }

    @Test
    void sinFechaDeVencimientoLaAntiguedadSeCuentaDesdeLaFactura() throws Exception {
        base();
        LocalDate hoy = LocalDate.now(clock);
        Sale vieja = credito("SV-1", andina, hoy.minusDays(70), hoy.minusDays(5), linea(llanta, 1, 100000.0));
        vieja.setDueDate(null);
        saleRepository.save(vieja);
        TenantContext.clear();
        ok("receivables")
                .andExpect(jsonPath("$.rows[0].daysOld").value(70))
                .andExpect(jsonPath("$.rows[0].agingBucket").value("61-90"));
    }

    @Test
    void losLimitesDeLosRangosDeAntiguedad() {
        assertThat(AgingRange.of(0)).isEqualTo(AgingRange.D0_30);
        assertThat(AgingRange.of(30)).isEqualTo(AgingRange.D0_30);
        assertThat(AgingRange.of(31)).isEqualTo(AgingRange.D31_60);
        assertThat(AgingRange.of(60)).isEqualTo(AgingRange.D31_60);
        assertThat(AgingRange.of(61)).isEqualTo(AgingRange.D61_90);
        assertThat(AgingRange.of(90)).isEqualTo(AgingRange.D61_90);
        assertThat(AgingRange.of(91)).isEqualTo(AgingRange.D90_MAS);
    }

    @Test
    void laCarteraPorPagarUsaProveedoresYFacturasDeCompra() throws Exception {
        escenarioCartera();
        ok("payables")
                .andExpect(jsonPath("$.rows[*].invoiceNumber", contains("C2", "C1")))
                .andExpect(jsonPath("$.rows[*].counterpartyName", contains("Baterias SAS", "Llantas del Norte")))
                .andExpect(jsonPath("$.rows[*].daysOld", contains(95, 10)))
                .andExpect(jsonPath("$.rows[1].paid", closeTo(19000.0, DELTA)))
                .andExpect(jsonPath("$.summary.totalBalance", closeTo(200000.0, DELTA)))
                .andExpect(jsonPath("$.summary.invoiceCount").value(2))
                .andExpect(jsonPath("$.summary.balance0To30", closeTo(100000.0, DELTA)))
                .andExpect(jsonPath("$.summary.balanceOver90", closeTo(100000.0, DELTA)));
        ok("payables?proveedorId=" + norte.getId() + "&antiguedad=0-30")
                .andExpect(jsonPath("$.rows[*].invoiceNumber", contains("C1")));
        ok("payables?antiguedad=31-60").andExpect(jsonPath("$.rows", hasSize(0)));
    }

    // ---- Aislamiento y errores ----

    @Test
    void ningunReporteMuestraDatosDeOtraServiteca() throws Exception {
        // El tenant dos tiene AJ-1 y AJC-1 del 15/09, pendientes, con el producto AJE-1.
        escenario();
        ok("sales?" + RANGO).andExpect(jsonPath("$.rows[*].invoiceNumber", contains("FV-1", "FV-2", "FV-3", "FV-4")));
        ok("purchases?" + RANGO).andExpect(jsonPath("$.rows[*].invoiceNumber", contains("FC-1", "FC-2", "FC-3")));
        ok("products-sold?" + RANGO).andExpect(jsonPath("$.rows[*].code", contains("LLA-001", "BAT-001", "SRV-ALI")));
        ok("receivables").andExpect(jsonPath("$.rows[*].invoiceNumber", contains("FV-2", "FV-4")));
        ok("payables").andExpect(jsonPath("$.rows[*].invoiceNumber", contains("FC-2")));
        error("receivables?clienteId=" + clienteAjeno.getId(), "Cliente no encontrado: " + clienteAjeno.getId());
        error("sales?" + RANGO + "&clienteId=" + clienteAjeno.getId(), "Cliente no encontrado: " + clienteAjeno.getId());
    }

    @Test
    void losFiltrosInvalidosSon400() throws Exception {
        base();
        TenantContext.clear();
        error("sales?formaPago=FIADO", "Valor invalido 'FIADO' en el parametro formaPago. Valores permitidos: CONTADO, CREDITO");
        error("sales?estado=BORRADOR",
                "Valor invalido 'BORRADOR' en el parametro estado. Valores permitidos: PAGADA, PENDIENTE, ANULADA");
        error("purchases?estado=X", "Valor invalido 'X' en el parametro estado. Valores permitidos: PAGADA, PENDIENTE, ANULADA");
        error("products-sold?itemType=KIT", "Valor invalido 'KIT' en el parametro itemType. Valores permitidos: PRODUCTO, SERVICIO");
        error("receivables?antiguedad=15",
                "Valor invalido '15' en el parametro antiguedad. Valores permitidos: 0-30, 31-60, 61-90, 90+");
        error("sales?desde=2026-09-30&hasta=2026-09-01", "La fecha desde no puede ser posterior a la fecha hasta");
        error("sales?desde=2026-09-01", "El rango necesita fecha desde y fecha hasta");
        error("purchases?desde=2025-01-01&hasta=2026-01-02", "El rango de un reporte no puede pasar de 366 dias");
        error("products-sold?categoriaId=999999", "Categoria no encontrada: 999999");
        error("products-sold?productoId=999999", "Producto no encontrado: 999999");
        error("payables?proveedorId=999999", "Proveedor no encontrado: 999999");
        error("sales?clienteId=abc", "Valor invalido 'abc' en el parametro clienteId");
        error("export?type=PRODUCTOS_VENDIDOS&format=PDF&clienteId=" + andina.getId(),
                "El parametro clienteId no aplica al reporte PRODUCTOS_VENDIDOS");
        error("export?type=VENTAS&format=PDF&estado=X",
                "Valor invalido 'X' en el parametro estado. Valores permitidos: PAGADA, PENDIENTE, ANULADA");
        error("export?type=CARTERA_COBRAR&format=XLSX&accountId=1", "accountId solo aplica al reporte MOVIMIENTOS");
    }

    @Test
    void sinTokenEs401() throws Exception {
        for (String ruta : List.of("sales", "products-sold", "purchases", "receivables", "payables")) {
            mockMvc.perform(get("/api/reporting/" + ruta)).andExpect(status().isUnauthorized());
        }
    }

    // ---- Exportaciones ----

    @Test
    void lasVentasEnPdfLlevanFiltrosTotalesYVersion() throws Exception {
        escenario();
        String texto = textoPdf(descargar("type=VENTAS&format=PDF&" + RANGO + "&clienteId=" + andina.getId(),
                "application/pdf", "ventas_2026-09-01_2026-09-30.pdf"));
        assertThat(texto).contains("Taller Uno", "Ventas", "Período: 01/09/2026 a 30/09/2026",
                "Filtros: Cliente: Transportes Andina", "FV-1", "FV-3", "FV-4", "Anulada", "Crédito",
                "Total vendido", "$ 321.300", "Número de facturas", "ServiBox ");
        assertThat(texto).doesNotContain("FV-2");
    }

    @Test
    void lasVentasEnExcelTienenLosTotalesComoNumeros() throws Exception {
        escenario();
        Sheet hoja = hoja(descargar("type=VENTAS&format=XLSX&" + RANGO, XLSX, "ventas_2026-09-01_2026-09-30.xlsx"));
        assertThat(ultimo(hoja, "Total vendido")).isCloseTo(512950.0, org.assertj.core.api.Assertions.within(DELTA));
        assertThat(ultimo(hoja, "Número de facturas")).isEqualTo(3.0);
        assertThat(ultimo(hoja, "Facturas anuladas")).isEqualTo(1.0);
        Row totales = fila(hoja, "Totales");
        assertThat(numero(totales, 5)).isEqualTo(455000.0);
        assertThat(numero(totales, 6)).isEqualTo(57950.0);
        assertThat(numero(totales, 7)).isEqualTo(512950.0);
        assertThat(numero(totales, 8)).isEqualTo(224950.0);
        Row fv2 = fila(hoja, "FV-2");
        assertThat(fv2.getCell(1).getCellType()).isEqualTo(CellType.NUMERIC);
        assertThat(numero(fv2, 8)).isEqualTo(141650.0);
        assertThat(tieneTexto(hoja, "Sin filtros")).isTrue();
        assertThat(tieneTexto(hoja, "ServiBox ")).isTrue();
    }

    @Test
    void losProductosVendidosEnExcelTienenElMargenComoPorcentaje() throws Exception {
        escenario();
        Sheet hoja = hoja(descargar("type=PRODUCTOS_VENDIDOS&format=XLSX&" + RANGO, XLSX,
                "productos-servicios-vendidos_2026-09-01_2026-09-30.xlsx"));
        Row srv = fila(hoja, "SRV-ALI");
        assertThat(numero(srv, 4)).isEqualTo(3.0);
        assertThat(numero(srv, 6)).isEqualTo(0.0);
        assertThat(numero(srv, 8)).isEqualTo(1.0);
        assertThat(srv.getCell(8).getCellStyle().getDataFormatString()).isEqualTo("0.00%");
        Row totales = fila(hoja, "Totales");
        assertThat(numero(totales, 4)).isEqualTo(6.0);
        assertThat(numero(totales, 7)).isEqualTo(275000.0);
        assertThat(numero(totales, 8)).isCloseTo(0.6044, org.assertj.core.api.Assertions.within(DELTA));
        assertThat(textoPdf(descargar("type=PRODUCTOS_VENDIDOS&format=PDF&" + RANGO + "&itemType=SERVICIO",
                "application/pdf", "productos-servicios-vendidos_2026-09-01_2026-09-30.pdf")))
                .contains("Filtros: Tipo: Servicio", "SRV-ALI", "100,00 %").doesNotContain("LLA-001");
    }

    @Test
    void lasComprasSeExportanEnPdfYExcel() throws Exception {
        escenario();
        Sheet hoja = hoja(descargar("type=COMPRAS&format=XLSX&" + RANGO + "&estado=PENDIENTE", XLSX,
                "compras_2026-09-01_2026-09-30.xlsx"));
        assertThat(ultimo(hoja, "Total comprado")).isEqualTo(200000.0);
        assertThat(ultimo(hoja, "Total pendiente de pago")).isEqualTo(150000.0);
        assertThat(textoPdf(descargar("type=COMPRAS&format=PDF&" + RANGO, "application/pdf",
                "compras_2026-09-01_2026-09-30.pdf"))).contains("Compras", "FC-1", "IVA descontable", "$ 95.000");
    }

    @Test
    void lasCarterasSeExportanConCorteAlDiaDeHoy() throws Exception {
        escenarioCartera();
        LocalDate hoy = LocalDate.now(clock);
        String texto = textoPdf(descargar("type=CARTERA_COBRAR&format=PDF", "application/pdf",
                "cartera-por-cobrar_" + hoy + ".pdf"));
        assertThat(texto).contains("Cartera por cobrar", "Corte al " + hoy.format(FECHA), "Sin filtros", "R4",
                "Saldo de más de 90 días", "Saldo total", "$ 560.650", "ServiBox ").doesNotContain("Período");

        Sheet hoja = hoja(descargar("type=CARTERA_COBRAR&format=XLSX&antiguedad=0-30", XLSX,
                "cartera-por-cobrar_" + hoy + ".xlsx"));
        assertThat(ultimo(hoja, "Saldo total")).isEqualTo(141650.0);
        assertThat(ultimo(hoja, "Facturas pendientes")).isEqualTo(2.0);
        assertThat(numero(fila(hoja, "Transportes Andina"), 4)).isEqualTo(10.0);
        assertThat(tieneTexto(hoja, "Filtros: Antigüedad: 0-30 días")).isTrue();
        assertThat(tieneTexto(hoja, "Corte al " + hoy.format(FECHA))).isTrue();
        // R5 vence en el futuro: celda de vencimiento con fecha, dias en 0.
        Row r5 = null;
        for (Row row : hoja) {
            Cell factura = row.getCell(1);
            if (factura != null && factura.getCellType() == CellType.STRING && factura.getStringCellValue().equals("R5")) {
                r5 = row;
            }
        }
        assertThat(r5).isNotNull();
        assertThat(numero(r5, 4)).isEqualTo(0.0);

        Sheet pagar = hoja(descargar("type=CARTERA_PAGAR&format=XLSX&proveedorId=" + bateriasSas.getId(), XLSX,
                "cartera-por-pagar_" + hoy + ".xlsx"));
        assertThat(ultimo(pagar, "Saldo total")).isEqualTo(100000.0);
        assertThat(ultimo(pagar, "Saldo de más de 90 días")).isEqualTo(100000.0);
        assertThat(textoPdf(descargar("type=CARTERA_PAGAR&format=PDF", "application/pdf",
                "cartera-por-pagar_" + hoy + ".pdf"))).contains("Cartera por pagar", "Pagado", "Baterias SAS");
    }

    @Test
    void sinFacturasLosArchivosLoDicen() throws Exception {
        String texto = textoPdf(descargar("type=VENTAS&format=PDF&" + RANGO, "application/pdf",
                "ventas_2026-09-01_2026-09-30.pdf"));
        assertThat(texto).contains("Sin facturas en el período");
        Sheet hoja = hoja(descargar("type=CARTERA_PAGAR&format=XLSX", XLSX,
                "cartera-por-pagar_" + LocalDate.now(clock) + ".xlsx"));
        assertThat(tieneTexto(hoja, "Sin facturas pendientes")).isTrue();
        assertThat(ultimo(hoja, "Saldo total")).isEqualTo(0.0);
    }
}
