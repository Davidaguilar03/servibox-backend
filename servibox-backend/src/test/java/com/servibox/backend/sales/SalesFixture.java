package com.servibox.backend.sales;

import com.servibox.backend.inventory.entity.Product;
import com.servibox.backend.inventory.entity.ProductCategory;
import com.servibox.backend.inventory.entity.TaxType;
import com.servibox.backend.inventory.service.InventoryService;
import com.servibox.backend.counterparties.entity.Counterparty;
import com.servibox.backend.counterparties.entity.CounterpartyRole;
import com.servibox.backend.counterparties.entity.DocumentType;
import com.servibox.backend.counterparties.service.CounterpartyService;
import com.servibox.backend.tenant.Tenant;
import com.servibox.backend.tenant.TenantContext;
import com.servibox.backend.tenant.TenantRepository;
import com.servibox.backend.treasury.entity.Account;
import com.servibox.backend.treasury.entity.AccountType;
import com.servibox.backend.treasury.entity.MovementType;
import com.servibox.backend.treasury.service.TreasuryService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Semilla comun de los tests de Sales: un tenant con su IVA del 19 por ciento, una
 * categoria, productos con stock y una cuenta de caja. Clientes y proveedores son terceros.
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
    private CounterpartyService counterpartyService;

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

    /** Tercero CLIENTE por el camino de obtenerOCrear, el mismo que usa una venta. */
    public Counterparty crearCliente(String nombre, String documento) {
        return counterpartyService.obtenerOCrear(CounterpartyRole.CLIENTE, DocumentType.CC, documento,
                nombre, nombre.toLowerCase().replace(' ', '.') + "@correo.local", "3000000000");
    }

    /** Tercero PROVEEDOR por el camino de obtenerOCrear, el mismo que usa una compra. */
    public Counterparty crearProveedor(String nombre, String nit) {
        return counterpartyService.obtenerOCrear(CounterpartyRole.PROVEEDOR, DocumentType.NIT, nit,
                nombre, null, null);
    }

    /**
     * Invariante de tesoreria: en cada cuenta del tenant activo, saldo_actual es
     * balance_inicial mas la suma de sus movimientos activos (los ingresos suman, los
     * egresos restan). Los de una factura ANULADA no cuentan. Llamar despues de cada
     * operacion que mueva dinero.
     */
    public void verificarInvariante() {
        for (Account cuenta : treasuryService.findAllAccounts()) {
            double neto = treasuryService.findMovementsByAccountId(cuenta.getId()).stream()
                    .mapToDouble(m -> m.getType() == MovementType.INGRESO ? m.getAmount() : -m.getAmount())
                    .sum();
            assertThat(cuenta.getCurrentBalance())
                    .as("saldo_actual de %s = balance_inicial + movimientos activos", cuenta.getName())
                    .isCloseTo(cuenta.getInitialBalance() + neto, within(0.001));
        }
    }

    public void usarTenant(Long tenantId) {
        TenantContext.setTenantId(tenantId);
    }
}
