package com.servibox.backend.sales;

import com.servibox.backend.counterparties.entity.Counterparty;
import com.servibox.backend.inventory.entity.Product;
import com.servibox.backend.inventory.entity.ProductCategory;
import com.servibox.backend.inventory.service.InventoryService;
import com.servibox.backend.sales.entity.PaymentType;
import com.servibox.backend.sales.entity.Sale;
import com.servibox.backend.sales.entity.SaleStatus;
import com.servibox.backend.sales.service.InsufficientStockException;
import com.servibox.backend.sales.service.InvalidSaleOperationException;
import com.servibox.backend.sales.service.SalesService;
import com.servibox.backend.tenant.TenantContext;
import com.servibox.backend.treasury.entity.Account;
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
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Restaurar una factura ANULADA es el inverso exacto de anularla. La propiedad central:
 * foto antes de anular, anular, restaurar, y la foto sale identica.
 */
@SpringBootTest
@ActiveProfiles("test")
class SalesRestoreTest {

    /** Fecha de la factura distinta de hoy, para que se note si el movimiento recreado la pierde. */
    private static final LocalDate FECHA_FACTURA = LocalDate.now().minusDays(10);

    @Autowired
    private SalesFixture fixture;

    @Autowired
    private SalesService salesService;

    @Autowired
    private InventoryService inventoryService;

    @Autowired
    private TreasuryService treasuryService;

    private Product llanta;
    private Product rin;
    private Account caja;
    private Account banco;
    private Counterparty cliente;

    @BeforeEach
    void seed() {
        fixture.usarTenant(fixture.crearTenant());
        ProductCategory categoria = fixture.crearCategoriaConIva();
        llanta = fixture.crearProducto(categoria, "LLA-001", 100000.0, 10);
        rin = fixture.crearProducto(categoria, "RIN-001", 50000.0, 10);
        caja = fixture.crearCuenta("Caja General");
        banco = fixture.crearCuenta("Bancolombia");
        cliente = fixture.crearCliente("Cliente Uno", "900123456");
    }

    @AfterEach
    void cleanUp() {
        TenantContext.clear();
    }

    /** Un movimiento sin su id: lo que tiene que coincidir entre el original y el recreado. */
    record Mov(MovementType type, Double amount, String concept, Long accountId, LocalDate date,
               MovementSourceType sourceType, Long sourceId) {
    }

    /** Todo lo que anular toca y restaurar debe devolver. */
    record Foto(SaleStatus estado, Map<String, Double> saldos, Map<String, Integer> stock, List<Mov> movimientos) {
    }

    private Foto foto(Sale venta) {
        List<Mov> movimientos = Stream.of(caja, banco)
                .flatMap(c -> treasuryService.findMovementsByAccountId(c.getId()).stream())
                .map(m -> new Mov(m.getType(), m.getAmount(), m.getConcept(), m.getAccount().getId(),
                        m.getDate(), m.getSourceType(), m.getSourceId()))
                .sorted(Comparator.comparing(Mov::toString))
                .toList();
        return new Foto(estadoDe(venta),
                Map.of("caja", saldoDe(caja), "banco", saldoDe(banco)),
                Map.of("llanta", stockDe(llanta), "rin", stockDe(rin)),
                movimientos);
    }

    /** Anula y restaura, y exige que todo quede como estaba antes de anular. */
    private Sale anularYRestaurarSinCambios(Sale venta) {
        Foto antes = foto(venta);
        salesService.anularFactura(venta.getId());
        Sale restaurada = salesService.restaurarFactura(venta.getId());
        assertThat(foto(venta)).isEqualTo(antes);
        return restaurada;
    }

    private Sale facturar(String numero, PaymentType tipo, Account cuenta, SalesService.LineaFactura... lineas) {
        return salesService.crearFactura(numero, cliente, FECHA_FACTURA, null, tipo, cuenta, "Efectivo",
                List.of(lineas));
    }

    private SalesService.LineaFactura linea(Product producto, int cantidad) {
        return new SalesService.LineaFactura(producto.getId(), cantidad, 100000.0);
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

    private double pendienteDe(Sale venta) {
        return salesService.saldoPendiente(salesService.findSaleById(venta.getId()).orElseThrow());
    }

    @Test
    void ventaDeContadoQuedaIdenticaConSuIngresoYSuFechaOriginal() {
        Sale venta = facturar("VEN-00001", PaymentType.CONTADO, caja, linea(llanta, 3), linea(rin, 1));

        Sale restaurada = anularYRestaurarSinCambios(venta);

        assertThat(restaurada.getStatus()).isEqualTo(SaleStatus.PAGADA);
        assertThat(treasuryService.findMovementsByAccountId(caja.getId()))
                .singleElement()
                .satisfies(m -> {
                    assertThat(m.getConcept()).isEqualTo("Venta VEN-00001");
                    assertThat(m.getDate()).isEqualTo(FECHA_FACTURA);
                    assertThat(m.getAmount()).isEqualTo(476000.0);
                });
    }

    @Test
    void ventaACreditoConAbonoParcialQuedaPendienteConElMismoSaldo() {
        Sale venta = facturar("VEN-00002", PaymentType.CREDITO, null, linea(llanta, 1));
        salesService.registrarAbono(venta.getId(), caja.getId(), 19000.0);

        Sale restaurada = anularYRestaurarSinCambios(venta);

        assertThat(restaurada.getStatus()).isEqualTo(SaleStatus.PENDIENTE);
        assertThat(pendienteDe(venta)).isEqualTo(100000.0);
    }

    @Test
    void ventaACreditoPagadaConAbonosQuedaPagada() {
        Sale venta = facturar("VEN-00003", PaymentType.CREDITO, null, linea(llanta, 1));
        salesService.registrarAbono(venta.getId(), caja.getId(), 100000.0);
        salesService.registrarAbono(venta.getId(), banco.getId(), 19000.0);

        Sale restaurada = anularYRestaurarSinCambios(venta);

        assertThat(restaurada.getStatus()).isEqualTo(SaleStatus.PAGADA);
        assertThat(pendienteDe(venta)).isEqualTo(0.0);
    }

    @Test
    void ventaACreditoSinAbonosNoRecreaNadaYQuedaPendiente() {
        Sale venta = facturar("VEN-00004", PaymentType.CREDITO, null, linea(llanta, 2));

        Sale restaurada = anularYRestaurarSinCambios(venta);

        assertThat(restaurada.getStatus()).isEqualTo(SaleStatus.PENDIENTE);
        assertThat(foto(venta).movimientos()).isEmpty();
    }

    /**
     * Mientras estaba anulada, otra factura vendio ese stock. La primera linea (rin) si
     * alcanza y se descuenta antes de fallar en la llanta: la transaccion la deshace, y
     * tampoco queda ningun ingreso recreado.
     */
    @Test
    void sinStockSuficienteRechazaSinTocarStockNiTesoreria() {
        Sale anulada = facturar("VEN-00005", PaymentType.CONTADO, caja, linea(rin, 2), linea(llanta, 6));
        salesService.anularFactura(anulada.getId());
        facturar("VEN-00006", PaymentType.CREDITO, null, linea(llanta, 8));
        Foto antes = foto(anulada);
        assertThat(antes.stock()).containsEntry("llanta", 2).containsEntry("rin", 10);

        assertThatThrownBy(() -> salesService.restaurarFactura(anulada.getId()))
                .isInstanceOf(InsufficientStockException.class)
                .hasMessage("Stock insuficiente para Producto LLA-001: hay 2 y se piden 6");

        assertThat(foto(anulada)).isEqualTo(antes);
        assertThat(antes.estado()).isEqualTo(SaleStatus.ANULADA);
        assertThat(antes.movimientos()).isEmpty();
    }

    @Test
    void despuesDeRestaurarUnAbonoQueCubreElSaldoLaDejaPagada() {
        Sale venta = facturar("VEN-00007", PaymentType.CREDITO, null, linea(llanta, 1));
        salesService.registrarAbono(venta.getId(), caja.getId(), 19000.0);
        anularYRestaurarSinCambios(venta);

        salesService.registrarAbono(venta.getId(), banco.getId(), 100000.0);

        assertThat(estadoDe(venta)).isEqualTo(SaleStatus.PAGADA);
        assertThat(pendienteDe(venta)).isEqualTo(0.0);
        assertThat(saldoDe(caja)).isEqualTo(19000.0);
        assertThat(saldoDe(banco)).isEqualTo(100000.0);
    }

    @Test
    void volverAAnularUnaRestauradaDejaTodoComoLaPrimeraAnulacion() {
        Sale venta = facturar("VEN-00008", PaymentType.CONTADO, caja, linea(llanta, 2));
        salesService.anularFactura(venta.getId());
        Foto primeraAnulacion = foto(venta);
        salesService.restaurarFactura(venta.getId());

        salesService.anularFactura(venta.getId());

        assertThat(foto(venta)).isEqualTo(primeraAnulacion);
    }

    @Test
    void unaAnuladaQueConservaMovimientosConSuOrigenSeRechazaPorInconsistencia() {
        Sale venta = facturar("VEN-00009", PaymentType.CREDITO, null, linea(llanta, 1));
        salesService.anularFactura(venta.getId());
        // Simula datos rotos: un movimiento vivo apuntando a una factura anulada.
        treasuryService.registrarIngresoDeVenta(caja, "Huerfano", 5000.0, venta, LocalDate.now());
        Foto antes = foto(venta);

        assertThatThrownBy(() -> salesService.restaurarFactura(venta.getId()))
                .isInstanceOf(InvalidSaleOperationException.class)
                .hasMessage("Inconsistencia: la factura VEN-00009 esta ANULADA pero todavia tiene "
                        + "movimientos de tesoreria; no se restaura");

        assertThat(foto(venta)).isEqualTo(antes);
    }

    @Test
    void restaurarRechazaUnaFacturaQueNoEstaAnulada() {
        Sale pagada = facturar("VEN-00010", PaymentType.CONTADO, caja, linea(llanta, 1));
        Sale pendiente = facturar("VEN-00011", PaymentType.CREDITO, null, linea(llanta, 1));

        assertThatThrownBy(() -> salesService.restaurarFactura(pagada.getId()))
                .isInstanceOf(InvalidSaleOperationException.class)
                .hasMessage("Solo se puede restaurar una factura ANULADA, y la VEN-00010 esta PAGADA");
        assertThatThrownBy(() -> salesService.restaurarFactura(pendiente.getId()))
                .isInstanceOf(InvalidSaleOperationException.class)
                .hasMessage("Solo se puede restaurar una factura ANULADA, y la VEN-00011 esta PENDIENTE");

        assertThat(stockDe(llanta)).isEqualTo(8);
        assertThat(estadoDe(pagada)).isEqualTo(SaleStatus.PAGADA);
        assertThat(estadoDe(pendiente)).isEqualTo(SaleStatus.PENDIENTE);
    }
}
