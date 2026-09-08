package com.servibox.backend.sales;

import com.servibox.backend.inventory.entity.Product;
import com.servibox.backend.inventory.entity.ProductCategory;
import com.servibox.backend.inventory.service.InventoryService;
import com.servibox.backend.sales.entity.Customer;
import com.servibox.backend.sales.entity.PaymentType;
import com.servibox.backend.sales.entity.Sale;
import com.servibox.backend.sales.entity.SaleStatus;
import com.servibox.backend.sales.service.InvalidSaleOperationException;
import com.servibox.backend.sales.service.SalesService;
import com.servibox.backend.tenant.TenantContext;
import com.servibox.backend.treasury.entity.Account;
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

/** Abonos y anulacion: los dos caminos que tocan tesoreria despues de emitida la factura. */
@SpringBootTest
@ActiveProfiles("test")
class SalesCollectionAndAnnulmentTest {

    @Autowired
    private SalesFixture fixture;

    @Autowired
    private SalesService salesService;

    @Autowired
    private InventoryService inventoryService;

    @Autowired
    private TreasuryService treasuryService;

    private Product llanta;
    private Account caja;
    private Customer cliente;

    @BeforeEach
    void seed() {
        fixture.usarTenant(fixture.crearTenant());
        ProductCategory categoria = fixture.crearCategoriaConIva();
        llanta = fixture.crearProducto(categoria, "LLA-001", 100000.0, 10);
        caja = fixture.crearCuenta("Caja General");
        cliente = fixture.crearCliente("Cliente Uno", "900123456");
    }

    @AfterEach
    void cleanUp() {
        TenantContext.clear();
    }

    private Sale facturar(String numero, PaymentType tipo, Account cuenta, int cantidad, double precio) {
        return salesService.crearFactura(numero, cliente, LocalDate.now(), null, tipo, cuenta, "Efectivo",
                List.of(new SalesService.LineaFactura(llanta.getId(), cantidad, precio)));
    }

    private int stockDe(Product producto) {
        return inventoryService.findProductById(producto.getId()).orElseThrow().getQuantity();
    }

    private double saldoDe(Account cuenta) {
        return treasuryService.findAccountById(cuenta.getId()).orElseThrow().getCurrentBalance();
    }

    private SaleStatus estadoDe(Sale venta) {
        return salesService.findSaleById(venta.getId()).orElseThrow().getStatus();
    }

    @Test
    void unAbonoQueCubreElTotalDejaLaFacturaPagadaYRegistraElIngreso() {
        Sale venta = facturar("VEN-00010", PaymentType.CREDITO, null, 1, 100000.0);
        double total = venta.getTotal(); // 119000
        assertThat(estadoDe(venta)).isEqualTo(SaleStatus.PENDIENTE);

        salesService.registrarAbono(venta.getId(), caja.getId(), total);

        assertThat(estadoDe(venta)).isEqualTo(SaleStatus.PAGADA);
        assertThat(saldoDe(caja)).isEqualTo(total);
        assertThat(salesService.saldoPendiente(venta)).isEqualTo(0.0);

        assertThat(treasuryService.findMovementsByAccountId(caja.getId()))
                .singleElement()
                .satisfies(m -> {
                    assertThat(m.getType()).isEqualTo(MovementType.INGRESO);
                    assertThat(m.getAmount()).isEqualTo(total);
                    assertThat(m.getConcept()).isEqualTo("Abono factura VEN-00010");
                    assertThat(m.getSourceSale().getId()).isEqualTo(venta.getId());
                });
    }

    @Test
    void unAbonoParcialDejaLaFacturaPendienteConSuSaldo() {
        Sale venta = facturar("VEN-00011", PaymentType.CREDITO, null, 1, 100000.0);

        salesService.registrarAbono(venta.getId(), caja.getId(), 19000.0);

        assertThat(estadoDe(venta)).isEqualTo(SaleStatus.PENDIENTE);
        assertThat(salesService.saldoPendiente(venta)).isEqualTo(100000.0);
        assertThat(saldoDe(caja)).isEqualTo(19000.0);
    }

    @Test
    void unAbonoQueExcedeElSaldoPendienteEsRechazado() {
        Sale venta = facturar("VEN-00012", PaymentType.CREDITO, null, 1, 100000.0);

        assertThatThrownBy(() -> salesService.registrarAbono(venta.getId(), caja.getId(), 200000.0))
                .isInstanceOf(InvalidSaleOperationException.class)
                .hasMessageContaining("excede el saldo pendiente");

        // Nada se movio.
        assertThat(saldoDe(caja)).isEqualTo(0.0);
        assertThat(estadoDe(venta)).isEqualTo(SaleStatus.PENDIENTE);
    }

    @Test
    void noSePuedeAbonarAUnaFacturaQueNoEstaPendiente() {
        Sale contado = facturar("VEN-00013", PaymentType.CONTADO, caja, 1, 100000.0);

        assertThatThrownBy(() -> salesService.registrarAbono(contado.getId(), caja.getId(), 1000.0))
                .isInstanceOf(InvalidSaleOperationException.class)
                .hasMessageContaining("PENDIENTE");
    }

    @Test
    void anularUnaFacturaPagadaDevuelveElStockBorraElMovimientoYRestauraElBalance() {
        double saldoAntesDeLaVenta = saldoDe(caja);
        assertThat(saldoAntesDeLaVenta).isEqualTo(0.0);

        Sale venta = facturar("VEN-00014", PaymentType.CONTADO, caja, 2, 200000.0);
        assertThat(stockDe(llanta)).isEqualTo(8);
        assertThat(saldoDe(caja)).isEqualTo(476000.0);
        assertThat(treasuryService.findMovementsByAccountId(caja.getId())).hasSize(1);

        salesService.anularFactura(venta.getId());

        assertThat(stockDe(llanta)).isEqualTo(10);
        assertThat(treasuryService.findMovementsByAccountId(caja.getId())).isEmpty();
        assertThat(saldoDe(caja)).isEqualTo(saldoAntesDeLaVenta);
        assertThat(estadoDe(venta)).isEqualTo(SaleStatus.ANULADA);
    }

    @Test
    void anularUnaFacturaPendienteSoloDevuelveElStock() {
        Sale venta = facturar("VEN-00015", PaymentType.CREDITO, null, 3, 200000.0);
        assertThat(stockDe(llanta)).isEqualTo(7);

        salesService.anularFactura(venta.getId());

        assertThat(stockDe(llanta)).isEqualTo(10);
        assertThat(saldoDe(caja)).isEqualTo(0.0);
        assertThat(treasuryService.findMovementsByAccountId(caja.getId())).isEmpty();
        assertThat(estadoDe(venta)).isEqualTo(SaleStatus.ANULADA);
    }

    /** Anular dos veces devolveria el stock por segunda vez y regalaria inventario. */
    @Test
    void noSePuedeAnularDosVecesLaMismaFactura() {
        Sale venta = facturar("VEN-00016", PaymentType.CONTADO, caja, 1, 100000.0);
        salesService.anularFactura(venta.getId());

        assertThatThrownBy(() -> salesService.anularFactura(venta.getId()))
                .isInstanceOf(InvalidSaleOperationException.class)
                .hasMessageContaining("ya esta anulada");

        assertThat(stockDe(llanta)).isEqualTo(10);
    }

    /** Anular una PENDIENTE con abonos tambien tiene que deshacer lo que entro en caja. */
    @Test
    void anularUnaFacturaConAbonosRevierteEsosAbonos() {
        Sale venta = facturar("VEN-00017", PaymentType.CREDITO, null, 1, 100000.0);
        salesService.registrarAbono(venta.getId(), caja.getId(), 19000.0);
        assertThat(saldoDe(caja)).isEqualTo(19000.0);

        salesService.anularFactura(venta.getId());

        assertThat(saldoDe(caja)).isEqualTo(0.0);
        assertThat(treasuryService.findMovementsByAccountId(caja.getId())).isEmpty();
        assertThat(stockDe(llanta)).isEqualTo(10);
        assertThat(estadoDe(venta)).isEqualTo(SaleStatus.ANULADA);
    }
}
