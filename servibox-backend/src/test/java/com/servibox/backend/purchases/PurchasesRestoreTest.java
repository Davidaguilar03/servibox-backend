package com.servibox.backend.purchases;

import com.servibox.backend.counterparties.entity.Counterparty;
import com.servibox.backend.inventory.entity.Product;
import com.servibox.backend.inventory.entity.ProductCategory;
import com.servibox.backend.inventory.service.InventoryService;
import com.servibox.backend.purchases.entity.PaymentType;
import com.servibox.backend.purchases.entity.Purchase;
import com.servibox.backend.purchases.entity.PurchaseStatus;
import com.servibox.backend.purchases.service.InsufficientBalanceException;
import com.servibox.backend.purchases.service.InvalidPurchaseOperationException;
import com.servibox.backend.purchases.service.PurchasesService;
import com.servibox.backend.sales.SalesFixture;
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
 * Restaurar una compra ANULADA es el inverso exacto de anularla, con la validacion de
 * saldo de la creacion. Misma propiedad central que SalesRestoreTest.
 */
@SpringBootTest
@ActiveProfiles("test")
class PurchasesRestoreTest {

    private static final LocalDate FECHA_FACTURA = LocalDate.now().minusDays(10);

    @Autowired
    private SalesFixture fixture;

    @Autowired
    private PurchasesService purchasesService;

    @Autowired
    private InventoryService inventoryService;

    @Autowired
    private TreasuryService treasuryService;

    private Product llanta;
    private Account caja;
    private Account banco;
    private Counterparty proveedor;

    @BeforeEach
    void seed() {
        fixture.usarTenant(fixture.crearTenant());
        ProductCategory categoria = fixture.crearCategoriaConIva();
        llanta = fixture.crearProducto(categoria, "LLA-001", 100000.0, 10);
        caja = fixture.crearCuenta("Caja General");
        banco = fixture.crearCuenta("Bancolombia");
        treasuryService.registrarMovimiento(caja, MovementType.INGRESO, "Fondeo de prueba", 1000000.0);
        treasuryService.registrarMovimiento(banco, MovementType.INGRESO, "Fondeo de prueba", 1000000.0);

        proveedor = fixture.crearProveedor("Importadora Andina", "900987654");
    }

    @AfterEach
    void cleanUp() {
        TenantContext.clear();
    }

    record Mov(MovementType type, Double amount, String concept, Long accountId, LocalDate date,
               MovementSourceType sourceType, Long sourceId) {
    }

    record Foto(PurchaseStatus estado, Map<String, Double> saldos, int stock, List<Mov> movimientos) {
    }

    private Foto foto(Purchase compra) {
        List<Mov> movimientos = Stream.of(caja, banco)
                .flatMap(c -> treasuryService.findMovementsByAccountId(c.getId()).stream())
                .map(m -> new Mov(m.getType(), m.getAmount(), m.getConcept(), m.getAccount().getId(),
                        m.getDate(), m.getSourceType(), m.getSourceId()))
                .sorted(Comparator.comparing(Mov::toString))
                .toList();
        return new Foto(estadoDe(compra),
                Map.of("caja", saldoDe(caja), "banco", saldoDe(banco)),
                productoActual().getQuantity(),
                movimientos);
    }

    private Purchase anularYRestaurarSinCambios(Purchase compra) {
        Foto antes = foto(compra);
        purchasesService.anularFactura(compra.getId());
        Purchase restaurada = purchasesService.restaurarCompra(compra.getId());
        assertThat(foto(compra)).isEqualTo(antes);
        return restaurada;
    }

    private Purchase comprar(String numero, PaymentType tipo, Account cuenta, int cantidad, double precio) {
        return purchasesService.crearFactura(numero, proveedor, FECHA_FACTURA, null, tipo, cuenta,
                "Efectivo", List.of(new PurchasesService.LineaCompra(llanta.getId(), cantidad, precio)));
    }

    /** Saca dinero de una cuenta sin pasar por una compra, para dejarla sin saldo. */
    private void retirar(Account cuenta, double monto) {
        treasuryService.registrarMovimiento(cuenta, MovementType.EGRESO, "Retiro", monto);
    }

    private Product productoActual() {
        return inventoryService.findProductById(llanta.getId()).orElseThrow();
    }

    private double saldoDe(Account cuenta) {
        return treasuryService.findAccountById(cuenta.getId()).orElseThrow().getCurrentBalance();
    }

    private PurchaseStatus estadoDe(Purchase compra) {
        return purchasesService.findPurchaseById(compra.getId()).orElseThrow().getStatus();
    }

    private double pendienteDe(Purchase compra) {
        return purchasesService.saldoPendiente(purchasesService.findPurchaseById(compra.getId()).orElseThrow());
    }

    @Test
    void compraDeContadoQuedaIdenticaConSuEgresoYSuFechaOriginal() {
        Purchase compra = comprar("FAC-00001", PaymentType.CONTADO, caja, 5, 100000.0);

        Purchase restaurada = anularYRestaurarSinCambios(compra);

        assertThat(restaurada.getStatus()).isEqualTo(PurchaseStatus.PAGADA);
        assertThat(treasuryService.findMovementsByAccountId(caja.getId()))
                .filteredOn(m -> m.getSourceType() == MovementSourceType.PURCHASE)
                .singleElement()
                .satisfies(m -> {
                    assertThat(m.getConcept()).isEqualTo("Compra FAC-00001");
                    assertThat(m.getDate()).isEqualTo(FECHA_FACTURA);
                    assertThat(m.getAmount()).isEqualTo(595000.0);
                });
    }

    @Test
    void compraACreditoConPagoParcialQuedaPendienteConElMismoSaldo() {
        Purchase compra = comprar("FAC-00002", PaymentType.CREDITO, null, 1, 100000.0);
        purchasesService.registrarPago(compra.getId(), caja.getId(), 19000.0);

        Purchase restaurada = anularYRestaurarSinCambios(compra);

        assertThat(restaurada.getStatus()).isEqualTo(PurchaseStatus.PENDIENTE);
        assertThat(pendienteDe(compra)).isEqualTo(100000.0);
    }

    @Test
    void compraACreditoPagadaPorCompletoQuedaPagada() {
        Purchase compra = comprar("FAC-00003", PaymentType.CREDITO, null, 1, 100000.0);
        purchasesService.registrarPago(compra.getId(), caja.getId(), 100000.0);
        purchasesService.registrarPago(compra.getId(), banco.getId(), 19000.0);

        Purchase restaurada = anularYRestaurarSinCambios(compra);

        assertThat(restaurada.getStatus()).isEqualTo(PurchaseStatus.PAGADA);
        assertThat(pendienteDe(compra)).isEqualTo(0.0);
    }

    @Test
    void compraACreditoSinPagosNoRecreaNadaYQuedaPendiente() {
        Purchase compra = comprar("FAC-00004", PaymentType.CREDITO, null, 5, 100000.0);

        Purchase restaurada = anularYRestaurarSinCambios(compra);

        assertThat(restaurada.getStatus()).isEqualTo(PurchaseStatus.PENDIENTE);
        assertThat(foto(compra).movimientos())
                .noneMatch(m -> m.sourceType() == MovementSourceType.PURCHASE);
    }

    @Test
    void compraDeContadoSinSaldoEnLaCuentaSeRechazaSinCambiarNada() {
        Purchase compra = comprar("FAC-00005", PaymentType.CONTADO, caja, 5, 100000.0);
        purchasesService.anularFactura(compra.getId());
        retirar(caja, 500000.0); // quedan 500000, la compra cuesta 595000
        Foto antes = foto(compra);

        assertThatThrownBy(() -> purchasesService.restaurarCompra(compra.getId()))
                .isInstanceOf(InsufficientBalanceException.class);

        assertThat(foto(compra)).isEqualTo(antes);
        assertThat(antes.estado()).isEqualTo(PurchaseStatus.ANULADA);
        assertThat(antes.stock()).isEqualTo(10);
    }

    /** El primer pago se recrea y el segundo no alcanza: el primero tampoco queda. */
    @Test
    void conVariosPagosSiElUltimoNoAlcanzaNoQuedaNadaAMedias() {
        Purchase compra = comprar("FAC-00006", PaymentType.CREDITO, null, 1, 100000.0);
        purchasesService.registrarPago(compra.getId(), caja.getId(), 60000.0);
        purchasesService.registrarPago(compra.getId(), banco.getId(), 59000.0);
        purchasesService.anularFactura(compra.getId());
        retirar(banco, 950000.0); // quedan 50000, el pago del banco fue de 59000
        Foto antes = foto(compra);

        assertThatThrownBy(() -> purchasesService.restaurarCompra(compra.getId()))
                .isInstanceOf(InsufficientBalanceException.class);

        assertThat(foto(compra)).isEqualTo(antes);
        assertThat(antes.saldos()).containsEntry("caja", 1000000.0).containsEntry("banco", 50000.0);
    }

    /** Ni la anulacion ni la restauracion tocan el costo que la compra le puso al producto. */
    @Test
    void restaurarNoCambiaElCostoDelProducto() {
        Purchase compra = comprar("FAC-00007", PaymentType.CREDITO, null, 1, 120000.0);
        purchasesService.anularFactura(compra.getId());
        Product antes = productoActual();
        assertThat(antes.getPurchaseCost()).isEqualTo(120000.0);

        purchasesService.restaurarCompra(compra.getId());

        Product despues = productoActual();
        assertThat(despues.getPurchaseCost()).isEqualTo(antes.getPurchaseCost());
        assertThat(despues.getSuggestedPrice()).isEqualTo(antes.getSuggestedPrice());
    }

    @Test
    void unaAnuladaQueConservaMovimientosConSuOrigenSeRechazaPorInconsistencia() {
        Purchase compra = comprar("FAC-00008", PaymentType.CREDITO, null, 1, 100000.0);
        purchasesService.anularFactura(compra.getId());
        treasuryService.registrarEgresoDeCompra(caja, "Huerfano", 5000.0, compra, LocalDate.now());
        Foto antes = foto(compra);

        assertThatThrownBy(() -> purchasesService.restaurarCompra(compra.getId()))
                .isInstanceOf(InvalidPurchaseOperationException.class)
                .hasMessage("Inconsistencia: la compra FAC-00008 esta ANULADA pero todavia tiene "
                        + "movimientos de tesoreria; no se restaura");

        assertThat(foto(compra)).isEqualTo(antes);
    }

    @Test
    void restaurarRechazaUnaCompraQueNoEstaAnulada() {
        Purchase pagada = comprar("FAC-00009", PaymentType.CONTADO, caja, 1, 100000.0);
        Purchase pendiente = comprar("FAC-00010", PaymentType.CREDITO, null, 1, 100000.0);

        assertThatThrownBy(() -> purchasesService.restaurarCompra(pagada.getId()))
                .isInstanceOf(InvalidPurchaseOperationException.class)
                .hasMessage("Solo se puede restaurar una compra ANULADA, y la FAC-00009 esta PAGADA");
        assertThatThrownBy(() -> purchasesService.restaurarCompra(pendiente.getId()))
                .isInstanceOf(InvalidPurchaseOperationException.class)
                .hasMessage("Solo se puede restaurar una compra ANULADA, y la FAC-00010 esta PENDIENTE");

        assertThat(productoActual().getQuantity()).isEqualTo(12);
    }
}
