package com.servibox.backend.sales;

import com.servibox.backend.inventory.entity.Product;
import com.servibox.backend.inventory.entity.ProductCategory;
import com.servibox.backend.inventory.entity.TaxType;
import com.servibox.backend.inventory.service.InventoryService;
import com.servibox.backend.sales.entity.Customer;
import com.servibox.backend.sales.service.SalesService;
import com.servibox.backend.tenant.Tenant;
import com.servibox.backend.tenant.TenantContext;
import com.servibox.backend.tenant.TenantRepository;
import com.servibox.backend.treasury.entity.Account;
import com.servibox.backend.treasury.entity.AccountType;
import com.servibox.backend.treasury.service.TreasuryService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Semilla comun de los tests de Sales: un tenant con su IVA del 19 por ciento, una
 * categoria, productos con stock y una cuenta de caja.
 */
@Component
public class SalesFixture {

    public static final double IVA = 0.19;

    @Autowired
    private TenantRepository tenantRepository;

    @Autowired
    private InventoryService inventoryService;

    @Autowired
    private TreasuryService treasuryService;

    @Autowired
    private SalesService salesService;

    public Long crearTenant() {
        Tenant tenant = new Tenant();
        String slug = "taller-" + System.nanoTime();
        tenant.setName(slug);
        tenant.setSlug(slug);
        tenant.setActive(Boolean.TRUE);
        return tenantRepository.save(tenant).getId();
    }

    /** Categoria con IVA del 19 por ciento, para que getIvaRateForProduct devuelva 0.19. */
    public ProductCategory crearCategoriaConIva() {
        TaxType iva = new TaxType();
        iva.setName("IVA");
        iva.setRate(IVA);
        iva.setIsVat(Boolean.TRUE);
        iva.setAppliesToTransaction(Boolean.TRUE);
        TaxType guardado = inventoryService.saveTaxType(iva);

        ProductCategory categoria = new ProductCategory();
        categoria.setName("LLANTAS");
        categoria.setColor("#e74c3c");
        categoria.setTargetMargin(0.0);
        categoria.setTaxTypes(List.of(guardado));
        return inventoryService.saveCategory(categoria);
    }

    public Product crearProducto(ProductCategory categoria, String codigo, double costo, int cantidad) {
        Product producto = new Product();
        producto.setCode(codigo);
        producto.setDescription("Producto " + codigo);
        producto.setPurchaseCost(costo);
        producto.setQuantity(cantidad);
        producto.setCategory(categoria);
        return inventoryService.saveProduct(producto);
    }

    public Account crearCuenta(String nombre) {
        Account cuenta = new Account();
        cuenta.setName(nombre);
        cuenta.setType(AccountType.CASH);
        cuenta.setInitialBalance(0.0);
        return treasuryService.saveAccount(cuenta);
    }

    public Customer crearCliente(String nombre, String documento) {
        Customer cliente = new Customer();
        cliente.setName(nombre);
        cliente.setDocument(documento);
        cliente.setEmail(nombre.toLowerCase() + "@correo.local");
        cliente.setPhone("3000000000");
        return salesService.saveCustomer(cliente);
    }

    public void usarTenant(Long tenantId) {
        TenantContext.setTenantId(tenantId);
    }
}
