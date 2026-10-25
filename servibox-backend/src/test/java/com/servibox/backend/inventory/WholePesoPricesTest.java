package com.servibox.backend.inventory;

import com.servibox.backend.counterparties.entity.Counterparty;
import com.servibox.backend.inventory.entity.Product;
import com.servibox.backend.inventory.entity.ProductCategory;
import com.servibox.backend.inventory.entity.TaxType;
import com.servibox.backend.inventory.service.InventoryService;
import com.servibox.backend.purchases.entity.PaymentType;
import com.servibox.backend.purchases.entity.Purchase;
import com.servibox.backend.purchases.service.PurchasesService;
import com.servibox.backend.sales.SalesFixture;
import com.servibox.backend.tenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * El precio sugerido y el IVA del costo se guardan en pesos enteros (HALF_UP) en cada punto
 * que los calcula: crear y editar producto, compras que cambian el costo, anular y recuperar
 * compras con reversion de costo, margen de categoria y configuracion financiera.
 *
 * IVA del 19 por ciento y margen 0.30, configuracion en 0 salvo donde se indica:
 * costo 10350 -> IVA 1966.5 = 1967, precio 14785.71 = 14786.
 * costo 13000 -> IVA 2470, precio 18571.43 = 18571.
 */
@SpringBootTest
@ActiveProfiles("test")
class WholePesoPricesTest {

    private static final LocalDate HOY = LocalDate.of(2026, 10, 24);

    @Autowired
    private SalesFixture fixture;

    @Autowired
    private InventoryService inventoryService;

    @Autowired
    private PurchasesService purchasesService;

    private ProductCategory categoria;
    private Counterparty proveedor;

    @BeforeEach
    void seed() {
        fixture.usarTenant(fixture.crearTenant());
        ProductCategory base = fixture.crearCategoriaConIva();
        categoria = inventoryService.actualizarCategoria(base.getId(), base.getName(), base.getColor(), 5, 2, 0.30,
                base.getTaxTypes().stream().map(TaxType::getId).toList());
        proveedor = fixture.crearProveedor("Proveedor Pesos", "900400500");
    }

    @AfterEach
    void cleanUp() {
        TenantContext.clear();
    }

    private Product recargar(Product producto) {
        return inventoryService.findProductById(producto.getId()).orElseThrow();
    }

    private void precios(Product producto, double iva, double sugerido) {
        Product actual = recargar(producto);
        assertThat(actual.getTaxAmount()).as("IVA del costo").isEqualTo(iva);
        assertThat(actual.getSuggestedPrice()).as("precio sugerido").isEqualTo(sugerido);
    }

    private Purchase comprar(String numero, Product producto, double costo) {
        return purchasesService.crearFactura(numero, proveedor, HOY, HOY.plusDays(30), PaymentType.CREDITO, null, null,
                List.of(new PurchasesService.LineaCompra(producto.getId(), 1, costo)));
    }

    @Test
    void crearYEditarProductoGuardanPesosEnteros() {
        Product llanta = fixture.crearProducto(categoria, "LLA-PESO", 10350.0, 5);
        precios(llanta, 1967.0, 14786.0);

        // 12345 * 0.19 = 2345.55 -> 2346; 12345 / 0.7 = 17635.71 -> 17636.
        inventoryService.actualizarProducto(llanta.getId(), "LLA-PESO", "Llanta", 12345.0, categoria);
        precios(llanta, 2346.0, 17636.0);
    }

    @Test
    void unaCompraQueSubeElCostoYSuAnulacionYRecuperacionGuardanPesosEnteros() {
        Product llanta = fixture.crearProducto(categoria, "LLA-COMPRA", 10350.0, 5);
        comprar("FC-1", llanta, 10350.0);
        Purchase sube = comprar("FC-2", llanta, 13000.0);
        precios(llanta, 2470.0, 18571.0);

        // La reversion de costo vuelve a 10350 con IVA y precio redondeados.
        purchasesService.anularFactura(sube.getId());
        precios(llanta, 1967.0, 14786.0);

        purchasesService.restaurarCompra(sube.getId());
        precios(llanta, 2470.0, 18571.0);
    }

    @Test
    void cambiarElMargenDeLaCategoriaGuardaPesosEnteros() {
        Product llanta = fixture.crearProducto(categoria, "LLA-MARGEN", 10350.0, 5);

        // 10350 / 0.75 = 13800 exacto, sin cambio.
        inventoryService.updateCategoryMargin(categoria.getId(), 0.25);
        precios(llanta, 1967.0, 13800.0);

        // 10350 / 0.65 = 15923.08 -> 15923, por el PUT de categoria.
        inventoryService.actualizarCategoria(categoria.getId(), categoria.getName(), categoria.getColor(), 5, 2, 0.35,
                categoria.getTaxTypes().stream().map(TaxType::getId).toList());
        precios(llanta, 1967.0, 15923.0);
    }

    @Test
    void cambiarLaConfiguracionFinancieraGuardaPesosEnteros() {
        Product llanta = fixture.crearProducto(categoria, "LLA-CONF", 30000.0, 5);
        precios(llanta, 5700.0, 42857.0);

        inventoryService.guardarConfiguracionFinanciera(0.10, 0.05, 0.0082, 0.03);

        // k = 0.9 * 0.95 = 0.855; divisor = 0.855 - 0.0082 - 0.30 = 0.5468;
        // 30000 * 0.855 / 0.5468 = 46909.29 -> 46909.
        precios(llanta, 5700.0, 46909.0);
    }

    @Test
    void conDivisorNoPositivoElPrecioEsElCostoEntero() {
        inventoryService.updateCategoryMargin(categoria.getId(), 1.0);
        Product servicio = fixture.crearProducto(recargarCategoria(), "SRV-PISO", 10350.0, 1);

        // margen 1.0: divisor 1 - 0 - 1 = 0. El precio es el costo, ya entero.
        precios(servicio, 1967.0, 10350.0);
    }

    private ProductCategory recargarCategoria() {
        return inventoryService.findCategoryById(categoria.getId()).orElseThrow();
    }
}
