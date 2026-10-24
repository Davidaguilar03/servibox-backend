package com.servibox.backend.concurrency;

import com.jayway.jsonpath.JsonPath;
import com.servibox.backend.auth.entity.Role;
import com.servibox.backend.auth.entity.User;
import com.servibox.backend.auth.repository.UserRepository;
import com.servibox.backend.counterparties.entity.Counterparty;
import com.servibox.backend.inventory.entity.Product;
import com.servibox.backend.inventory.entity.ProductCategory;
import com.servibox.backend.sales.SalesFixture;
import com.servibox.backend.tenant.Tenant;
import com.servibox.backend.tenant.TenantContext;
import com.servibox.backend.tenant.TenantRepository;
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
import org.springframework.test.web.servlet.RequestBuilder;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntFunction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Operaciones simultaneas sobre el mismo saldo, el mismo stock o la misma factura. Cada
 * prueba lanza las peticiones en hilos reales, retenidas por un CountDownLatch para que
 * salgan a la vez, y despues comprueba que no se perdio ninguna actualizacion: el saldo de
 * cada cuenta es su balance inicial mas sus movimientos activos y el stock es exacto.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ConcurrentOperationsTest {

    private static final String PASSWORD = "clave-correcta";
    private static final int HILOS = 20;

    /**
     * Numero explicito para cada venta: el consecutivo FV- automatico no se reserva, y dos
     * ventas simultaneas sin numero chocan con 409 por numero repetido (ver 03-DECISIONS.md).
     * Aqui se prueba el stock y el saldo, no la numeracion.
     */
    private final AtomicInteger numeroVenta = new AtomicInteger();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private SalesFixture fixture;

    @Autowired
    private TenantRepository tenantRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private Long tenantId;
    private String token;
    private ProductCategory categoria;
    private Counterparty cliente;
    private Counterparty proveedor;

    @BeforeEach
    void seed() throws Exception {
        String slug = "taller-concurrencia-" + System.nanoTime();
        Tenant tenant = new Tenant();
        tenant.setName(slug);
        tenant.setSlug(slug);
        tenant.setActive(Boolean.TRUE);
        tenantId = tenantRepository.save(tenant).getId();

        fixture.usarTenant(tenantId);
        User user = new User();
        user.setUsername("admin");
        user.setPasswordHash(passwordEncoder.encode(PASSWORD));
        user.setActive(true);
        user.setRole(Role.ADMIN);
        userRepository.save(user);
        categoria = fixture.crearCategoriaConIva();
        cliente = fixture.crearCliente("Cliente Concurrente", "1010");
        proveedor = fixture.crearProveedor("Proveedor Concurrente", "800100100");
        TenantContext.clear();

        MvcResult login = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"tenantSlug":"%s","username":"admin","password":"%s"}
                                """.formatted(slug, PASSWORD)))
                .andExpect(status().isOk())
                .andReturn();
        token = JsonPath.read(login.getResponse().getContentAsString(), "$.token");
    }

    @AfterEach
    void cleanUp() {
        TenantContext.clear();
    }

    // ---- (a) cobros y pagos simultaneos a una misma cuenta ----

    @Test
    void abonosYPagosSimultaneosAUnaMismaCuentaNoPierdenSaldo() throws Exception {
        long producto = producto("ABO-1", 1000);
        long cuenta = cuenta("Caja", 1_000_000);
        List<Long> ventas = new ArrayList<>();
        List<Long> compras = new ArrayList<>();
        for (int i = 0; i < HILOS / 2; i++) {
            ventas.add(id(enviar(ventaCredito(producto, 1, 100000))));
            compras.add(id(enviar(compraCredito("PAG-" + i, proveedor.getId(), producto, 1, 10000))));
        }

        // 10 abonos de 50000 (ingresos) y 10 pagos de 5000 (egresos), todos a la misma cuenta.
        List<MvcResult> resultados = simultaneas(HILOS, i -> i % 2 == 0
                ? json("/api/sales/" + ventas.get(i / 2) + "/collections", """
                        {"accountId":%d,"amount":50000}""".formatted(cuenta))
                : json("/api/purchases/" + compras.get(i / 2) + "/payments", """
                        {"accountId":%d,"amount":5000}""".formatted(cuenta)));

        assertThat(codigos(resultados)).containsOnly(201);
        assertThat(saldo(cuenta)).isCloseTo(1_000_000 + 10 * 50000 - 10 * 5000, within(0.001));
        verificarInvariante();
    }

    // ---- (b) ventas simultaneas del mismo producto ----

    @Test
    void ventasSimultaneasDelMismoProductoDescuentanElStockExacto() throws Exception {
        long producto = producto("VEN-1", 100);
        long cuenta = cuenta("Caja", 0);

        List<MvcResult> resultados = simultaneas(HILOS, i -> ventaContado(producto, 2, 1000, cuenta));

        assertThat(codigos(resultados)).containsOnly(201);
        assertThat(stock(producto)).isEqualTo(100 - HILOS * 2);
        assertThat(saldo(cuenta)).isCloseTo(HILOS * 2 * 1000 * 1.19, within(0.001));
        verificarInvariante();
    }

    // ---- (c) compras simultaneas del mismo producto ----

    @Test
    void comprasSimultaneasDelMismoProductoSumanElStockExacto() throws Exception {
        long producto = producto("COM-1", 10);
        List<Long> proveedores = new ArrayList<>();
        fixture.usarTenant(tenantId);
        for (int i = 0; i < HILOS; i++) {
            proveedores.add(fixture.crearProveedor("Proveedor " + i, "900000" + i).getId());
        }
        TenantContext.clear();

        List<MvcResult> resultados = simultaneas(HILOS,
                i -> compraCredito("COM-" + i, proveedores.get(i), producto, 3, 10000));

        assertThat(codigos(resultados)).containsOnly(201);
        assertThat(stock(producto)).isEqualTo(10 + HILOS * 3);
    }

    // ---- (d) stock insuficiente bajo concurrencia ----

    @Test
    void conStockDiezYQuinceVentasSimultaneasSoloDiezPasan() throws Exception {
        long producto = producto("STK-1", 10);

        List<MvcResult> resultados = simultaneas(15, i -> ventaCredito(producto, 1, 1000));

        assertThat(codigos(resultados)).filteredOn(c -> c == 201).hasSize(10);
        assertThat(codigos(resultados)).filteredOn(c -> c == 409).hasSize(5);
        for (MvcResult r : resultados) {
            if (r.getResponse().getStatus() == 409) {
                assertThat(error(r)).startsWith("Stock insuficiente para Producto STK-1");
            }
        }
        assertThat(stock(producto)).isZero();
    }

    // ---- (e) transferencias simultaneas en las dos direcciones ----

    @Test
    void transferenciasSimultaneasEnLasDosDireccionesNoSeInterbloquean() throws Exception {
        long a = cuenta("Banco A", 1_000_000);
        long b = cuenta("Banco B", 1_000_000);

        List<MvcResult> resultados = simultaneas(HILOS, i -> i % 2 == 0
                ? transferencia(a, b, 1000)
                : transferencia(b, a, 1000));

        assertThat(codigos(resultados)).containsOnly(201);
        assertThat(saldo(a)).isCloseTo(1_000_000, within(0.001));
        assertThat(saldo(b)).isCloseTo(1_000_000, within(0.001));
        verificarInvariante();
    }

    // ---- (f) transferencias simultaneas sin saldo para todas ----

    @Test
    void transferenciasSimultaneasSinSaldoParaTodasSoloPasanLasQueCaben() throws Exception {
        long origen = cuenta("Origen", 5000);
        long destino = cuenta("Destino", 0);

        List<MvcResult> resultados = simultaneas(HILOS, i -> transferencia(origen, destino, 1000));

        assertThat(codigos(resultados)).filteredOn(c -> c == 201).hasSize(5);
        assertThat(codigos(resultados)).filteredOn(c -> c == 409).hasSize(HILOS - 5);
        assertThat(saldo(origen)).isCloseTo(0, within(0.001));
        assertThat(saldo(destino)).isCloseTo(5000, within(0.001));
        verificarInvariante();
    }

    // ---- (g) gastos manuales simultaneos ----

    @Test
    void gastosManualesSimultaneosEnUnaMismaCuentaNoPierdenSaldo() throws Exception {
        long cuenta = cuenta("Caja", 100_000);

        List<MvcResult> resultados = simultaneas(HILOS, i -> json("/api/treasury/movements", """
                {"accountId":%d,"category":"GASTO_OPERATIVO","concept":"Gasto %d","amount":100.5}
                """.formatted(cuenta, i)));

        assertThat(codigos(resultados)).containsOnly(201);
        assertThat(saldo(cuenta)).isCloseTo(100_000 - HILOS * 100.5, within(0.001));
        verificarInvariante();
    }

    // ---- (h) mismo numero de factura de compra cambiando mayusculas ----

    @Test
    void comprasSimultaneasConElMismoNumeroEnMayusculasYMinusculasDanUn201YUn409() throws Exception {
        long producto = producto("FAC-1", 10);

        List<MvcResult> resultados = simultaneas(2, i ->
                compraCredito(i == 0 ? "FAC-300" : "fac-300", proveedor.getId(), producto, 1, 10000));

        assertThat(codigos(resultados)).containsExactlyInAnyOrder(201, 409);
        for (MvcResult r : resultados) {
            if (r.getResponse().getStatus() == 409) {
                assertThat(error(r)).matches("Ya existe una factura de compra (FAC|fac)-300 del proveedor Proveedor Concurrente");
            }
        }
        assertThat(stock(producto)).isEqualTo(11);
    }

    // ---- (i) abonos simultaneos que superan el saldo pendiente ----

    @Test
    void abonosSimultaneosQueSuperanElPendienteNoDejanSaldoNegativo() throws Exception {
        long producto = producto("PEN-1", 10);
        long cuenta = cuenta("Caja", 0);
        // Total 119000: caben exactamente 10 abonos de 11900.
        long venta = id(enviar(ventaCredito(producto, 1, 100000)));

        List<MvcResult> resultados = simultaneas(HILOS, i -> json("/api/sales/" + venta + "/collections", """
                {"accountId":%d,"amount":11900}""".formatted(cuenta)));

        assertThat(codigos(resultados)).filteredOn(c -> c == 201).hasSize(10);
        assertThat(codigos(resultados)).filteredOn(c -> c == 400).hasSize(HILOS - 10);
        MvcResult factura = enviar(get("/api/sales/" + venta).header("Authorization", "Bearer " + token));
        assertThat(JsonPath.<Number>read(factura.getResponse().getContentAsString(), "$.pendingBalance").doubleValue())
                .isZero();
        assertThat(JsonPath.<String>read(factura.getResponse().getContentAsString(), "$.status")).isEqualTo("PAGADA");
        assertThat(saldo(cuenta)).isCloseTo(119000, within(0.001));
        verificarInvariante();
    }

    // ---- (j) anular y abonar a la vez la misma factura ----

    @Test
    void anularYAbonarALaVezDejanLaFacturaYLaCuentaCuadradas() throws Exception {
        long producto = producto("ANU-1", 100);
        long cuenta = cuenta("Caja", 0);

        for (int ronda = 0; ronda < 10; ronda++) {
            long venta = id(enviar(ventaCredito(producto, 1, 100000)));

            List<MvcResult> resultados = simultaneas(2, i -> i == 0
                    ? post("/api/sales/" + venta + "/annul").header("Authorization", "Bearer " + token)
                    : json("/api/sales/" + venta + "/collections", """
                            {"accountId":%d,"amount":50000}""".formatted(cuenta)));

            assertThat(resultados.get(0).getResponse().getStatus()).as("anular, ronda %d", ronda).isEqualTo(200);
            assertThat(resultados.get(1).getResponse().getStatus()).as("abonar, ronda %d", ronda).isIn(201, 400);
            // Gane quien gane, la venta queda anulada y su abono, si entro, revertido.
            assertThat(saldo(cuenta)).as("saldo, ronda %d", ronda).isCloseTo(0, within(0.001));
            verificarInvariante();
        }
        assertThat(stock(producto)).isEqualTo(100);
    }

    // ---- Ayudas ----

    /** Lanza n peticiones a la vez y devuelve sus resultados en el orden de i. */
    private List<MvcResult> simultaneas(int n, IntFunction<RequestBuilder> peticion) throws Exception {
        CountDownLatch salida = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(n);
        try {
            List<Future<MvcResult>> futuros = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                RequestBuilder builder = peticion.apply(i);
                futuros.add(pool.submit(() -> {
                    salida.await();
                    return mockMvc.perform(builder).andReturn();
                }));
            }
            salida.countDown();
            List<MvcResult> resultados = new ArrayList<>();
            for (Future<MvcResult> futuro : futuros) {
                resultados.add(futuro.get());
            }
            return resultados;
        } finally {
            pool.shutdownNow();
        }
    }

    private static List<Integer> codigos(List<MvcResult> resultados) {
        return resultados.stream().map(r -> r.getResponse().getStatus()).toList();
    }

    private RequestBuilder json(String ruta, String cuerpo) {
        return post(ruta)
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(cuerpo);
    }

    private MvcResult enviar(RequestBuilder peticion) throws Exception {
        MvcResult r = mockMvc.perform(peticion).andReturn();
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isBetween(200, 201);
        return r;
    }

    private static long id(MvcResult r) throws Exception {
        return JsonPath.<Number>read(r.getResponse().getContentAsString(), "$.id").longValue();
    }

    private static String error(MvcResult r) throws Exception {
        return JsonPath.read(r.getResponse().getContentAsString(), "$.error");
    }

    private long producto(String codigo, int cantidad) {
        fixture.usarTenant(tenantId);
        try {
            Product p = fixture.crearProducto(categoria, codigo, 1000, cantidad);
            return p.getId();
        } finally {
            TenantContext.clear();
        }
    }

    private long cuenta(String nombre, double inicial) throws Exception {
        return id(enviar(json("/api/treasury/accounts", """
                {"name":"%s","initialBalance":%s,"type":"BANCO"}""".formatted(nombre, inicial))));
    }

    private RequestBuilder ventaCredito(long producto, int cantidad, double precio) {
        return json("/api/sales", """
                {"invoiceNumber":"V-%d","customerId":%d,"paymentType":"CREDITO","dueDate":"2099-12-31",
                 "details":[{"productId":%d,"quantity":%d,"price":%s}]}
                """.formatted(numeroVenta.incrementAndGet(), cliente.getId(), producto, cantidad, precio));
    }

    private RequestBuilder ventaContado(long producto, int cantidad, double precio, long cuenta) {
        return json("/api/sales", """
                {"invoiceNumber":"V-%d","customerId":%d,"paymentType":"CONTADO","accountId":%d,
                 "paymentMethod":"EFECTIVO","details":[{"productId":%d,"quantity":%d,"price":%s}]}
                """.formatted(numeroVenta.incrementAndGet(), cliente.getId(), cuenta, producto, cantidad, precio));
    }

    private RequestBuilder compraCredito(String numero, long proveedorId, long producto, int cantidad, double precio) {
        return json("/api/purchases", """
                {"invoiceNumber":"%s","supplierId":%d,"paymentType":"CREDITO","dueDate":"2099-12-31",
                 "details":[{"productId":%d,"quantity":%d,"price":%s}]}
                """.formatted(numero, proveedorId, producto, cantidad, precio));
    }

    private RequestBuilder transferencia(long origen, long destino, double monto) {
        return json("/api/treasury/transfers", """
                {"originAccountId":%d,"destinationAccountId":%d,"amount":%s}
                """.formatted(origen, destino, monto));
    }

    private double saldo(long cuenta) throws Exception {
        MvcResult r = enviar(get("/api/treasury/accounts").header("Authorization", "Bearer " + token));
        List<Number> saldos = JsonPath.read(r.getResponse().getContentAsString(),
                "$[?(@.id == " + cuenta + ")].currentBalance");
        return saldos.getFirst().doubleValue();
    }

    private int stock(long producto) throws Exception {
        MvcResult r = enviar(get("/api/inventory/products/" + producto).header("Authorization", "Bearer " + token));
        return JsonPath.read(r.getResponse().getContentAsString(), "$.quantity");
    }

    private void verificarInvariante() {
        fixture.usarTenant(tenantId);
        try {
            fixture.verificarInvariante();
        } finally {
            TenantContext.clear();
        }
    }
}
