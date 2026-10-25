package com.servibox.backend.purchases;

import com.servibox.backend.counterparties.entity.Counterparty;
import com.servibox.backend.inventory.entity.Product;
import com.servibox.backend.inventory.entity.ProductCategory;
import com.servibox.backend.inventory.entity.TaxType;
import com.servibox.backend.inventory.service.InventoryService;
import com.servibox.backend.purchases.entity.PaymentType;
import com.servibox.backend.purchases.entity.Purchase;
import com.servibox.backend.purchases.service.PurchasesService;
import com.servibox.backend.sales.SalesFixture;
import com.servibox.backend.sales.entity.Sale;
import com.servibox.backend.sales.entity.SaleDetail;
import com.servibox.backend.sales.service.SalesService;
import com.servibox.backend.shared.Moneda;
import com.servibox.backend.tenant.TenantContext;
import com.servibox.backend.treasury.entity.Account;
import com.servibox.backend.treasury.service.TreasuryService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/**
 * Costo del producto al anular y recuperar una compra. Al anular, si el costo todavia es el
 * precio de la linea de esa compra, vuelve al de la ultima compra no anulada del producto
 * (la de mayor id); al recuperar, la compra vuelve a fijar el costo si es la mas reciente.
 * Ver 03-DECISIONS.md.
 *
 * Categoria con margen 0.50 y FinancialSettings en ceros, como en PurchasesCostSyncTest:
 * precio sugerido = costo / 0.50 y IVA = costo * 0.19.
 */
@SpringBootTest
@ActiveProfiles("test")
class PurchasesCostRevertTest {

    private static final double DELTA = 0.001;
    private static final LocalDate HOY = LocalDate.now();

    @Autowired
    private SalesFixture fixture;

    @Autowired
    private PurchasesService purchasesService;

    @Autowired
    private SalesService salesService;

    @Autowired
    private InventoryService inventoryService;

    @Autowired
    private TreasuryService treasuryService;

    private Long tenantId;
    private ProductCategory categoria;
    private Product llanta;
    private Account caja;
    private Counterparty proveedor;

    @BeforeEach
    void seed() {
        tenantId = fixture.crearTenant();
        fixture.usarTenant(tenantId);
        categoria = categoriaConMargen();
        llanta = fixture.crearProducto(categoria, "LLA-REV", 10.0, 100);
        caja = fixture.crearCuenta("Caja General");
        treasuryService.registrarIngresoOcasional(caja, "Fondeo de prueba", 1000000.0, null, null);
        proveedor = fixture.crearProveedor("Importadora Andina", "900111222");
    }

    @AfterEach
    void cleanUp() {
        TenantContext.clear();
    }

    private ProductCategory categoriaConMargen() {
        TaxType iva = new TaxType();
        iva.setName("IVA");
        iva.setRate(SalesFixture.IVA);
        iva.setIsVat(Boolean.TRUE);
        iva.setAppliesToTransaction(Boolean.TRUE);
        ProductCategory nueva = new ProductCategory();
        nueva.setName("LLANTAS");
        nueva.setColor("#e74c3c");
        nueva.setYellowStockMin(5);
        nueva.setRedStockMin(2);
        nueva.setTargetMargin(0.50);
        nueva.setTaxTypes(new HashSet<>(List.of(inventoryService.saveTaxType(iva))));
        return inventoryService.saveCategory(nueva);
    }

    private Purchase comprar(String numero, PaymentType tipo, PurchasesService.LineaCompra... lineas) {
        return purchasesService.crearFactura(numero, proveedor, HOY, HOY.plusDays(30), tipo,
                tipo == PaymentType.CONTADO ? caja : null, "Efectivo", List.of(lineas));
    }

    private Purchase comprar(String numero, double precio) {
        return comprar(numero, PaymentType.CREDITO, linea(llanta, precio));
    }

    private static PurchasesService.LineaCompra linea(Product producto, double precio) {
        return new PurchasesService.LineaCompra(producto.getId(), 2, precio);
    }

    private Product recargar(Product producto) {
        return inventoryService.findProductById(producto.getId()).orElseThrow();
    }

    /**
     * Costo, IVA y precio sugerido del producto, y que IVA y precio salen del costo. IVA y
     * precio se guardan en pesos enteros, redondeados con la mitad hacia arriba.
     */
    private void costo(Product producto, double esperado) {
        Product actual = recargar(producto);
        assertThat(actual.getPurchaseCost()).isCloseTo(esperado, within(DELTA));
        assertThat(actual.getTaxAmount()).isEqualTo(Moneda.aPesosEnteros(
                BigDecimal.valueOf(esperado).multiply(BigDecimal.valueOf(SalesFixture.IVA))));
        assertThat(actual.getSuggestedPrice()).isEqualTo(Moneda.aPesosEnteros(
                BigDecimal.valueOf(esperado).multiply(BigDecimal.valueOf(2))));
    }

    // ---- (a), (b) y (c) ----

    @Test
    void anularLaUltimaCompraVuelveAlCostoDeLaAnteriorYSinOtrasSeQueda() {
        costo(llanta, 10.0);
        Purchase a = comprar("FAC-A", 12.0);
        costo(llanta, 12.0);
        Purchase b = comprar("FAC-B", 15.0);
        costo(llanta, 15.0);

        // (a) Anular B: vuelve al de A, con IVA y precio sugerido recalculados. El stock no cambia de regla.
        purchasesService.anularFactura(b.getId());
        costo(llanta, 12.0);
        assertThat(recargar(llanta).getQuantity()).isEqualTo(102);

        // (b) Anular A: no quedan compras no anuladas, el costo se queda en 12.
        purchasesService.anularFactura(a.getId());
        costo(llanta, 12.0);
        assertThat(recargar(llanta).getQuantity()).isEqualTo(100);
        fixture.verificarInvariante();
    }

    @Test
    void recuperarLaCompraMasRecienteVuelveASuCostoYUnaAnteriorNoLoToca() {
        Purchase a = comprar("FAC-A", 12.0);
        Purchase b = comprar("FAC-B", 15.0);
        purchasesService.anularFactura(b.getId());
        purchasesService.anularFactura(a.getId());
        costo(llanta, 12.0);

        // (c) Recuperar B: es la mas reciente de las no anuladas, el costo vuelve a 15.
        purchasesService.restaurarCompra(b.getId());
        costo(llanta, 15.0);

        // Recuperar A con B activa: A es anterior, el costo no cambia.
        purchasesService.restaurarCompra(a.getId());
        costo(llanta, 15.0);
        assertThat(recargar(llanta).getQuantity()).isEqualTo(104);
        fixture.verificarInvariante();
    }

    @Test
    void recuperarUnaCompraAnteriorConLaPosteriorAnuladaVuelveASuCosto() {
        Purchase a = comprar("FAC-A", 12.0);
        Purchase b = comprar("FAC-B", 15.0);
        purchasesService.anularFactura(a.getId());
        // A no fijaba el costo (lo fijo B): anularla no lo toca.
        costo(llanta, 15.0);
        purchasesService.anularFactura(b.getId());
        costo(llanta, 15.0);

        purchasesService.restaurarCompra(a.getId());
        costo(llanta, 12.0);
    }

    // ---- (d) ----

    @Test
    void unCostoEditadoAManoDespuesDeLaCompraNoSeRevierte() {
        comprar("FAC-A", 12.0);
        Purchase b = comprar("FAC-B", 15.0);
        inventoryService.actualizarProducto(llanta.getId(), llanta.getCode(), llanta.getDescription(), 20.0, categoria);
        costo(llanta, 20.0);

        purchasesService.anularFactura(b.getId());
        costo(llanta, 20.0);
    }

    @Test
    void unaCompraPosteriorQueFijoElCostoNoSeRevierteAlAnularUnaAnterior() {
        comprar("FAC-A", 12.0);
        Purchase b = comprar("FAC-B", 15.0);
        comprar("FAC-C", 18.0);

        purchasesService.anularFactura(b.getId());
        costo(llanta, 18.0);
    }

    @Test
    void laToleranciaEsDeMedioCentavo() {
        comprar("FAC-A", 12.0);
        Purchase b = comprar("FAC-B", 15.0);
        // A un centavo del precio de B ya no viene de B.
        inventoryService.actualizarProducto(llanta.getId(), llanta.getCode(), llanta.getDescription(), 15.01, categoria);

        purchasesService.anularFactura(b.getId());
        costo(llanta, 15.01);
    }

    // ---- (e) ----

    @Test
    void unaCompraConVariosProductosRevierteCadaUnoPorSuCuenta() {
        Product filtro = fixture.crearProducto(categoria, "FIL-REV", 30.0, 50);
        comprar("FAC-A", 12.0);
        // La ultima linea de un producto es la que fijo su costo: aqui 15 para la llanta.
        Purchase b = comprar("FAC-B", PaymentType.CREDITO, linea(llanta, 14.0), linea(filtro, 35.0),
                linea(llanta, 15.0));
        costo(llanta, 15.0);
        costo(filtro, 35.0);

        purchasesService.anularFactura(b.getId());

        costo(llanta, 12.0);
        costo(filtro, 35.0); // sin otra compra no anulada: se queda
        assertThat(recargar(llanta).getQuantity()).isEqualTo(102);
        assertThat(recargar(filtro).getQuantity()).isEqualTo(50);

        purchasesService.restaurarCompra(b.getId());
        costo(llanta, 15.0);
        costo(filtro, 35.0);
    }

    // ---- (f) ----

    @Test
    void contadoYCreditoSeComportanIgual() {
        Purchase a = comprar("FAC-A", PaymentType.CONTADO, linea(llanta, 12.0));
        Purchase b = comprar("FAC-B", PaymentType.CONTADO, linea(llanta, 15.0));
        Purchase c = comprar("FAC-C", PaymentType.CREDITO, linea(llanta, 18.0));

        purchasesService.anularFactura(c.getId());
        costo(llanta, 15.0);
        purchasesService.anularFactura(b.getId());
        costo(llanta, 12.0);
        purchasesService.restaurarCompra(c.getId());
        costo(llanta, 18.0);
        assertThat(a.getPaymentType()).isEqualTo(PaymentType.CONTADO);
        fixture.verificarInvariante();
    }

    // ---- (g) ----

    @Test
    void lasVentasYaEmitidasNoCambian() {
        comprar("FAC-A", 12.0);
        Purchase b = comprar("FAC-B", 15.0);
        Counterparty cliente = fixture.crearCliente("Cliente", "1010");
        Sale venta = salesService.crearFactura("FV-1", cliente, HOY, HOY.plusDays(30),
                com.servibox.backend.sales.entity.PaymentType.CREDITO, null, null,
                List.of(new SalesService.LineaFactura(llanta.getId(), 1, 40.0)));
        SaleDetail antes = salesService.detallesDe(venta).get(0);
        Sale ventaAntes = salesService.findSaleById(venta.getId()).orElseThrow();

        purchasesService.anularFactura(b.getId());
        costo(llanta, 12.0);

        SaleDetail despues = salesService.detallesDe(venta).get(0);
        Sale ventaDespues = salesService.findSaleById(venta.getId()).orElseThrow();
        assertThat(despues.getPrice()).isEqualTo(antes.getPrice());
        assertThat(despues.getIvaAmount()).isEqualTo(antes.getIvaAmount());
        assertThat(despues.getIvaDifference()).isEqualTo(antes.getIvaDifference());
        assertThat(ventaDespues.getTotal()).isEqualTo(ventaAntes.getTotal());
        assertThat(ventaDespues.getStatus()).isEqualTo(ventaAntes.getStatus());
    }

    // ---- (h) ----

    @Test
    void lasComprasDeOtroTenantNoCuentanNiSePuedenAnular() {
        Purchase b = comprar("FAC-B", 15.0);

        // El otro tenant, con un producto propio y compras a otros precios.
        Long otro = fixture.crearTenant();
        fixture.usarTenant(otro);
        ProductCategory otraCategoria = categoriaConMargen();
        Product ajeno = fixture.crearProducto(otraCategoria, "LLA-REV", 10.0, 100);
        Counterparty otroProveedor = fixture.crearProveedor("Otro proveedor", "800999888");
        purchasesService.crearFactura("FAC-X", otroProveedor, HOY, HOY.plusDays(30), PaymentType.CREDITO, null,
                null, List.of(linea(ajeno, 50.0)));
        assertThatThrownBy(() -> purchasesService.anularFactura(b.getId()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Compra no encontrada: " + b.getId());

        // En el tenant uno B no tiene otra compra propia: el costo se queda, la de 50 ajena no cuenta.
        fixture.usarTenant(tenantId);
        purchasesService.anularFactura(b.getId());
        costo(llanta, 15.0);

        fixture.usarTenant(otro);
        costo(ajeno, 50.0);
    }
}
