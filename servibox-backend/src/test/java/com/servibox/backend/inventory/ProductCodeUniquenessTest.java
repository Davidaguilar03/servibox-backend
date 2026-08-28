package com.servibox.backend.inventory;

import com.servibox.backend.inventory.entity.Product;
import com.servibox.backend.inventory.entity.ProductCategory;
import com.servibox.backend.inventory.repository.ProductRepository;
import com.servibox.backend.inventory.service.DuplicateProductCodeException;
import com.servibox.backend.inventory.service.InventoryService;
import com.servibox.backend.tenant.Tenant;
import com.servibox.backend.tenant.TenantContext;
import com.servibox.backend.tenant.TenantRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatCode;

@SpringBootTest
@ActiveProfiles("test")
class ProductCodeUniquenessTest {

    private static final String CODIGO = "LLA-001";

    @Autowired
    private InventoryService inventoryService;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private TenantRepository tenantRepository;

    private Long tenantUno;
    private Long tenantDos;

    @BeforeEach
    void seed() {
        tenantUno = crearTenant();
        tenantDos = crearTenant();
    }

    @AfterEach
    void cleanUp() {
        TenantContext.clear();
    }

    private Long crearTenant() {
        Tenant tenant = new Tenant();
        String slug = "taller-" + System.nanoTime();
        tenant.setName(slug);
        tenant.setSlug(slug);
        tenant.setActive(Boolean.TRUE);
        return tenantRepository.save(tenant).getId();
    }

    private ProductCategory categoriaDe(Long tenantId) {
        TenantContext.setTenantId(tenantId);
        ProductCategory categoria = new ProductCategory();
        categoria.setName("LLANTAS");
        categoria.setColor("#e74c3c");
        categoria.setTargetMargin(0.0);
        return inventoryService.saveCategory(categoria);
    }

    private Product nuevoProducto(String code, ProductCategory categoria) {
        Product product = new Product();
        product.setCode(code);
        product.setDescription("Producto de prueba");
        product.setPurchaseCost(25000.0);
        product.setQuantity(5);
        product.setCategory(categoria);
        return product;
    }

    @Test
    void dosProductosConElMismoCodigoEnElMismoTenantFalla() {
        ProductCategory categoria = categoriaDe(tenantUno);
        inventoryService.saveProduct(nuevoProducto(CODIGO, categoria));

        assertThatThrownBy(() -> inventoryService.saveProduct(nuevoProducto(CODIGO, categoria)))
                .isInstanceOf(DuplicateProductCodeException.class)
                .hasMessage("Ya existe un producto con el codigo " + CODIGO);
    }

    @Test
    void dosProductosConElMismoCodigoEnTenantsDistintosFunciona() {
        ProductCategory categoriaUno = categoriaDe(tenantUno);
        Product enTenantUno = inventoryService.saveProduct(nuevoProducto(CODIGO, categoriaUno));

        ProductCategory categoriaDos = categoriaDe(tenantDos);
        Product enTenantDos = inventoryService.saveProduct(nuevoProducto(CODIGO, categoriaDos));

        assertThat(enTenantUno.getCode()).isEqualTo(enTenantDos.getCode());
        assertThat(enTenantUno.getTenantId()).isEqualTo(tenantUno);
        assertThat(enTenantDos.getTenantId()).isEqualTo(tenantDos);
        assertThat(enTenantUno.getId()).isNotEqualTo(enTenantDos.getId());
    }

    /**
     * La validacion del service no reemplaza a la restriccion de base: si alguien escribe
     * por el repositorio directo, la base tiene que seguir frenandolo. Esto prueba que
     * uk_producto_tenant_code existe de verdad en el DDL.
     */
    @Test
    void laRestriccionDeBaseFrenaAunSaltandoseElService() {
        ProductCategory categoria = categoriaDe(tenantUno);
        productRepository.saveAndFlush(nuevoProducto(CODIGO, categoria));

        assertThatThrownBy(() -> productRepository.saveAndFlush(nuevoProducto(CODIGO, categoria)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void guardarDeNuevoElMismoProductoSinCambiarElCodigoNoEsDuplicado() {
        ProductCategory categoria = categoriaDe(tenantUno);
        Product guardado = inventoryService.saveProduct(nuevoProducto(CODIGO, categoria));

        guardado.setDescription("Descripcion editada");

        assertThatCode(() -> inventoryService.saveProduct(guardado)).doesNotThrowAnyException();
    }
}
