package com.servibox.backend.inventory.service;

import com.servibox.backend.inventory.entity.FinancialSettings;
import com.servibox.backend.inventory.entity.Product;
import com.servibox.backend.inventory.entity.ProductCategory;
import com.servibox.backend.inventory.entity.TaxType;
import com.servibox.backend.inventory.repository.FinancialSettingsRepository;
import com.servibox.backend.inventory.repository.ProductCategoryRepository;
import com.servibox.backend.inventory.repository.ProductRepository;
import com.servibox.backend.inventory.repository.TaxTypeRepository;
import com.servibox.backend.tenant.TenantContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class InventoryService {

    private final ProductRepository productRepository;
    private final ProductCategoryRepository productCategoryRepository;
    private final TaxTypeRepository taxTypeRepository;
    private final FinancialSettingsRepository financialSettingsRepository;

    @Transactional(readOnly = true)
    public List<Product> findAllProducts() {
        return productRepository.findAll();
    }

    /**
     * No usa productRepository.findById: el @Filter de Hibernate no se aplica a
     * EntityManager.find(), asi que ese camino devuelve productos de otros tenants.
     * Verificado con InventoryTenantIsolationTest.
     */
    @Transactional(readOnly = true)
    public Optional<Product> findProductById(Long id) {
        Long tenantId = TenantContext.getTenantId();
        if (tenantId == null) {
            return Optional.empty();
        }
        return productRepository.findByIdAndTenantId(id, tenantId);
    }

    @Transactional(readOnly = true)
    public List<Product> findProductsByCategory(Long categoryId) {
        return productRepository.findByCategoryId(categoryId);
    }

    @Transactional(readOnly = true)
    public List<ProductCategory> findAllCategories() {
        return productCategoryRepository.findAll();
    }

    /** Mismo motivo que findProductById: findById se salta el filtro por tenant. */
    @Transactional(readOnly = true)
    public Optional<ProductCategory> findCategoryById(Long id) {
        Long tenantId = TenantContext.getTenantId();
        if (tenantId == null) {
            return Optional.empty();
        }
        return productCategoryRepository.findByIdAndTenantId(id, tenantId);
    }

    @Transactional(readOnly = true)
    public List<TaxType> findAllTaxTypes() {
        return taxTypeRepository.findAll();
    }

    @Transactional
    public TaxType saveTaxType(TaxType taxType) {
        return taxTypeRepository.save(taxType);
    }

    @Transactional
    public ProductCategory saveCategory(ProductCategory category) {
        if (category.getName() != null) {
            category.setName(category.getName().trim().toUpperCase());
        }
        return productCategoryRepository.save(category);
    }

    /**
     * Guarda el producto y deja sus precios recalculados. En Autollantas saveProduct y
     * recalculatePrices se invocan por separado desde el formulario; aqui se unen porque
     * un producto guardado con precios viejos es un bug esperando.
     */
    @Transactional
    public Product saveProduct(Product product) {
        validarCodigoUnico(product);
        Product guardado = productRepository.save(product);
        recalculatePrices(guardado);
        return guardado;
    }

    /**
     * La base tambien lo impide con la restriccion uk_producto_tenant_code, pero esa
     * salta como un error crudo de constraint violation que no le sirve a nadie. Esto lo
     * convierte en un mensaje legible antes de llegar al INSERT.
     *
     * Al editar hay que excluir el propio registro: guardar un producto sin cambiarle el
     * codigo no es un duplicado.
     */
    private void validarCodigoUnico(Product product) {
        if (product.getCode() == null || product.getCode().isBlank()) {
            return;
        }
        Long tenantId = TenantContext.getTenantId();
        if (tenantId == null) {
            return;
        }
        productRepository.findByCodeAndTenantId(product.getCode(), tenantId)
                .filter(existente -> !existente.getId().equals(product.getId()))
                .ifPresent(existente -> {
                    throw new DuplicateProductCodeException(product.getCode());
                });
    }

    /**
     * Portado de InventoryService.recalculatePrices de Autollantas, con UNA divergencia
     * deliberada en taxAmount, ver 03-DECISIONS.md.
     *
     * Autollantas: taxAmount = purchaseCost * suma de TODAS las rates de la categoria.
     * ServiBox:    taxAmount = purchaseCost * suma de las rates con isVat true.
     *
     * suggestedPrice se mantiene identico a Autollantas, incluida la dependencia de
     * FinancialSettings y el piso cuando el divisor no es positivo.
     */
    @Transactional
    public void recalculatePrices(Product product) {
        if (product.getPurchaseCost() == null || product.getPurchaseCost() == 0) {
            return;
        }
        double purchaseCost = product.getPurchaseCost();

        product.setTaxAmount(purchaseCost * getIvaRateForProduct(product));

        double margin = (product.getCategory() != null && product.getCategory().getTargetMargin() != null)
                ? product.getCategory().getTargetMargin()
                : 0.0;

        FinancialSettings fs = findFinancialSettings();
        double gastosRate = fs.getExpensesRate() != null ? fs.getExpensesRate() : 0.0;
        double dianRate = fs.getDianRate() != null ? fs.getDianRate() : 0.0;
        double icaRate = fs.getIcaRate() != null ? fs.getIcaRate() : 0.0;

        // Precio tal que, despues de Gastos/DIAN/ICA, la utilidad neta quede en
        // exactamente margin% del PRECIO DE VENTA. Derivacion: utilidadNeta =
        // (P - costo) * (1-gastos) * (1-dian) - P*ica, despejando P para que
        // utilidadNeta = margin * P.
        double k = (1 - gastosRate) * (1 - dianRate);
        double divisor = k - icaRate - margin;

        double suggestedPrice;
        if (divisor <= 0) {
            // margen + ICA superan lo que dejan Gastos/DIAN: no existe precio finito que
            // garantice ese margen sobre venta. Usamos el costo como piso.
            suggestedPrice = purchaseCost;
        } else {
            suggestedPrice = purchaseCost * k / divisor;
        }
        product.setSuggestedPrice(suggestedPrice);

        productRepository.save(product);
    }

    /**
     * Tasa de IVA que aplica a un producto, como fraccion (0.19 es 19 por ciento).
     *
     * Es el equivalente del `getIvaRate(Product)` de Autollantas, que alli esta copiado
     * identico en cuatro sitios (`SaleFormController`, `SaleDetailsController`,
     * `ProductsController` y `SaleDetailRow.ivaRate()`); la propia documentacion del
     * proyecto lo marca como candidato a centralizar. Aqui vive una sola vez y es de donde
     * lo toman tanto el calculo de precios como Sales.
     *
     * Dos diferencias con Autollantas, las dos heredadas de decisiones ya tomadas:
     * suma las rates con isVat en vez de quedarse con la primera, y no tiene la rama de
     * servicios (`isService`), porque ServiBox no tiene todavia `itemType` ni `basePrice`
     * en Product. Ver 03-DECISIONS.md.
     */
    public double getIvaRateForProduct(Product product) {
        return product != null ? vatRateOf(product.getCategory()) : 0.0;
    }

    /** Solo los impuestos marcados como IVA entran en el taxAmount del producto. */
    private double vatRateOf(ProductCategory category) {
        if (category == null || category.getTaxTypes() == null) {
            return 0.0;
        }
        double total = 0.0;
        for (TaxType tax : category.getTaxTypes()) {
            if (Boolean.TRUE.equals(tax.getIsVat()) && tax.getRate() != null) {
                total += tax.getRate();
            }
        }
        return total;
    }

    /**
     * Equivalente al tab Margenes de Utilidad: al cambiar el margen de una categoria hay
     * que rehacer el precio sugerido de todos sus productos.
     */
    @Transactional
    public ProductCategory updateCategoryMargin(Long categoryId, Double targetMargin) {
        ProductCategory category = findCategoryById(categoryId)
                .orElseThrow(() -> new IllegalArgumentException("Categoria no encontrada: " + categoryId));
        category.setTargetMargin(targetMargin);
        ProductCategory guardada = saveCategory(category);

        productRepository.findByCategoryId(categoryId).forEach(this::recalculatePrices);

        return guardada;
    }

    /**
     * Una fila por tenant. Si el tenant todavia no la tiene, devuelve una instancia
     * transitoria en ceros para que el calculo no reviente.
     */
    @Transactional(readOnly = true)
    public FinancialSettings findFinancialSettings() {
        return financialSettingsRepository.findAll().stream()
                .findFirst()
                .orElseGet(() -> {
                    FinancialSettings fs = new FinancialSettings();
                    fs.setExpensesRate(0.0);
                    fs.setDianRate(0.0);
                    fs.setIcaRate(0.0);
                    return fs;
                });
    }

    @Transactional
    public FinancialSettings saveFinancialSettings(FinancialSettings settings) {
        return financialSettingsRepository.save(settings);
    }
}
