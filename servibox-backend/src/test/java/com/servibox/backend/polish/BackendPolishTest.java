package com.servibox.backend.polish;

import com.jayway.jsonpath.JsonPath;
import com.servibox.backend.inventory.entity.Product;
import com.servibox.backend.inventory.entity.ProductCategory;
import com.servibox.backend.inventory.service.InventoryService;
import com.servibox.backend.reporting.service.ReportExportService;
import com.servibox.backend.sales.SalesFixture;
import com.servibox.backend.sales.entity.PaymentType;
import com.servibox.backend.sales.service.SalesService;
import com.servibox.backend.purchases.service.PurchasesService;
import com.servibox.backend.tenant.TenantContext;
import com.servibox.backend.treasury.entity.Account;
import com.servibox.backend.treasury.entity.AccountType;
import com.servibox.backend.treasury.entity.MovementCategory;
import com.servibox.backend.treasury.service.TreasuryService;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.core.env.Environment;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class BackendPolishTest {
    @Autowired private MockMvc mvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private SalesFixture fixture;
    @Autowired private InventoryService inventory;
    @Autowired private TreasuryService treasury;
    @Autowired private SalesService sales;
    @Autowired private PurchasesService purchases;
    @Autowired private ReportExportService reports;
    @Autowired private Environment environment;
    private String token;
    private Long tenant;
    private long account;
    private long product;
    private long supplier;
    private final LocalDate today = LocalDate.now(java.time.ZoneId.of("America/Bogota"));

    @BeforeEach
    void seed() throws Exception {
        token = registrar();
        String accounts = json(get("/api/treasury/accounts"), 200);
        account = ((Number) JsonPath.read(accounts, "$[0].id")).longValue();
        long category = ((Number) JsonPath.read(json(get("/api/inventory/categories"), 200), "$[0].id")).longValue();
        product = id(json(post("/api/inventory/products").content("""
                {"code":"P","description":"Producto","purchaseCost":0.01,"quantity":100,"categoryId":%d}
                """.formatted(category)), 201));
        supplier = id(json(post("/api/counterparties").content("""
                {"role":"PROVEEDOR","name":"Proveedor","documentType":"NIT","documentNumber":"900000001"}
                """), 201));
        tenant = jdbc.queryForObject("SELECT id_tenant FROM productos WHERE id_producto = ?", Long.class, product);
    }

    @AfterEach
    void cleanup() { TenantContext.clear(); }

    private String registrar() throws Exception {
        String slug = "polish-" + System.nanoTime();
        String response = mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content("""
                {"businessName":"Prueba","nit":"900000000-0","phone":"3000000000","slug":"%s",
                 "adminFullName":"Admin","adminEmail":"admin@example.com","adminUsername":"admin",
                 "password":"prueba-123","acceptTerms":true}
                """.formatted(slug))).andReturn().getResponse().getContentAsString();
        return JsonPath.read(response, "$.token");
    }

    private String json(MockHttpServletRequestBuilder request, int status) throws Exception {
        var response = mvc.perform(request.header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)).andReturn().getResponse();
        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(status);
        return response.getContentAsString();
    }

    private static long id(String json) { return ((Number) JsonPath.read(json, "$.id")).longValue(); }
    private static double amount(String json, String path) { return ((Number) JsonPath.read(json, path)).doubleValue(); }

    private String invoice(boolean purchase, String payment, double price, int quantity, int lines) throws Exception {
        String line = "{\"productId\":" + product + ",\"quantity\":" + quantity + ",\"price\":" + price + "}";
        return json(post(purchase ? "/api/purchases" : "/api/sales").content("""
                {"invoiceNumber":"P-%s","supplierId":%d,"accountId":%d,"paymentType":"%s",
                 "invoiceDate":"%s","dueDate":"%s","details":[%s]}
                """.formatted(System.nanoTime(), supplier, account, payment, today, today.plusDays(30),
                String.join(",", java.util.Collections.nCopies(lines, line)))), 201);
    }

    private void initial(double initial) throws Exception {
        json(put("/api/treasury/accounts/" + account).content(
                "{\"name\":\"Caja General\",\"initialBalance\":" + initial + "}"), 200);
    }

    private void balance(double expected) throws Exception {
        BigDecimal stored = jdbc.queryForObject("SELECT saldo_actual FROM cuentas WHERE id_cuenta = ?",
                BigDecimal.class, account);
        assertThat(stored).isEqualByComparingTo(BigDecimal.valueOf(expected));
        assertThat(amount(json(get("/api/treasury/accounts"), 200), "$[0].currentBalance")).isEqualTo(expected);
        TenantContext.setTenantId(tenant);
        fixture.verificarInvariante();
        assertThat(treasury.saldoAl(treasury.findAccountById(account).orElseThrow(), today)).isEqualTo(expected);
        TenantContext.clear();
    }

    @ParameterizedTest
    @CsvSource({"false,0.005", "true,0.005", "false,0.0100000000000000000001", "true,0.0100000000000000000001"})
    void precisionExcesivaRechazaTodaLaFacturaSinCambiarStockSaldosNiPersistirLineas(boolean purchase, String price)
            throws Exception {
        initial(100);
        List<String> tables = List.of("ventas", "compras", "detalle_ventas", "detalle_compras", "movimientos", "terceros");
        List<Long> before = tables.stream().map(table -> jdbc.queryForObject(
                "SELECT COUNT(*) FROM " + table + " WHERE id_tenant = ?", Long.class, tenant)).toList();
        String originalProduct = json(get("/api/inventory/products/" + product), 200);
        String response = json(post(purchase ? "/api/purchases" : "/api/sales").content("""
                {"invoiceNumber":"PRECISION","supplierId":%d,"accountId":%d,"paymentType":"CONTADO",
                 "details":[{"productId":%d,"quantity":1,"price":0.01},{"productId":%d,"quantity":2,"price":%s}]}
                """.formatted(supplier, account, product, product, price)), 400);
        assertThat(JsonPath.<String>read(response, "$.error"))
                .isEqualTo("El precio unitario admite como maximo dos decimales");
        assertThat(JsonPath.<Integer>read(response, "$.status")).isEqualTo(400);
        assertThat(tables.stream().map(table -> jdbc.queryForObject(
                "SELECT COUNT(*) FROM " + table + " WHERE id_tenant = ?", Long.class, tenant)).toList())
                .isEqualTo(before);
        assertThat(json(get("/api/inventory/products/" + product), 200)).isEqualTo(originalProduct);
        balance(100);
    }

    @ParameterizedTest
    @CsvSource({"false,0.01", "true,0.01", "false,0.0100", "true,0.0100"})
    void aceptaDosDecimalesYCerosFinalesSinRedondearElPrecioAntesDeMultiplicar(boolean purchase, String price)
            throws Exception {
        initial(100);
        String response = json(post(purchase ? "/api/purchases" : "/api/sales").content("""
                {"invoiceNumber":"PRECISION-OK","supplierId":%d,"accountId":%d,"paymentType":"CONTADO",
                 "details":[{"productId":%d,"quantity":3,"price":%s}]}
                """.formatted(supplier, account, product, price)), 201);
        assertThat(amount(response, "$.details[0].price")).isEqualTo(0.01);
        assertThat(amount(response, "$.subtotal")).isEqualTo(0.03);
        assertThat(amount(response, "$.details[0].ivaAmount")).isEqualTo(0.01);
        assertThat(amount(response, "$.total")).isEqualTo(0.04);
        assertThat(json(get((purchase ? "/api/purchases/" : "/api/sales/") + id(response)), 200)).isEqualTo(response);
        assertThat(jdbc.queryForObject(purchase
                ? "SELECT precio_compra FROM detalle_compras WHERE id_compra = ?"
                : "SELECT precio_venta FROM detalle_ventas WHERE id_venta = ?", BigDecimal.class, id(response)))
                .isEqualByComparingTo("0.01");
        assertThat(jdbc.queryForObject("SELECT cantidad FROM productos WHERE id_producto = ?", Integer.class, product))
                .isEqualTo(purchase ? 103 : 97);
        balance(purchase ? 99.96 : 100.04);
    }

    @ParameterizedTest
    @CsvSource({"false,0", "true,0", "false,-0.005", "true,-0.005", "false,null", "true,null"})
    void conservaElRechazoActualDelPrecioNoPositivoONulo(boolean purchase, String price) throws Exception {
        String response = json(post(purchase ? "/api/purchases" : "/api/sales").content("""
                {"invoiceNumber":"SIGNO","supplierId":%d,"accountId":%d,"paymentType":"CONTADO",
                 "details":[{"productId":%d,"quantity":1,"price":%s}]}
                """.formatted(supplier, account, product, price)), 400);
        assertThat(JsonPath.<String>read(response, "$.error")).isEqualTo(price.equals("null")
                ? "Peticion invalida" : "El precio de Producto debe ser mayor a cero");
        balance(0);
    }

    @Test
    void ventaDeDosLineasRedondeaCadaIvaYConservaTotalPersistidoYConsultado() throws Exception {
        String created = invoice(false, "CONTADO", 0.03, 1, 2);
        comprobarFactura(created, "/api/sales/", "ventas", "id_venta", "total_venta");
        balance(0.08);
        assertThat(amount(json(get("/api/reporting/iva?desde=" + today + "&hasta=" + today), 200),
                "$.totalIvaGenerado")).isEqualTo(0.02);
    }

    @Test
    void compraDeDosLineasRedondeaCadaIvaYConservaTotalPersistidoYConsultado() throws Exception {
        initial(10.0);
        String created = invoice(true, "CONTADO", 0.03, 1, 2);
        comprobarFactura(created, "/api/purchases/", "compras", "id_compra", "total_compra");
        balance(9.92);
    }

    private void comprobarFactura(String created, String endpoint, String table, String pk, String total) throws Exception {
        assertThat(amount(created, "$.subtotal")).isEqualTo(0.06);
        assertThat(JsonPath.<List<Double>>read(created, "$.details[*].ivaAmount")).containsExactly(0.01, 0.01);
        assertThat(amount(created, "$.total")).isEqualTo(0.08);
        assertThat(json(get(endpoint + id(created)), 200)).isEqualTo(created);
        assertThat(jdbc.queryForObject("SELECT " + total + " FROM " + table + " WHERE " + pk + " = ?",
                BigDecimal.class, id(created))).isEqualByComparingTo("0.08");
    }

    @Test
    void ivaSeRedondeaDespuesDeMultiplicarLaCantidadEnAmbosDocumentos() throws Exception {
        initial(10);
        for (boolean purchase : List.of(false, true)) {
            String created = invoice(purchase, "CONTADO", 0.01, 3, 1);
            assertThat(amount(created, "$.details[0].ivaAmount")).isEqualTo(0.01);
            assertThat(amount(created, "$.total")).isEqualTo(0.04);
        }
        balance(10);
    }

    @Test
    void pagosAbonosTransferenciaYAnulacionesConservanLosCentavosSinDuplicarMovimientos() throws Exception {
        initial(100.105);
        balance(100.11);
        long purchase = id(invoice(true, "CREDITO", 10.03, 1, 1));
        String pay = json(post("/api/purchases/" + purchase + "/payments").content(
                "{\"accountId\":" + account + ",\"amount\":2.335}"), 201);
        assertThat(amount(pay, "$.amount")).isEqualTo(2.34);
        balance(97.77);
        assertThat(amount(json(get("/api/purchases/" + purchase), 200), "$.pendingBalance")).isEqualTo(9.60);
        long sale = id(invoice(false, "CREDITO", 20.03, 1, 1));
        String collection = json(post("/api/sales/" + sale + "/collections").content(
                "{\"accountId\":" + account + ",\"amount\":2.345}"), 201);
        assertThat(amount(collection, "$.amount")).isEqualTo(2.35);
        balance(100.12);
        assertThat(amount(json(get("/api/sales/" + sale), 200), "$.pendingBalance")).isEqualTo(21.49);
        for (int round = 0; round < 2; round++) {
            json(post("/api/sales/" + sale + "/annul"), 200);
            balance(97.77);
            json(post("/api/sales/" + sale + "/restore"), 200);
            balance(100.12);
            json(post("/api/purchases/" + purchase + "/annul"), 200);
            balance(102.46);
            json(post("/api/purchases/" + purchase + "/restore"), 200);
            balance(100.12);
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM movimientos WHERE id_tenant = ?", Long.class, tenant))
                .isEqualTo(2);
    }

    @Test
    void manualesEditadosYTransferenciasNormalizanElMontoAntesDeMoverSaldos() throws Exception {
        initial(1.005);
        String movement = json(post("/api/treasury/movements").content(
                "{\"accountId\":" + account + ",\"category\":\"GASTO_OPERATIVO\",\"concept\":\"Gasto\",\"amount\":1.115}"), 201);
        balance(-0.11);
        json(put("/api/treasury/movements/" + id(movement)).content(
                "{\"accountId\":" + account + ",\"category\":\"GASTO_OPERATIVO\",\"concept\":\"Gasto\",\"amount\":0.105}"), 200);
        balance(0.90);
        long destination = id(json(post("/api/treasury/accounts").content(
                "{\"name\":\"Destino\",\"initialBalance\":0.20,\"type\":\"BANCO\"}"), 201));
        json(post("/api/treasury/transfers").content(
                "{\"originAccountId\":" + account + ",\"destinationAccountId\":" + destination + ",\"amount\":0.105}"), 201);
        balance(0.79);
        assertThat(amount(json(get("/api/treasury/balance"), 200), "$.total")).isEqualTo(1.10);
    }

    @Test
    void saldoGlobalCoincideConSumaDecimalDeCuentas() throws Exception {
        initial(4406852.45);
        for (String data : List.of("Banco:16963933.02", "Nequi:6807530.08")) {
            String[] fields = data.split(":");
            json(post("/api/treasury/accounts").content("{\"name\":\"" + fields[0]
                    + "\",\"initialBalance\":" + fields[1] + ",\"type\":\"BANCO\"}"), 201);
        }
        String response = json(get("/api/treasury/balance"), 200);
        assertThat(amount(response, "$.total")).isEqualTo(28178315.55);
        assertThat(new BigDecimal(JsonPath.<Number>read(response, "$.total").toString()))
                .isEqualByComparingTo("28178315.55");
        assertThat(jdbc.queryForObject("SELECT SUM(saldo_actual) FROM cuentas WHERE id_tenant = ?",
                BigDecimal.class, tenant)).isEqualByComparingTo("28178315.55");
    }

    @Test
    void apiKpisPdfYXlsxCompartenCifrasYElFormatoMuestraCentavos() throws Exception {
        invoice(false, "CONTADO", 0.03, 1, 2);
        String range = "&desde=" + today + "&hasta=" + today;
        String kpis = json(get("/api/reporting/kpis?" + range.substring(1)), 200);
        assertThat(amount(kpis, "$.netSales")).isEqualTo(0.06);
        assertThat(amount(kpis, "$.costOfSales")).isEqualTo(0.02);
        assertThat(amount(kpis, "$.netProfit")).isEqualTo(0.04);
        for (String type : List.of("ESTADO_RESULTADOS", "MOVIMIENTOS")) {
            byte[] pdf = export(type, "PDF", range);
            try (var document = Loader.loadPDF(pdf)) {
                String text = new PDFTextStripper().getText(document);
                assertThat(text).contains(type.equals("MOVIMIENTOS") ? "$ 0,08" : "$ 0,06");
            }
            try (var workbook = new XSSFWorkbook(new ByteArrayInputStream(export(type, "XLSX", range)))) {
                boolean found = false;
                double expected = type.equals("MOVIMIENTOS") ? 0.08 : 0.06;
                for (var row : workbook.getSheetAt(0)) for (var cell : row) {
                    if (cell.getCellType() == org.apache.poi.ss.usermodel.CellType.NUMERIC
                            && cell.getNumericCellValue() == expected) {
                        assertThat(cell.getCellStyle().getDataFormatString()).isEqualTo("#,##0.00");
                        found = true;
                    }
                }
                assertThat(found).isTrue();
            }
        }
    }

    private byte[] export(String type, String format, String range) throws Exception {
        var response = mvc.perform(get("/api/reporting/export?type=" + type + "&format=" + format + range)
                .header("Authorization", "Bearer " + token)).andReturn().getResponse();
        assertThat(response.getStatus()).isEqualTo(200);
        return response.getContentAsByteArray();
    }

    @Test
    void endpointsConOpenInViewDesactivadoYAislamiento() throws Exception {
        assertThat(environment.getProperty("spring.jpa.open-in-view")).isEqualTo("false");
        long sale = id(invoice(false, "CONTADO", 0.03, 1, 2));
        initial(10);
        long purchase = id(invoice(true, "CONTADO", 0.03, 1, 2));
        for (String path : List.of("/api/sales", "/api/sales/" + sale, "/api/purchases",
                "/api/purchases/" + purchase, "/api/inventory/products", "/api/inventory/products/" + product,
                "/api/inventory/categories", "/api/inventory/tax-types", "/api/treasury/accounts",
                "/api/treasury/movements", "/api/treasury/accounts/" + account + "/movements")) json(get(path), 200);
        token = registrar();
        for (String path : List.of("/api/sales", "/api/purchases", "/api/inventory/products", "/api/treasury/movements"))
            assertThat(json(get(path), 200)).isEqualTo("[]");
        json(get("/api/sales/" + sale), 404);
        json(get("/api/purchases/" + purchase), 404);
        json(get("/api/inventory/products/" + product), 404);
        json(get("/api/treasury/accounts/" + account + "/movements"), 404);
    }

    @Test
    void anularYRestaurarUnDocumentoHistoricoNoRecalculaSusLineasNiTotal() throws Exception {
        long sale = id(invoice(false, "CONTADO", 0.03, 1, 2));
        // Representa el total agregado de una factura anterior a esta regla, ya guardada.
        jdbc.update("UPDATE ventas SET total_venta = 0.07 WHERE id_venta = ?", sale);
        jdbc.update("UPDATE movimientos SET monto = 0.07 WHERE id_venta = ?", sale);
        jdbc.update("UPDATE cuentas SET saldo_actual = 0.07 WHERE id_cuenta = ?", account);
        json(post("/api/sales/" + sale + "/annul"), 200);
        balance(0.0);
        String restored = json(post("/api/sales/" + sale + "/restore"), 200);
        assertThat(amount(restored, "$.total")).isEqualTo(0.07);
        assertThat(JsonPath.<List<Double>>read(restored, "$.details[*].ivaAmount")).containsExactly(0.01, 0.01);
        balance(0.07);
    }

    @Test
    void catalogosOrdenanPorCampoRealEIdSinCruzarTenants() throws Exception {
        TenantContext.setTenantId(tenant);
        ProductCategory category = inventory.findAllCategories().getFirst();
        for (String code : List.of("Z", "A", "B")) {
            Product p = fixture.crearProducto(category, code, 1, 10);
            p.setDescription(code.equals("Z") ? "Zeta" : "Alfa");
            inventory.saveProduct(p);
        }
        assertThat(inventory.findAllProducts()).extracting(Product::getCode).containsExactly("A", "B", "P", "Z");
        assertThat(inventory.findAllCategories()).extracting(ProductCategory::getName).isSorted();
        List<Long> categoryIds = inventory.findAllCategories().stream().map(ProductCategory::getId).toList();
        jdbc.update("UPDATE categoria_productos SET nombre_categoria_producto = 'IGUAL' WHERE id_tenant = ?", tenant);
        assertThat(inventory.findAllCategories()).extracting(ProductCategory::getId)
                .containsExactlyElementsOf(categoryIds.stream().sorted().toList());
        for (String name : List.of("Zeta", "Alfa")) fixture.crearCuenta(name);
        assertThat(treasury.findAllAccounts()).extracting(Account::getName).containsExactly("Alfa", "Caja General", "Zeta");
        fixture.crearCliente("Igual", "1001");
        fixture.crearCliente("Igual", "1002");
        TenantContext.clear();
        List<Integer> ids = JsonPath.read(json(get("/api/counterparties?role=CLIENTE"), 200), "$[?(@.name == 'Igual')].id");
        assertThat(ids).hasSize(2).isSorted();
        token = registrar();
        assertThat(json(get("/api/inventory/products"), 200)).isEqualTo("[]");
    }

    @Test
    void documentosIncluyenAnuladosYOrdenanFechaDescendenteConDesempatePorId() throws Exception {
        long firstSale = id(invoice(false, "CREDITO", 10, 1, 1));
        long secondSale = id(invoice(false, "CREDITO", 10, 1, 1));
        long oldSale = id(invoice(false, "CREDITO", 10, 1, 1));
        jdbc.update("UPDATE ventas SET fecha_venta = ? WHERE id_venta = ?", today.minusDays(1), oldSale);
        json(post("/api/sales/" + secondSale + "/annul"), 200);
        assertThat(JsonPath.<List<Integer>>read(json(get("/api/sales"), 200), "$[*].id"))
                .containsExactly((int) secondSale, (int) firstSale, (int) oldSale);
        long firstPurchase = id(invoice(true, "CREDITO", 10, 1, 1));
        long secondPurchase = id(invoice(true, "CREDITO", 10, 1, 1));
        long oldPurchase = id(invoice(true, "CREDITO", 10, 1, 1));
        jdbc.update("UPDATE compras SET fecha_compra = ? WHERE id_compra = ?", today.minusDays(1), oldPurchase);
        json(post("/api/purchases/" + secondPurchase + "/annul"), 200);
        assertThat(JsonPath.<List<Integer>>read(json(get("/api/purchases"), 200), "$[*].id"))
                .containsExactly((int) secondPurchase, (int) firstPurchase, (int) oldPurchase);
    }

    @Test
    void movimientosConservanOrdenDescendenteEnPantallaYAscendenteEnReporte() throws Exception {
        TenantContext.setTenantId(tenant);
        Account cash = treasury.findAccountById(account).orElseThrow();
        long first = treasury.registrarIngresoOcasional(cash, "Primero", 0.10, today, null).getId();
        long second = treasury.registrarIngresoOcasional(cash, "Segundo", 0.20, today, null).getId();
        long old = treasury.registrarIngresoOcasional(cash, "Anterior", 0.30, today.minusDays(1), null).getId();
        assertThat(treasury.buscarMovimientos(null, null, null, null)).extracting(m -> m.getId())
                .containsExactly(second, first, old);
        assertThat(treasury.listarParaReporte(null, today.minusDays(1), today, 100)).extracting(m -> m.id())
                .containsExactly(old, first, second);
    }
}
