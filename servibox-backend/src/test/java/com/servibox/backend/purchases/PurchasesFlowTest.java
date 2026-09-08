package com.servibox.backend.purchases;

import com.servibox.backend.inventory.entity.Product;
import com.servibox.backend.inventory.entity.ProductCategory;
import com.servibox.backend.inventory.service.InventoryService;
import com.servibox.backend.purchases.entity.PaymentType;
import com.servibox.backend.purchases.entity.Purchase;
import com.servibox.backend.purchases.entity.PurchaseDetail;
import com.servibox.backend.purchases.entity.PurchaseStatus;
import com.servibox.backend.purchases.entity.Supplier;
import com.servibox.backend.purchases.service.DuplicatePurchaseInvoiceNumberException;
import com.servibox.backend.purchases.service.InsufficientBalanceException;
import com.servibox.backend.purchases.service.InvalidPurchaseOperationException;
import com.servibox.backend.purchases.service.PurchasesService;
import com.servibox.backend.sales.SalesFixture;
import com.servibox.backend.sales.entity.Sale;
import com.servibox.backend.sales.service.SalesService;
import com.servibox.backend.tenant.TenantContext;
import com.servibox.backend.treasury.entity.Account;
import com.servibox.backend.treasury.entity.Movement;
import com.servibox.backend.treasury.entity.MovementSourceType;
import com.servibox.backend.treasury.entity.MovementType;
import com.servibox.backend.treasury.service.TreasuryService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Flujo factura de compra -> inventario (suma) -> tesoreria (egreso), a nivel de servicio.
 */
@SpringBootTest
@ActiveProfiles("test")
class PurchasesFlowTest {

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

    private Product llanta;
    private Account caja;
    private Supplier proveedor;

    @BeforeEach
    void seed() {
        fixture.usarTenant(fixture.crearTenant());
        ProductCategory categoria = fixture.crearCategoriaConIva();
        llanta = fixture.crearProducto(categoria, "LLA-001", 100000.0, 10);
        caja = fixture.crearCuenta("Caja General");

        Supplier s = new Supplier();
        s.setName("Importadora Andina");
        s.setBusinessName("Importadora Andina S.A.S.");
        s.setDocument("900987654");
        s.setEmail("ventas@andina.local");
        s.setPhone("3001112233");
        proveedor = purchasesService.saveSupplier(s);
    }

    @AfterEach
    void cleanUp() {
        TenantContext.clear();
    }

    /** Mete dinero en la caja para poder comprar de contado. */
    private void fondear(double monto) {
        treasuryService.registrarMovimiento(caja, MovementType.INGRESO, "Fondeo de prueba", monto);
    }

    private Purchase comprar(String numero, PaymentType tipo, Account cuenta, int cantidad, double precio) {
        return purchasesService.crearFactura(numero, proveedor, LocalDate.now(), null, tipo, cuenta,
                "Efectivo", List.of(new PurchasesService.LineaCompra(llanta.getId(), cantidad, precio)));
    }

    private int stockDe(Product producto) {
        return inventoryService.findProductById(producto.getId()).orElseThrow().getQuantity();
    }

    private double saldoDe(Account cuenta) {
        return treasuryService.findAccountById(cuenta.getId()).orElseThrow().getCurrentBalance();
    }

    private PurchaseStatus estadoDe(Purchase compra) {
        return purchasesService.findPurchaseById(compra.getId()).orElseThrow().getStatus();
    }

    @Test
    void compraDeContadoConSaldoSuficienteSumaStockRegistraEgresoYQuedaPagada() {
        fondear(1000000.0);

        Purchase compra = comprar("FAC-00001", PaymentType.CONTADO, caja, 5, 100000.0);

        // Al reves de una venta: el stock SUBE.
        assertThat(stockDe(llanta)).isEqualTo(15);

        List<PurchaseDetail> detalles = purchasesService.detallesDe(compra);
        assertThat(detalles).hasSize(1);
        assertThat(detalles.get(0).getIvaAmount()).isEqualTo(95000.0);

        assertThat(compra.getSubtotal()).isEqualTo(500000.0);
        assertThat(compra.getIvaTotal()).isEqualTo(95000.0);
        assertThat(compra.getTotal()).isEqualTo(595000.0);
        assertThat(compra.getStatus()).isEqualTo(PurchaseStatus.PAGADA);

        // El dinero salio de la caja.
        assertThat(saldoDe(caja)).isEqualTo(1000000.0 - 595000.0);
        List<Movement> movimientos = treasuryService.findMovementsByAccountId(caja.getId());
        Movement egreso = movimientos.stream()
                .filter(m -> m.getSourceType() == MovementSourceType.PURCHASE)
                .findFirst()
                .orElseThrow();
        assertThat(egreso.getType()).isEqualTo(MovementType.EGRESO);
        assertThat(egreso.getAmount()).isEqualTo(595000.0);
        assertThat(egreso.getConcept()).isEqualTo("Compra FAC-00001");
        assertThat(egreso.getSourceId()).isEqualTo(compra.getId());
    }

    @Test
    void compraDeContadoSinSaldoSuficienteEsRechazadaSinTocarStockNiBalance() {
        fondear(100000.0);
        int stockAntes = stockDe(llanta);
        double saldoAntes = saldoDe(caja);

        assertThatThrownBy(() -> comprar("FAC-00002", PaymentType.CONTADO, caja, 5, 100000.0))
                .isInstanceOf(InsufficientBalanceException.class)
                .hasMessageContaining("Saldo insuficiente en Caja General");

        assertThat(stockDe(llanta)).isEqualTo(stockAntes);
        assertThat(saldoDe(caja)).isEqualTo(saldoAntes);
        assertThat(purchasesService.findAllPurchases()).isEmpty();
    }

    @Test
    void compraACreditoSumaStockQuedaPendienteYNoValidaSaldo() {
        // Caja en cero: a credito no se valida saldo, porque hoy no sale dinero.
        assertThat(saldoDe(caja)).isEqualTo(0.0);

        Purchase compra = comprar("FAC-00003", PaymentType.CREDITO, null, 5, 100000.0);

        assertThat(stockDe(llanta)).isEqualTo(15);
        assertThat(compra.getStatus()).isEqualTo(PurchaseStatus.PENDIENTE);
        assertThat(compra.getTotal()).isEqualTo(595000.0);
        assertThat(saldoDe(caja)).isEqualTo(0.0);
        assertThat(treasuryService.findMovementsByAccountId(caja.getId())).isEmpty();
        assertThat(purchasesService.saldoPendiente(compra)).isEqualTo(595000.0);
    }

    @Test
    void unPagoQueCubreElTotalDejaLaCompraPagadaYRegistraElEgreso() {
        Purchase compra = comprar("FAC-00004", PaymentType.CREDITO, null, 1, 100000.0);
        double total = compra.getTotal(); // 119000
        fondear(500000.0);

        purchasesService.registrarPago(compra.getId(), caja.getId(), total);

        assertThat(estadoDe(compra)).isEqualTo(PurchaseStatus.PAGADA);
        assertThat(purchasesService.saldoPendiente(compra)).isEqualTo(0.0);
        assertThat(saldoDe(caja)).isEqualTo(500000.0 - total);

        Movement egreso = treasuryService.findMovementsByAccountId(caja.getId()).stream()
                .filter(m -> m.getSourceType() == MovementSourceType.PURCHASE)
                .findFirst()
                .orElseThrow();
        assertThat(egreso.getType()).isEqualTo(MovementType.EGRESO);
        assertThat(egreso.getConcept()).isEqualTo("Pago compra FAC-00004");
    }

    @Test
    void unPagoQueExcedeElSaldoPendienteEsRechazado() {
        Purchase compra = comprar("FAC-00005", PaymentType.CREDITO, null, 1, 100000.0);
        fondear(500000.0);
        double saldoAntes = saldoDe(caja);

        assertThatThrownBy(() -> purchasesService.registrarPago(compra.getId(), caja.getId(), 200000.0))
                .isInstanceOf(InvalidPurchaseOperationException.class)
                .hasMessageContaining("excede el saldo pendiente");

        assertThat(saldoDe(caja)).isEqualTo(saldoAntes);
        assertThat(estadoDe(compra)).isEqualTo(PurchaseStatus.PENDIENTE);
    }

    @Test
    void noSePuedePagarUnaCompraQueNoEstaPendiente() {
        fondear(1000000.0);
        Purchase contado = comprar("FAC-00006", PaymentType.CONTADO, caja, 1, 100000.0);

        assertThatThrownBy(() -> purchasesService.registrarPago(contado.getId(), caja.getId(), 1000.0))
                .isInstanceOf(InvalidPurchaseOperationException.class)
                .hasMessageContaining("PENDIENTE");
    }

    @Test
    void anularUnaCompraPagadaQuitaElStockRevierteElMovimientoYRestauraElBalance() {
        fondear(1000000.0);
        double saldoAntesDeLaCompra = saldoDe(caja);
        int stockAntesDeLaCompra = stockDe(llanta);

        Purchase compra = comprar("FAC-00007", PaymentType.CONTADO, caja, 5, 100000.0);
        assertThat(stockDe(llanta)).isEqualTo(15);
        assertThat(saldoDe(caja)).isEqualTo(405000.0);

        purchasesService.anularFactura(compra.getId());

        assertThat(stockDe(llanta)).isEqualTo(stockAntesDeLaCompra);
        assertThat(saldoDe(caja)).isEqualTo(saldoAntesDeLaCompra);
        assertThat(treasuryService.findMovementsByAccountId(caja.getId()).stream()
                .filter(m -> m.getSourceType() == MovementSourceType.PURCHASE).toList()).isEmpty();
        assertThat(estadoDe(compra)).isEqualTo(PurchaseStatus.ANULADA);
    }

    @Test
    void anularUnaCompraPendienteSoloQuitaElStock() {
        Purchase compra = comprar("FAC-00008", PaymentType.CREDITO, null, 5, 100000.0);
        assertThat(stockDe(llanta)).isEqualTo(15);

        purchasesService.anularFactura(compra.getId());

        assertThat(stockDe(llanta)).isEqualTo(10);
        assertThat(saldoDe(caja)).isEqualTo(0.0);
        assertThat(treasuryService.findMovementsByAccountId(caja.getId())).isEmpty();
        assertThat(estadoDe(compra)).isEqualTo(PurchaseStatus.ANULADA);
    }

    /**
     * La correccion que ya se aplico en Sales: Autollantas solo revierte tesoreria si la
     * factura es de contado y esta pagada, dejando los pagos de una compra a credito sin
     * devolver. Aqui se revierten todos.
     */
    @Test
    void anularUnaCompraConPagosRevierteEsosPagos() {
        Purchase compra = comprar("FAC-00009", PaymentType.CREDITO, null, 1, 100000.0);
        fondear(500000.0);
        double saldoAntesDelPago = saldoDe(caja);

        purchasesService.registrarPago(compra.getId(), caja.getId(), 19000.0);
        assertThat(saldoDe(caja)).isEqualTo(saldoAntesDelPago - 19000.0);

        purchasesService.anularFactura(compra.getId());

        assertThat(saldoDe(caja)).isEqualTo(saldoAntesDelPago);
        assertThat(treasuryService.findMovementsByAccountId(caja.getId()).stream()
                .filter(m -> m.getSourceType() == MovementSourceType.PURCHASE).toList()).isEmpty();
        assertThat(stockDe(llanta)).isEqualTo(10);
        assertThat(estadoDe(compra)).isEqualTo(PurchaseStatus.ANULADA);
    }

    @Test
    void noSePuedeAnularDosVecesLaMismaCompra() {
        Purchase compra = comprar("FAC-00010", PaymentType.CREDITO, null, 1, 100000.0);
        purchasesService.anularFactura(compra.getId());

        assertThatThrownBy(() -> purchasesService.anularFactura(compra.getId()))
                .isInstanceOf(InvalidPurchaseOperationException.class)
                .hasMessageContaining("ya esta anulada");
    }

    /**
     * Si parte de lo comprado ya se vendio, quitar el stock dejaria al producto en
     * negativo. Se rechaza antes de tocar nada.
     */
    @Test
    void anularUnaCompraCuyoStockYaSeVendioEnParteEsRechazado() {
        Purchase compra = comprar("FAC-00011", PaymentType.CREDITO, null, 5, 100000.0);
        assertThat(stockDe(llanta)).isEqualTo(15);

        // Se venden 12 de las 15 unidades: quedan 3, menos que las 5 compradas.
        Sale venta = salesService.crearFactura("VEN-90001", null, LocalDate.now(), null,
                com.servibox.backend.sales.entity.PaymentType.CREDITO, null, null,
                List.of(new SalesService.LineaFactura(llanta.getId(), 12, 150000.0)));
        assertThat(venta.getId()).isNotNull();
        assertThat(stockDe(llanta)).isEqualTo(3);

        assertThatThrownBy(() -> purchasesService.anularFactura(compra.getId()))
                .isInstanceOf(InvalidPurchaseOperationException.class)
                .hasMessageContaining("quedan 3 unidades y habria que quitar 5");

        // Nada cambio: ni el stock ni el estado.
        assertThat(stockDe(llanta)).isEqualTo(3);
        assertThat(estadoDe(compra)).isEqualTo(PurchaseStatus.PENDIENTE);
    }

    @Test
    void dosComprasConElMismoNumeroEnElMismoTenantFalla() {
        comprar("FAC-00012", PaymentType.CREDITO, null, 1, 100000.0);

        assertThatThrownBy(() -> comprar("FAC-00012", PaymentType.CREDITO, null, 1, 100000.0))
                .isInstanceOf(DuplicatePurchaseInvoiceNumberException.class)
                .hasMessage("Ya existe una factura de compra con el numero FAC-00012");
    }
}
