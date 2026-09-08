package com.servibox.backend.purchases;

import com.servibox.backend.inventory.entity.Product;
import com.servibox.backend.inventory.entity.ProductCategory;
import com.servibox.backend.inventory.entity.TaxType;
import com.servibox.backend.inventory.service.InventoryService;
import com.servibox.backend.purchases.entity.PaymentType;
import com.servibox.backend.purchases.entity.Purchase;
import com.servibox.backend.purchases.entity.Supplier;
import com.servibox.backend.purchases.service.PurchasesService;
import com.servibox.backend.sales.SalesFixture;
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
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Comprar a un costo nuevo reescribe el costo del producto y rehace su precio sugerido,
 * igual que savePurchaseWithDetails de Autollantas. Ver 03-DECISIONS.md.
 *
 * La categoria se arma aqui con margen 0.50 en vez de usar la de SalesFixture (margen 0):
 * con margen 0 el precio sugerido es igual al costo y el test no distinguiria un
 * recalculo real de una simple copia.
 */
@SpringBootTest
@ActiveProfiles("test")
class PurchasesCostSyncTest {

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
    private Supplier proveedor;

    @BeforeEach
    void seed() {
        fixture.usarTenant(fixture.crearTenant());

        TaxType iva = new TaxType();
        iva.setName("IVA");
        iva.setRate(SalesFixture.IVA);
        iva.setIsVat(Boolean.TRUE);
        iva.setAppliesToTransaction(Boolean.TRUE);

        ProductCategory categoria = new ProductCategory();
        categoria.setName("LLANTAS");
        categoria.setColor("#e74c3c");
        categoria.setTargetMargin(0.50);
        categoria.setTaxTypes(new ArrayList<>(List.of(inventoryService.saveTaxType(iva))));
        categoria = inventoryService.saveCategory(categoria);

        llanta = fixture.crearProducto(categoria, "LLA-COSTO", 20000.0, 10);
        caja = fixture.crearCuenta("Caja General");

        Supplier s = new Supplier();
        s.setName("Importadora Andina");
        s.setDocument("900111222");
        proveedor = purchasesService.saveSupplier(s);
    }

    @AfterEach
    void cleanUp() {
        TenantContext.clear();
    }

    private Purchase comprar(String numero, PaymentType tipo, Account cuenta,
                             PurchasesService.LineaCompra... lineas) {
        return purchasesService.crearFactura(numero, proveedor, LocalDate.now(), null, tipo, cuenta,
                "Efectivo", List.of(lineas));
    }

    private Product recargarLlanta() {
        return inventoryService.findProductById(llanta.getId()).orElseThrow();
    }

    /**
     * Mismo calculo que InventoryPricingTest.suggestedPriceMantieneLaFormulaDeAutollantas:
     * FinancialSettings en ceros deja k = 1 y divisor = 1 - 0 - 0.50 = 0.50, asi que
     * suggestedPrice = costo / 0.50. Con costo 25000 son 50000.
     */
    @Test
    void comprarActualizaElPurchaseCostYRecalculaElPrecioSugerido() {
        assertThat(llanta.getPurchaseCost()).isEqualTo(20000.0);
        assertThat(llanta.getSuggestedPrice()).isCloseTo(40000.0, within(0.001));

        comprar("FAC-COSTO-1", PaymentType.CREDITO, null,
                new PurchasesService.LineaCompra(llanta.getId(), 3, 25000.0));

        Product despues = recargarLlanta();
        assertThat(despues.getPurchaseCost()).isEqualTo(25000.0);
        assertThat(despues.getSuggestedPrice()).isCloseTo(50000.0, within(0.001));
        assertThat(despues.getTaxAmount()).isCloseTo(25000.0 * SalesFixture.IVA, within(0.001));
        // El stock se sumo igual que antes.
        assertThat(despues.getQuantity()).isEqualTo(13);
    }

    /**
     * En Autollantas el bloque que reescribe el costo corre antes y fuera del if de
     * Contado, asi que no depende del tipo de pago. Aqui tampoco.
     */
    @Test
    void unaCompraDeContadoTambienActualizaElCosto() {
        treasuryService.registrarMovimiento(caja, MovementType.INGRESO, "Fondeo de prueba", 1000000.0);

        comprar("FAC-COSTO-2", PaymentType.CONTADO, caja,
                new PurchasesService.LineaCompra(llanta.getId(), 2, 25000.0));

        Product despues = recargarLlanta();
        assertThat(despues.getPurchaseCost()).isEqualTo(25000.0);
        assertThat(despues.getSuggestedPrice()).isCloseTo(50000.0, within(0.001));
    }

    /**
     * Caso raro pero posible: el mismo producto en dos lineas de la misma factura a
     * precios distintos. Las lineas se procesan en orden y cada una pisa el costo, asi que
     * gana la ultima. Es lo que hace el for de savePurchaseWithDetails en Autollantas.
     */
    @Test
    void conDosLineasDelMismoProductoGanaElPrecioDeLaUltima() {
        comprar("FAC-COSTO-3", PaymentType.CREDITO, null,
                new PurchasesService.LineaCompra(llanta.getId(), 1, 25000.0),
                new PurchasesService.LineaCompra(llanta.getId(), 1, 30000.0));

        Product despues = recargarLlanta();
        assertThat(despues.getPurchaseCost()).isEqualTo(30000.0);
        assertThat(despues.getSuggestedPrice()).isCloseTo(60000.0, within(0.001));
        // El stock si acumula las dos lineas.
        assertThat(despues.getQuantity()).isEqualTo(12);
    }

    /**
     * Omision heredada de Autollantas a proposito: cancelPurchase devuelve el stock y el
     * dinero, pero nunca toca purchaseCost. Ver 03-DECISIONS.md.
     */
    @Test
    void anularNoRevierteElCostoDelProducto() {
        comprar("FAC-COSTO-4", PaymentType.CREDITO, null,
                new PurchasesService.LineaCompra(llanta.getId(), 3, 25000.0));
        Purchase compra = purchasesService.findAllPurchases().get(0);

        purchasesService.anularFactura(compra.getId());

        Product despues = recargarLlanta();
        // El stock si vuelve...
        assertThat(despues.getQuantity()).isEqualTo(10);
        // ...pero el costo y el precio sugerido se quedan como los dejo la compra.
        assertThat(despues.getPurchaseCost()).isEqualTo(25000.0);
        assertThat(despues.getSuggestedPrice()).isCloseTo(50000.0, within(0.001));
    }
}
