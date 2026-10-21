package com.servibox.backend.config;

import com.servibox.backend.auth.entity.Role;
import com.servibox.backend.auth.entity.User;
import com.servibox.backend.auth.repository.UserRepository;
import com.servibox.backend.counterparties.entity.Counterparty;
import com.servibox.backend.counterparties.entity.CounterpartyRole;
import com.servibox.backend.counterparties.entity.DocumentType;
import com.servibox.backend.counterparties.service.CounterpartyService;
import com.servibox.backend.inventory.entity.Product;
import com.servibox.backend.inventory.entity.ProductCategory;
import com.servibox.backend.inventory.entity.TaxType;
import com.servibox.backend.inventory.service.InventoryService;
import com.servibox.backend.purchases.service.PurchasesService;
import com.servibox.backend.sales.entity.PaymentType;
import com.servibox.backend.sales.entity.Sale;
import com.servibox.backend.sales.service.SalesService;
import com.servibox.backend.treasury.entity.Account;
import com.servibox.backend.treasury.entity.AccountType;
import com.servibox.backend.treasury.service.TreasuryService;
import com.servibox.backend.tenant.Tenant;
import com.servibox.backend.tenant.TenantContext;
import com.servibox.backend.tenant.TenantRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Datos minimos para probar en local. Solo perfil dev.
 */
@Component
@Profile("dev")
@RequiredArgsConstructor
@Slf4j
public class DevDataInitializer implements CommandLineRunner {

    private static final String TENANT_SLUG = "demo";
    private static final String ADMIN_USERNAME = "admin";
    private static final String ADMIN_PASSWORD = "admin123";

    /** Las 8 categorias por defecto de Autollantas, con su color de chip. */
    private static final Map<String, String> CATEGORIAS_POR_DEFECTO = Map.of(
            "LLANTAS", "#e74c3c",
            "FILTROS DE AIRE", "#e67e22",
            "ACEITES", "#f1c40f",
            "BATERIAS", "#2ecc71",
            "FILTROS DE ACEITE", "#1abc9c",
            "PLUMILLAS", "#3498db",
            "ELECTRICOS", "#9b59b6",
            "OTROS", "#95a5a6"
    );

    /** Las 2 cuentas por defecto de Autollantas, con su tipo. */
    private static final Map<String, AccountType> CUENTAS_POR_DEFECTO = Map.of(
            "Caja General", AccountType.EFECTIVO,
            "Bancolombia", AccountType.BANCO
    );

    private final TenantRepository tenantRepository;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final InventoryService inventoryService;
    private final TreasuryService treasuryService;
    private final SalesService salesService;
    private final PurchasesService purchasesService;
    private final CounterpartyService counterpartyService;
    /** El "hoy" de negocio, ver BusinessClockConfiguration. */
    private final Clock clock;

    /** servibox.dev.demo-data, en application-dev.properties. Ver sembrarDemostracion. */
    @Value("${servibox.dev.demo-data}")
    private boolean demoData;

    @Override
    public void run(String... args) {
        if (tenantRepository.count() == 0) {
            sembrarBase();
        }
        if (demoData) {
            sembrarDemostracion();
        }
    }

    private void sembrarBase() {
        Tenant tenant = new Tenant();
        tenant.setName("ServiBox Demo");
        tenant.setSlug(TENANT_SLUG);
        tenant.setActive(Boolean.TRUE);
        Tenant creado = tenantRepository.save(tenant);

        // TenantAwareEntity asigna tenantId en @PrePersist desde TenantContext, hay que
        // poblarlo antes de guardar cualquier entidad de negocio.
        TenantContext.setTenantId(creado.getId());
        try {
            sembrarAdmin();
            TaxType iva = sembrarIva();
            sembrarCategorias(iva);
            sembrarCuentas();
        } finally {
            TenantContext.clear();
        }

        log.info("Perfil dev: tenant [{}], usuario [{}], IVA, {} categorias y {} cuentas creados",
                TENANT_SLUG, ADMIN_USERNAME, CATEGORIAS_POR_DEFECTO.size(), CUENTAS_POR_DEFECTO.size());
    }

    private void sembrarAdmin() {
        User admin = new User();
        admin.setUsername(ADMIN_USERNAME);
        admin.setPasswordHash(passwordEncoder.encode(ADMIN_PASSWORD));
        admin.setEmail("admin@demo.local");
        admin.setActive(true);
        admin.setRole(Role.ADMIN);
        userRepository.save(admin);
    }

    private TaxType sembrarIva() {
        TaxType iva = new TaxType();
        iva.setName("IVA");
        iva.setRate(0.19);
        iva.setDescription("Impuesto al Valor Agregado");
        iva.setAppliesToTransaction(Boolean.TRUE);
        iva.setIsVat(Boolean.TRUE);
        return inventoryService.saveTaxType(iva);
    }

    private void sembrarCuentas() {
        CUENTAS_POR_DEFECTO.forEach((nombre, tipo) -> {
            Account cuenta = new Account();
            cuenta.setName(nombre);
            cuenta.setType(tipo);
            cuenta.setInitialBalance(0.0);
            treasuryService.saveAccount(cuenta);
        });
    }

    private void sembrarCategorias(TaxType iva) {
        CATEGORIAS_POR_DEFECTO.forEach((nombre, color) -> {
            ProductCategory categoria = new ProductCategory();
            categoria.setName(nombre);
            categoria.setColor(color);
            // Sin umbrales de alerta ni margen: los configura cada taller. Son NOT NULL.
            categoria.setYellowStockMin(0);
            categoria.setRedStockMin(0);
            categoria.setTargetMargin(0.0);
            categoria.setTaxTypes(Set.of(iva));
            inventoryService.saveCategory(categoria);
        });
    }

    /**
     * Datos de demostracion sobre la siembra base, para probar el frontend y Reportes sin
     * cargar nada a mano. Pasa por los services, con las mismas reglas que la API. Las
     * fechas salen del Clock de negocio: operaciones en el mes anterior y en el actual.
     *
     * Idempotente: si el tenant demo ya tiene productos no hace nada. Si algo falla, deja
     * un aviso en el log y el arranque sigue. Cifras esperadas en 02-CONVENTIONS.md.
     */
    private void sembrarDemostracion() {
        Optional<Tenant> demo = tenantRepository.findBySlug(TENANT_SLUG);
        if (demo.isEmpty()) {
            log.warn("Perfil dev: no existe el tenant [{}], no se siembran datos de demostracion", TENANT_SLUG);
            return;
        }
        TenantContext.setTenantId(demo.get().getId());
        String paso = "comprobar productos existentes";
        try {
            if (!inventoryService.findAllProducts().isEmpty()) {
                log.info("Perfil dev: el tenant [{}] ya tiene productos, no se siembran datos de demostracion",
                        TENANT_SLUG);
                return;
            }

            LocalDate inicioMes = LocalDate.now(clock).withDayOfMonth(1);
            LocalDate hoy = LocalDate.now(clock);
            LocalDate mesAnterior = inicioMes.minusMonths(1);

            paso = "cuentas e IVA de la siembra base";
            Account caja = cuentaBase("Caja General");
            Account banco = cuentaBase("Bancolombia");
            TaxType iva = inventoryService.findAllTaxTypes().stream()
                    .filter(t -> Boolean.TRUE.equals(t.getIsVat()))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("No existe el IVA de la siembra base"));

            paso = "categoria y productos";
            ProductCategory categoria = new ProductCategory();
            categoria.setName("Repuestos demo");
            categoria.setColor("#ff3b30");
            categoria.setYellowStockMin(10);
            categoria.setRedStockMin(3);
            categoria.setTargetMargin(0.30);
            categoria.setTaxTypes(Set.of(iva));
            categoria = inventoryService.saveCategory(categoria);
            Product aceite = producto(categoria, "DEMO-001", "Aceite 20W50 galon", 30000.0, 50);
            Product filtro = producto(categoria, "DEMO-002", "Filtro de aceite", 15000.0, 12);
            Product bateria = producto(categoria, "DEMO-003", "Bateria 12V", 250000.0, 4);

            paso = "terceros";
            Counterparty clienteUno = tercero(CounterpartyRole.CLIENTE, DocumentType.CC, "1010101010", "Laura Gomez");
            Counterparty clienteDos = tercero(CounterpartyRole.CLIENTE, DocumentType.NIT, "900111222", "Transportes Andinos SAS");
            Counterparty proveedor = tercero(CounterpartyRole.PROVEEDOR, DocumentType.NIT, "800333444", "Lubricantes del Valle SAS");

            paso = "ingresos ocasionales";
            treasuryService.registrarIngresoOcasional(caja, "Aporte inicial demo", 2000000.0, mesAnterior, null);
            treasuryService.registrarIngresoOcasional(banco, "Aporte inicial demo", 2000000.0, mesAnterior, null);

            paso = "compra de contado";
            purchasesService.crearFactura("PROV-1001", proveedor, mesAnterior.plusDays(2), null,
                    com.servibox.backend.purchases.entity.PaymentType.CONTADO, caja, "Efectivo",
                    List.of(new PurchasesService.LineaCompra(aceite.getId(), 10, 30000.0)));

            paso = "ventas del mes anterior";
            salesService.crearFactura(null, clienteUno, mesAnterior.plusDays(4), null, PaymentType.CONTADO, caja,
                    "Efectivo", List.of(new SalesService.LineaFactura(aceite.getId(), 5, 45000.0),
                            new SalesService.LineaFactura(bateria.getId(), 1, 360000.0)));
            Sale credito = salesService.crearFactura(null, clienteDos, mesAnterior.plusDays(19),
                    mesAnterior.plusDays(49), PaymentType.CREDITO, null, null,
                    List.of(new SalesService.LineaFactura(filtro.getId(), 2, 22000.0)));

            paso = "transferencia";
            treasuryService.registrarTransferencia(caja, banco, null, 500000.0, mesAnterior.plusDays(24));

            paso = "abono, gasto y ventas del mes actual";
            salesService.registrarAbono(credito.getId(), banco.getId(), 20000.0, inicioMes);
            treasuryService.registrarGastoOperativo(banco, "Arriendo del local", 450000.0, inicioMes, null);
            salesService.crearFactura(null, clienteDos, inicioMes, null, PaymentType.CONTADO, banco,
                    "Transferencia", List.of(new SalesService.LineaFactura(aceite.getId(), 3, 45000.0)));
            Sale anulada = salesService.crearFactura(null, clienteUno, hoy, null, PaymentType.CONTADO, caja,
                    "Efectivo", List.of(new SalesService.LineaFactura(bateria.getId(), 1, 360000.0)));
            salesService.crearFactura(null, clienteUno, hoy, null, PaymentType.CONTADO, caja,
                    "Efectivo", List.of(new SalesService.LineaFactura(aceite.getId(), 2, 45000.0)));

            paso = "anulacion";
            salesService.anularFactura(anulada.getId());

            log.info("Perfil dev: datos de demostracion en [{}]: 1 categoria, 3 productos, 3 terceros, "
                    + "5 ventas (1 a credito con abono, 1 anulada), 1 compra, 5 movimientos manuales "
                    + "(2 ingresos ocasionales, 1 gasto, las 2 patas de 1 transferencia) y 1 abono", TENANT_SLUG);
        } catch (RuntimeException ex) {
            log.warn("Perfil dev: fallo la siembra de demostracion en el paso [{}]: {}. El arranque sigue "
                    + "con los datos creados hasta ese punto.", paso, ex.getMessage());
        } finally {
            TenantContext.clear();
        }
    }

    private Account cuentaBase(String nombre) {
        Long id = treasuryService.findAllAccounts().stream()
                .filter(c -> nombre.equals(c.getName()))
                .map(Account::getId)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No existe la cuenta base " + nombre));
        return treasuryService.findAccountById(id).orElseThrow();
    }

    private Product producto(ProductCategory categoria, String codigo, String descripcion,
                             double costo, int cantidad) {
        Product producto = new Product();
        producto.setCode(codigo);
        producto.setDescription(descripcion);
        producto.setPurchaseCost(costo);
        producto.setQuantity(cantidad);
        producto.setCategory(categoria);
        return inventoryService.saveProduct(producto);
    }

    private Counterparty tercero(CounterpartyRole rol, DocumentType tipo, String documento, String nombre) {
        Counterparty tercero = new Counterparty();
        tercero.setRole(rol);
        tercero.setDocumentType(tipo);
        tercero.setDocumentNumber(documento);
        tercero.setName(nombre);
        return counterpartyService.crear(tercero);
    }
}
