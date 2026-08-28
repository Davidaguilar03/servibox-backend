package com.servibox.backend.inventory;

import com.servibox.backend.inventory.entity.FinancialSettings;
import com.servibox.backend.inventory.entity.Product;
import com.servibox.backend.inventory.entity.ProductCategory;
import com.servibox.backend.inventory.entity.TaxType;
import com.servibox.backend.inventory.service.InventoryService;
import com.servibox.backend.tenant.Tenant;
import com.servibox.backend.tenant.TenantContext;
import com.servibox.backend.tenant.TenantRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

@SpringBootTest
@ActiveProfiles("test")
class InventoryPricingTest {

    @Autowired
    private InventoryService inventoryService;

    @Autowired
    private TenantRepository tenantRepository;

    private Long tenantId;

    @BeforeEach
    void seed() {
        Tenant tenant = new Tenant();
        tenant.setName("Taller Precios");
        tenant.setSlug("taller-precios-" + System.nanoTime());
        tenant.setActive(Boolean.TRUE);
        tenantId = tenantRepository.save(tenant).getId();
        TenantContext.setTenantId(tenantId);
    }

    @AfterEach
    void cleanUp() {
        TenantContext.clear();
    }

    private TaxType impuesto(String nombre, double rate, boolean esIva) {
        TaxType tax = new TaxType();
        tax.setName(nombre);
        tax.setRate(rate);
        tax.setIsVat(esIva);
        tax.setAppliesToTransaction(Boolean.TRUE);
        return inventoryService.saveTaxType(tax);
    }

    private ProductCategory categoria(Double margen, TaxType... impuestos) {
        ProductCategory categoria = new ProductCategory();
        categoria.setName("CATEGORIA " + System.nanoTime());
        categoria.setColor("#000000");
        categoria.setTargetMargin(margen);
        categoria.setTaxTypes(new ArrayList<>(List.of(impuestos)));
        return inventoryService.saveCategory(categoria);
    }

    private Product producto(double costo, ProductCategory categoria) {
        Product product = new Product();
        product.setCode("COD-" + System.nanoTime());
        product.setDescription("Producto de prueba");
        product.setPurchaseCost(costo);
        product.setQuantity(10);
        product.setCategory(categoria);
        return inventoryService.saveProduct(product);
    }

    /**
     * Divergencia deliberada respecto de Autollantas: alli taxAmount suma TODAS las rates
     * de la categoria. Aqui solo las marcadas isVat.
     */
    @Test
    void taxAmountSoloCuentaLosImpuestosMarcadosComoIva() {
        TaxType iva = impuesto("IVA", 0.19, true);
        ProductCategory categoria = categoria(0.0, iva);

        Product product = producto(25000.0, categoria);

        assertThat(product.getTaxAmount()).isCloseTo(4750.0, within(0.001));
    }

    @Test
    void taxAmountIgnoraLosImpuestosQueNoSonIva() {
        TaxType iva = impuesto("IVA", 0.19, true);
        TaxType reteIca = impuesto("ReteICA", 0.03, false);
        ProductCategory categoria = categoria(0.0, iva, reteIca);

        Product product = producto(25000.0, categoria);

        // Autollantas daria 25000 * 0.22 = 5500 porque suma todas las rates.
        assertThat(product.getTaxAmount()).isCloseTo(4750.0, within(0.001));
    }

    /**
     * suggestedPrice se porto sin cambios desde recalculatePrices de Autollantas.
     * Con FinancialSettings en ceros: k = 1, divisor = 1 - 0 - 0.5 = 0.5,
     * suggestedPrice = 25000 * 1 / 0.5 = 50000.
     */
    @Test
    void suggestedPriceMantieneLaFormulaDeAutollantas() {
        TaxType iva = impuesto("IVA", 0.19, true);
        TaxType reteIca = impuesto("ReteICA", 0.03, false);
        ProductCategory categoria = categoria(0.50, iva, reteIca);

        Product product = producto(25000.0, categoria);

        assertThat(product.getSuggestedPrice()).isCloseTo(50000.0, within(0.001));
        assertThat(product.getTaxAmount()).isCloseTo(4750.0, within(0.001));
    }

    @Test
    void suggestedPriceUsaLosPorcentajesDeFinancialSettings() {
        FinancialSettings fs = new FinancialSettings();
        fs.setExpensesRate(0.10);
        fs.setDianRate(0.0);
        fs.setIcaRate(0.0082);
        fs.setCardCommissionRate(0.0);
        inventoryService.saveFinancialSettings(fs);

        ProductCategory categoria = categoria(0.30, impuesto("IVA", 0.19, true));
        Product product = producto(25000.0, categoria);

        // k = (1 - 0.10) * (1 - 0) = 0.9 ; divisor = 0.9 - 0.0082 - 0.30 = 0.5918
        double esperado = 25000.0 * 0.9 / 0.5918;
        assertThat(product.getSuggestedPrice()).isCloseTo(esperado, within(0.001));
    }

    @Test
    void margenImposibleUsaElCostoComoPiso() {
        // margen 1.0 deja divisor = 1 - 0 - 1 = 0, no positivo.
        ProductCategory categoria = categoria(1.0, impuesto("IVA", 0.19, true));

        Product product = producto(25000.0, categoria);

        assertThat(product.getSuggestedPrice()).isCloseTo(25000.0, within(0.001));
    }

    @Test
    void cambiarElMargenRecalculaTodosLosProductosDeLaCategoria() {
        ProductCategory categoria = categoria(0.0, impuesto("IVA", 0.19, true));
        Product product = producto(25000.0, categoria);
        assertThat(product.getSuggestedPrice()).isCloseTo(25000.0, within(0.001));

        inventoryService.updateCategoryMargin(categoria.getId(), 0.50);

        Product recargado = inventoryService.findProductById(product.getId()).orElseThrow();
        assertThat(recargado.getSuggestedPrice()).isCloseTo(50000.0, within(0.001));
    }
}
