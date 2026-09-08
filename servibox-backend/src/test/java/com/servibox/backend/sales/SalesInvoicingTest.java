package com.servibox.backend.sales;

import com.servibox.backend.inventory.entity.Product;
import com.servibox.backend.inventory.entity.ProductCategory;
import com.servibox.backend.inventory.service.InventoryService;
import com.servibox.backend.sales.entity.Customer;
import com.servibox.backend.sales.entity.PaymentType;
import com.servibox.backend.sales.entity.Sale;
import com.servibox.backend.sales.entity.SaleDetail;
import com.servibox.backend.sales.entity.SaleStatus;
import com.servibox.backend.sales.service.DuplicateInvoiceNumberException;
import com.servibox.backend.sales.service.InsufficientStockException;
import com.servibox.backend.sales.service.InvalidSaleOperationException;
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
import static org.assertj.core.api.Assertions.within;

/**
 * Flujo factura -> inventario -> tesoreria, a nivel de servicio.
 */
@SpringBootTest
@ActiveProfiles("test")
class SalesInvoicingTest {

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

    @Test
    void facturaDeContadoDescuentaStockCongelaIvaRegistraIngresoYQuedaPagada() {
        Sale venta = facturar("VEN-00001", PaymentType.CONTADO, caja, 2, 200000.0);

        // Stock: 10 - 2
        assertThat(stockDe(llanta)).isEqualTo(8);

        // IVA congelado por linea: 200000 * 2 * 0.19
        List<SaleDetail> detalles = salesService.detallesDe(venta);
        assertThat(detalles).hasSize(1);
        assertThat(detalles.get(0).getIvaAmount()).isEqualTo(76000.0);
        assertThat(detalles.get(0).getQuantity()).isEqualTo(2);
        assertThat(detalles.get(0).getPrice()).isEqualTo(200000.0);

        // Totales de la factura.
        assertThat(venta.getSubtotal()).isEqualTo(400000.0);
        assertThat(venta.getTotal()).isEqualTo(476000.0);
        // ivaPorPagar = IVA generado (76000) - IVA descontable (taxAmount 19000 * 2)
        assertThat(venta.getIvaPorPagar()).isCloseTo(38000.0, within(0.01));

        assertThat(venta.getStatus()).isEqualTo(SaleStatus.PAGADA);

        // Ingreso en tesoreria por el total, ligado a la venta.
        assertThat(saldoDe(caja)).isEqualTo(476000.0);
        List<Movement> movimientos = treasuryService.findMovementsByAccountId(caja.getId());
        assertThat(movimientos).hasSize(1);
        assertThat(movimientos.get(0).getType()).isEqualTo(MovementType.INGRESO);
        assertThat(movimientos.get(0).getAmount()).isEqualTo(476000.0);
        assertThat(movimientos.get(0).getConcept()).isEqualTo("Venta VEN-00001");
        assertThat(movimientos.get(0).getSourceType()).isEqualTo(MovementSourceType.SALE);
        assertThat(movimientos.get(0).getSourceId()).isEqualTo(venta.getId());
    }

    @Test
    void facturaACreditoDescuentaStockQuedaPendienteYNoTocaTesoreria() {
        Sale venta = facturar("VEN-00002", PaymentType.CREDITO, null, 3, 200000.0);

        assertThat(stockDe(llanta)).isEqualTo(7);
        assertThat(venta.getStatus()).isEqualTo(SaleStatus.PENDIENTE);
        assertThat(venta.getTotal()).isEqualTo(714000.0);

        assertThat(saldoDe(caja)).isEqualTo(0.0);
        assertThat(treasuryService.findMovementsByAccountId(caja.getId())).isEmpty();
        assertThat(salesService.saldoPendiente(venta)).isEqualTo(714000.0);
    }

    /**
     * El caso que justifica congelar ivaAmount: una factura ya emitida no puede cambiar de
     * IVA porque despues alguien le edite el impuesto al producto.
     */
    @Test
    void editarElIvaDelProductoNoMueveElIvaYaCongeladoEnUnaFacturaEmitida() {
        Sale venta = facturar("VEN-00003", PaymentType.CONTADO, caja, 1, 100000.0);
        Double ivaAlFacturar = salesService.detallesDe(venta).get(0).getIvaAmount();
        assertThat(ivaAlFacturar).isEqualTo(19000.0);

        // Se le sube el IVA a la categoria del producto, del 19 al 30 por ciento.
        Product producto = inventoryService.findProductById(llanta.getId()).orElseThrow();
        producto.getCategory().getTaxTypes().get(0).setRate(0.30);
        inventoryService.saveTaxType(producto.getCategory().getTaxTypes().get(0));

        // La tasa vigente cambio...
        assertThat(inventoryService.getIvaRateForProduct(producto)).isEqualTo(0.30);

        // ...pero la factura ya emitida conserva su IVA y su total.
        assertThat(salesService.detallesDe(venta).get(0).getIvaAmount()).isEqualTo(ivaAlFacturar);
        assertThat(salesService.findSaleById(venta.getId()).orElseThrow().getTotal())
                .isEqualTo(119000.0);
    }

    @Test
    void noSePuedeFacturarMasStockDelQueHay() {
        assertThatThrownBy(() -> facturar("VEN-00004", PaymentType.CONTADO, caja, 11, 100000.0))
                .isInstanceOf(InsufficientStockException.class)
                .hasMessageContaining("hay 10 y se piden 11");
    }

    @Test
    void unaFacturaDeContadoSinCuentaEsRechazada() {
        assertThatThrownBy(() -> facturar("VEN-00005", PaymentType.CONTADO, null, 1, 100000.0))
                .isInstanceOf(InvalidSaleOperationException.class)
                .hasMessageContaining("necesita la cuenta");
    }

    @Test
    void dosFacturasConElMismoNumeroEnElMismoTenantFalla() {
        facturar("VEN-00006", PaymentType.CONTADO, caja, 1, 100000.0);

        assertThatThrownBy(() -> facturar("VEN-00006", PaymentType.CONTADO, caja, 1, 100000.0))
                .isInstanceOf(DuplicateInvoiceNumberException.class)
                .hasMessage("Ya existe una factura con el numero VEN-00006");
    }
}
