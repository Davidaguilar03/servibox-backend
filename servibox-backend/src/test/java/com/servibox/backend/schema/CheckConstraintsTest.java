package com.servibox.backend.schema;

import com.servibox.backend.counterparties.entity.Counterparty;
import com.servibox.backend.inventory.entity.Product;
import com.servibox.backend.purchases.entity.Purchase;
import com.servibox.backend.purchases.entity.PurchaseDetail;
import com.servibox.backend.purchases.repository.PurchaseDetailRepository;
import com.servibox.backend.purchases.service.PurchasesService;
import com.servibox.backend.sales.SalesFixture;
import com.servibox.backend.sales.entity.Sale;
import com.servibox.backend.sales.entity.SaleDetail;
import com.servibox.backend.sales.repository.SaleDetailRepository;
import com.servibox.backend.sales.service.SalesService;
import com.servibox.backend.tenant.TenantContext;
import com.servibox.backend.treasury.entity.Account;
import com.servibox.backend.treasury.entity.Movement;
import com.servibox.backend.treasury.entity.MovementCategory;
import com.servibox.backend.treasury.entity.MovementType;
import com.servibox.backend.treasury.repository.MovementRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Los CHECK de monto y cantidad existen en la base: se prueban escribiendo por el
 * repositorio, saltandose las validaciones del service.
 */
@SpringBootTest
@ActiveProfiles("test")
class CheckConstraintsTest {

    @Autowired
    private SalesFixture fixture;

    @Autowired
    private SalesService salesService;

    @Autowired
    private PurchasesService purchasesService;

    @Autowired
    private MovementRepository movementRepository;

    @Autowired
    private SaleDetailRepository saleDetailRepository;

    @Autowired
    private PurchaseDetailRepository purchaseDetailRepository;

    private Account caja;
    private Product llanta;
    private Counterparty cliente;
    private Counterparty proveedor;

    @BeforeEach
    void seed() {
        fixture.usarTenant(fixture.crearTenant());
        caja = fixture.crearCuenta("Caja General");
        llanta = fixture.crearProducto(fixture.crearCategoriaConIva(), "LLA-CHECK", 100.0, 10);
        cliente = fixture.crearCliente("Cliente Check", "100100100");
        proveedor = fixture.crearProveedor("Proveedor Check", "900900900");
    }

    @AfterEach
    void cleanUp() {
        TenantContext.clear();
    }

    private Movement gasto(double monto) {
        Movement movimiento = new Movement();
        movimiento.setAccount(caja);
        movimiento.setCategory(MovementCategory.GASTO_OPERATIVO);
        movimiento.setType(MovementType.EGRESO);
        movimiento.setConcept("Insertado a mano");
        movimiento.setAmount(monto);
        movimiento.setDate(LocalDate.now());
        return movimiento;
    }

    private void laBaseLoRechazaPor(Runnable escritura, String restriccion) {
        assertThatThrownBy(escritura::run)
                .isInstanceOf(DataIntegrityViolationException.class)
                .satisfies(e -> assertThat(e.getMessage()).containsIgnoringCase(restriccion));
    }

    @Test
    void elMontoDeUnMovimientoTieneQueSerPositivo() {
        laBaseLoRechazaPor(() -> movementRepository.saveAndFlush(gasto(0.0)), "ck_movimiento_monto");
        laBaseLoRechazaPor(() -> movementRepository.saveAndFlush(gasto(-500.0)), "ck_movimiento_monto");
        assertThatCode(() -> movementRepository.saveAndFlush(gasto(0.01))).doesNotThrowAnyException();
    }

    @Test
    void laCantidadDeUnaLineaDeVentaTieneQueSerPositiva() {
        Sale venta = salesService.crearFactura("VEN-CHECK", cliente, LocalDate.now(), LocalDate.now().plusDays(30),
                com.servibox.backend.sales.entity.PaymentType.CREDITO, null, null,
                List.of(new SalesService.LineaFactura(llanta.getId(), 1, 200.0)));

        laBaseLoRechazaPor(() -> saleDetailRepository.saveAndFlush(lineaDeVenta(venta, 0)), "ck_detalle_venta_cantidad");
        laBaseLoRechazaPor(() -> saleDetailRepository.saveAndFlush(lineaDeVenta(venta, -1)), "ck_detalle_venta_cantidad");
        assertThatCode(() -> saleDetailRepository.saveAndFlush(lineaDeVenta(venta, 1))).doesNotThrowAnyException();
    }

    @Test
    void laCantidadDeUnaLineaDeCompraTieneQueSerPositiva() {
        Purchase compra = purchasesService.crearFactura("FAC-CHECK", proveedor, LocalDate.now(), LocalDate.now().plusDays(30),
                com.servibox.backend.purchases.entity.PaymentType.CREDITO, null, null,
                List.of(new PurchasesService.LineaCompra(llanta.getId(), 1, 100.0)));

        laBaseLoRechazaPor(() -> purchaseDetailRepository.saveAndFlush(lineaDeCompra(compra, 0)), "ck_detalle_compra_cantidad");
        laBaseLoRechazaPor(() -> purchaseDetailRepository.saveAndFlush(lineaDeCompra(compra, -1)), "ck_detalle_compra_cantidad");
        assertThatCode(() -> purchaseDetailRepository.saveAndFlush(lineaDeCompra(compra, 1))).doesNotThrowAnyException();
    }

    private SaleDetail lineaDeVenta(Sale venta, int cantidad) {
        SaleDetail linea = new SaleDetail();
        linea.setSale(venta);
        linea.setProduct(llanta);
        linea.setQuantity(cantidad);
        linea.setPrice(200.0);
        linea.setIvaAmount(0.0);
        linea.setIvaDifference(0.0);
        return linea;
    }

    private PurchaseDetail lineaDeCompra(Purchase compra, int cantidad) {
        PurchaseDetail linea = new PurchaseDetail();
        linea.setPurchase(compra);
        linea.setProduct(llanta);
        linea.setQuantity(cantidad);
        linea.setPrice(100.0);
        linea.setIvaAmount(0.0);
        return linea;
    }
}
