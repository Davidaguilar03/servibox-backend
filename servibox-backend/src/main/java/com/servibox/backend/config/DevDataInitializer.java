package com.servibox.backend.config;

import com.servibox.backend.shared.SafeLog;
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
import com.servibox.backend.purchases.entity.Purchase;
import com.servibox.backend.purchases.service.PurchasesService;
import com.servibox.backend.sales.entity.PaymentType;
import com.servibox.backend.sales.entity.Sale;
import com.servibox.backend.sales.service.SalesService;
import com.servibox.backend.shared.Moneda;
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

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Datos minimos para probar en local. Solo perfil dev.
 */
@Component
@Profile("dev & !platform-bootstrap")
@RequiredArgsConstructor
@Slf4j
public class DevDataInitializer implements CommandLineRunner {

    private static final String TENANT_SLUG = "demo";
    private static final String ADMIN_USERNAME = "admin";
    private static final String ADMIN_PASSWORD = "admin123";

    /** Las 3 categorias base, las mismas que el registro publico, con su color de chip. */
    private static final Map<String, String> CATEGORIAS_POR_DEFECTO = Map.of(
            "LLANTAS", "#e74c3c",
            "LUBRICANTES", "#f1c40f",
            "BATERIAS", "#2ecc71"
    );

    /** Las 2 cuentas por defecto de Autollantas, con su tipo. */
    private static final Map<String, AccountType> CUENTAS_POR_DEFECTO = Map.of(
            "Caja General", AccountType.EFECTIVO,
            "Bancolombia", AccountType.BANCO
    );

    private static final com.servibox.backend.purchases.entity.PaymentType COMPRA_CONTADO =
            com.servibox.backend.purchases.entity.PaymentType.CONTADO;
    private static final com.servibox.backend.purchases.entity.PaymentType COMPRA_CREDITO =
            com.servibox.backend.purchases.entity.PaymentType.CREDITO;

    /**
     * Recargo de la siembra sobre el precio sugerido. Con la utilidad bruta sobre costo de lo
     * vendido, vender al sugerido deja un margen bruto cercano al 25 por ciento y utilidad neta
     * negativa todos los meses; con el 12 por ciento el margen queda cerca del 33 por ciento.
     */
    private static final double RECARGO_VENTA = 1.12;

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

    /** Productos de la siembra de demostracion por codigo, para escribir las lineas como "LL-001:4". */
    private final Map<String, Long> productosPorCodigo = new HashMap<>();

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
        // Inventados, con el formato que exige el registro: no son de ninguna empresa.
        tenant.setNit("900000000-0");
        tenant.setPhone("3000000000");
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
        return inventoryService.crearIvaPorDefecto();
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
     * Datos de demostracion sobre la siembra base: un almacen de llantas, lubricantes y
     * baterias de Tunja con tres meses de operacion (el actual y los dos anteriores), para
     * probar el frontend y Reportes sin cargar nada a mano. Pasa por los services, con las
     * mismas reglas que la API: stock, costos, precio sugerido, IVA y saldos salen de ahi.
     * Las fechas salen del Clock de negocio y nunca pasan de hoy (ver fecha).
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

            paso = "cuentas e IVA de la siembra base";
            Account caja = cuentaBase("Caja General");
            Account banco = cuentaBase("Bancolombia");
            TaxType iva = inventoryService.findAllTaxTypes().stream()
                    .filter(t -> Boolean.TRUE.equals(t.getIsVat()))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("No existe el IVA de la siembra base"));

            // Umbrales y margen propios por categoria; el precio sugerido sale del margen.
            paso = "umbrales y margen de las categorias";
            ProductCategory llantas = configurarCategoria("LLANTAS", 8, 3, 0.22, iva);
            ProductCategory lubricantes = configurarCategoria("LUBRICANTES", 12, 4, 0.30, iva);
            ProductCategory baterias = configurarCategoria("BATERIAS", 5, 2, 0.25, iva);

            paso = "productos";
            producto(llantas, "LL-001", "Llanta Michelin Energy XM2+ 175/70 R13 82T", 245000.0, 16);
            producto(llantas, "LL-002", "Llanta Pirelli P400 Evo 175/70 R13 82T", 215000.0, 8);
            producto(llantas, "LL-003", "Llanta Goodyear Assurance 185/65 R14 86H", 255000.0, 12);
            producto(llantas, "LL-004", "Llanta Hankook Kinergy Eco2 185/60 R14 82H", 230000.0, 12);
            producto(llantas, "LL-005", "Llanta Bridgestone Ecopia EP150 185/65 R15 88H", 285000.0, 12);
            producto(llantas, "LL-006", "Llanta Continental PowerContact 2 195/65 R15 91H", 310000.0, 14);
            producto(llantas, "LL-007", "Llanta Michelin Primacy 4 205/55 R16 91V", 455000.0, 8);
            producto(llantas, "LL-008", "Llanta Pirelli Cinturato P7 205/55 R16 91V", 420000.0, 12);
            producto(llantas, "LL-009", "Llanta Hankook Dynapro HT RH12 225/65 R17 102H", 520000.0, 10);
            producto(llantas, "LL-010", "Llanta Bridgestone Dueler H/T 684 265/65 R17 112S", 690000.0, 4);
            producto(lubricantes, "LU-001", "Aceite Terpel Oiltec 20W-50 cuarto", 26000.0, 30);
            producto(lubricantes, "LU-002", "Aceite Mobil Super 1000 20W-50 cuarto", 32000.0, 24);
            producto(lubricantes, "LU-003", "Aceite Castrol GTX 20W-50 cuarto", 34000.0, 24);
            producto(lubricantes, "LU-004", "Aceite Shell Rimula R4 X 15W-40 galon", 98000.0, 20);
            producto(lubricantes, "LU-005", "Aceite Chevron Delo 400 SDE 15W-40 galon", 105000.0, 16);
            producto(lubricantes, "LU-006", "Aceite Mobil Delvac MX 15W-40 galon", 110000.0, 24);
            producto(lubricantes, "LU-007", "Aceite Castrol Magnatec 10W-30 cuarto", 42000.0, 24);
            producto(lubricantes, "LU-008", "Aceite Terpel Oiltec 10W-30 cuarto", 28000.0, 16);
            producto(lubricantes, "LU-009", "Aceite Mobil Super 3000 5W-30 cuarto", 48000.0, 20);
            producto(lubricantes, "LU-010", "Aceite Shell Helix HX8 5W-30 cuarto", 58000.0, 17);
            producto(baterias, "BA-001", "Bateria MAC Silver grupo 42 42ISTD-700", 330000.0, 8);
            producto(baterias, "BA-002", "Bateria MAC Gold grupo NS40 NS40-570", 280000.0, 6);
            producto(baterias, "BA-003", "Bateria Willard Increible grupo 24 24BD-1100", 520000.0, 4);
            producto(baterias, "BA-004", "Bateria Willard Extrema grupo 34 34D-1100", 560000.0, 4);
            producto(baterias, "BA-005", "Bateria Tudor grupo 48 TS48D-760", 470000.0, 7);
            producto(baterias, "BA-006", "Bateria Bosch S4 grupo 24 S4-24-700", 450000.0, 8);
            producto(baterias, "BA-007", "Bateria MAC Diesel grupo 4D 4D-1350", 980000.0, 2);

            paso = "terceros";
            Counterparty jorge = tercero(CounterpartyRole.CLIENTE, DocumentType.CC, "1049612345",
                    "Jorge Enrique Moreno Pinto", "3108745123");
            Counterparty martha = tercero(CounterpartyRole.CLIENTE, DocumentType.CC, "40041287",
                    "Martha Lucia Rodriguez Vargas", "3204518876");
            Counterparty luis = tercero(CounterpartyRole.CLIENTE, DocumentType.CC, "7178934",
                    "Luis Alberto Sanabria Cely", "3125567890");
            Counterparty taxis = tercero(CounterpartyRole.CLIENTE, DocumentType.NIT, "901482376-1",
                    "Taxis Altiplano Boyacense SAS", "6087441520");
            Counterparty chicamocha = tercero(CounterpartyRole.CLIENTE, DocumentType.NIT, "900587214-3",
                    "Transportes Rio Chicamocha SAS", "6087423318");
            Counterparty tenza = tercero(CounterpartyRole.CLIENTE, DocumentType.NIT, "901112903-5",
                    "Flota Valle de Tenza Express SAS", "6087509264");
            Counterparty puente = tercero(CounterpartyRole.CLIENTE, DocumentType.NIT, "901345678-2",
                    "Taller Automotriz El Puente SAS", "3115620437");
            Counterparty hunza = tercero(CounterpartyRole.CLIENTE, DocumentType.NIT, "900763125-0",
                    "Servitaller Hunza SAS", "3138902215");
            Counterparty distrillantas = tercero(CounterpartyRole.PROVEEDOR, DocumentType.NIT, "900345612-3",
                    "Distrillantas del Oriente SAS", "6017458230");
            Counterparty rinesBoyaca = tercero(CounterpartyRole.PROVEEDOR, DocumentType.NIT, "901078341-0",
                    "Llantas y Rines de Boyaca SAS", "6087436671");
            Counterparty lubricantesAndinos = tercero(CounterpartyRole.PROVEEDOR, DocumentType.NIT, "800912457-8",
                    "Lubricantes Andinos del Centro SAS", "6017724415");
            Counterparty lubrisogamoso = tercero(CounterpartyRole.PROVEEDOR, DocumentType.NIT, "901256390-5",
                    "Comercializadora Lubrisogamoso SAS", "6087702948");
            Counterparty energiaTotal = tercero(CounterpartyRole.PROVEEDOR, DocumentType.NIT, "900819736-3",
                    "Energia Total Baterias SAS", "6016953120");

            // Saldo inicial en la cuenta, no como ingreso ocasional: un aporte no es utilidad.
            paso = "saldos iniciales";
            caja = fijarSaldoInicial(caja, 2500000.0);
            banco = fijarSaldoInicial(banco, 15000000.0);
            Account nequi = new Account();
            nequi.setName("Nequi");
            nequi.setType(AccountType.DIGITAL);
            nequi.setInitialBalance(600000.0);
            nequi = treasuryService.saveAccount(nequi);

            // Hace dos meses (2): surtido inicial y arranque de las cuentas por cobrar.
            paso = "operaciones de hace dos meses";
            comprar("DO-4512", distrillantas, fecha(2, 1), banco, "TRANSFERENCIA", "LL-010:8", "LL-007:4");
            gasto(banco, "Arriendo del local", 2600000.0, fecha(2, 1));
            vender(fecha(2, 2), null, caja, "EFECTIVO", "LU-001:4");
            vender(fecha(2, 3), jorge, banco, "TARJETA_DEBITO", "LL-003:4");
            Purchase lacUno = comprar("LAC-10385", lubricantesAndinos, fecha(2, 4), null, null,
                    "LU-001:24", "LU-003:24", "LU-005:12");
            Sale taxisUno = vender(fecha(2, 5), taxis, null, null, "LL-001:4", "LU-008:4");
            vender(fecha(2, 6), luis, caja, "EFECTIVO", "LU-001:4", "BA-002:1");
            Sale chicamochaUno = vender(fecha(2, 8), chicamocha, null, null, "LL-010:4", "LU-005:6");
            gasto(caja, "Insumos de aseo y cafeteria", 85000.0, fecha(2, 8));
            vender(fecha(2, 10), null, nequi, "TRANSFERENCIA", "LU-009:4");
            gasto(banco, "Energia electrica", 412300.0, fecha(2, 10));
            vender(fecha(2, 12), puente, banco, "TRANSFERENCIA", "LU-003:12", "LU-002:12");
            gasto(banco, "Acueducto, alcantarillado y aseo", 126800.0, fecha(2, 12));
            vender(fecha(2, 14), martha, banco, "TARJETA_CREDITO", "LL-007:4");
            gasto(banco, "Internet y telefonia", 119900.0, fecha(2, 14));
            comprar("ETB-3301", energiaTotal, fecha(2, 15), banco, "TRANSFERENCIA", "BA-002:4", "BA-003:2");
            gasto(banco, "Nomina primera quincena", 1850000.0, fecha(2, 15));
            vender(fecha(2, 17), null, caja, "EFECTIVO", "BA-001:1");
            gasto(nequi, "Publicidad en redes sociales", 250000.0, fecha(2, 18));
            Sale tenzaUno = vender(fecha(2, 19), tenza, null, null, "LU-006:8", "BA-007:1");
            abonar(taxisUno, fecha(2, 20), banco, null);
            Sale hunzaUno = vender(fecha(2, 22), hunza, null, null, "LL-005:4", "LU-007:8");
            vender(fecha(2, 25), null, nequi, "TRANSFERENCIA", "LL-002:2");
            pagar(lacUno, fecha(2, 25), banco, 1600000.0);
            vender(fecha(2, 27), jorge, caja, "EFECTIVO", "LU-010:4", "BA-006:1");
            abonar(chicamochaUno, fecha(2, 28), banco, 2600000.0);
            treasuryService.registrarTransferencia(caja, banco, "Consignacion de efectivo", 2000000.0, fecha(2, 28));
            gasto(banco, "Nomina segunda quincena", 1850000.0, fecha(2, 31));

            // Mes anterior (1): el mejor mes, con una venta y una compra anuladas.
            paso = "operaciones del mes anterior";
            Purchase rinesUno = comprar("LRB-2210", rinesBoyaca, fecha(1, 1), null, null,
                    "LL-001:12@252000", "LL-002:8");
            gasto(banco, "Arriendo del local", 2600000.0, fecha(1, 1));
            Sale taxisDos = vender(fecha(1, 2), taxis, null, null, "LL-001:8", "LU-001:8");
            vender(fecha(1, 3), null, caja, "EFECTIVO", "LU-001:4");
            abonar(tenzaUno, fecha(1, 4), banco, null);
            vender(fecha(1, 5), luis, nequi, "TRANSFERENCIA", "LL-002:4");
            pagar(lacUno, fecha(1, 5), banco, null);
            abonar(hunzaUno, fecha(1, 6), caja, 1000000.0);
            Sale chicamochaDos = vender(fecha(1, 7), chicamocha, null, null, "LL-010:4", "BA-004:2");
            comprar("CL-0874", lubrisogamoso, fecha(1, 8), caja, "EFECTIVO", "LU-008:12", "LU-007:12");
            gasto(caja, "Insumos de aseo y cafeteria", 92000.0, fecha(1, 8));
            vender(fecha(1, 9), null, banco, "TARJETA_DEBITO", "BA-005:1");
            abonar(chicamochaUno, fecha(1, 10), banco, null);
            gasto(banco, "Energia electrica", 398700.0, fecha(1, 10));
            vender(fecha(1, 11), martha, caja, "EFECTIVO", "LU-009:4");
            Sale puenteDos = vender(fecha(1, 12), puente, null, null, "LU-003:12", "LU-004:6");
            gasto(banco, "Acueducto, alcantarillado y aseo", 131200.0, fecha(1, 12));
            // Mal digitada: el cliente llevaba 10W-30 de otra marca.
            Sale ventaAnulada = vender(fecha(1, 14), null, caja, "EFECTIVO", "LU-008:4");
            salesService.anularFactura(ventaAnulada.getId());
            gasto(banco, "Internet y telefonia", 119900.0, fecha(1, 14));
            vender(fecha(1, 15), hunza, banco, "TRANSFERENCIA", "LL-006:4", "LU-007:8");
            gasto(banco, "Nomina primera quincena", 1850000.0, fecha(1, 15));
            comprar("DO-4630", distrillantas, fecha(1, 16), banco, "CHEQUE", "LL-006:4", "LL-009:4");
            vender(fecha(1, 17), jorge, banco, "TARJETA_CREDITO", "LL-008:2");
            // Factura del proveedor registrada dos veces: se anula la repetida.
            Purchase compraAnulada = comprar("ETB-3317", energiaTotal, fecha(1, 18), banco, "TRANSFERENCIA",
                    "BA-005:2");
            purchasesService.anularFactura(compraAnulada.getId());
            Sale tenzaDos = vender(fecha(1, 19), tenza, null, null, "LU-006:8", "LL-009:4");
            abonar(hunzaUno, fecha(1, 20), nequi, null);
            gasto(caja, "Volantes y pendon publicitario", 180000.0, fecha(1, 20));
            vender(fecha(1, 21), null, nequi, "TRANSFERENCIA", "LU-002:4", "BA-002:1");
            treasuryService.registrarIngresoOcasional(banco, "Reintegro de Distrillantas del Oriente por garantia",
                    310000.0, fecha(1, 22), null);
            vender(fecha(1, 23), null, caja, "EFECTIVO", "LL-004:2");
            vender(fecha(1, 25), luis, caja, "EFECTIVO", "LU-001:4");
            abonar(chicamochaDos, fecha(1, 26), banco, 3000000.0);
            abonar(taxisDos, fecha(1, 27), banco, null);
            vender(fecha(1, 28), martha, banco, "TARJETA_DEBITO", "BA-001:1", "LU-010:4");
            pagar(rinesUno, fecha(1, 28), banco, 3000000.0);
            treasuryService.registrarTransferencia(caja, banco, "Consignacion de efectivo", 2500000.0, fecha(1, 28));
            gasto(banco, "Nomina segunda quincena", 1850000.0, fecha(1, 31));

            // Mes actual (0): todo hasta hoy, sin segunda quincena todavia.
            paso = "operaciones del mes actual";
            vender(fecha(0, 1), null, caja, "EFECTIVO", "LU-001:4");
            gasto(banco, "Arriendo del local", 2600000.0, fecha(0, 1));
            vender(fecha(0, 2), taxis, null, null, "LL-001:4", "BA-002:2");
            Purchase lacDos = comprar("LAC-10592", lubricantesAndinos, fecha(0, 3), null, null,
                    "LU-002:24", "LU-009:12", "LU-006:12");
            vender(fecha(0, 3), jorge, banco, "TARJETA_CREDITO", "LL-007:2");
            abonar(puenteDos, fecha(0, 4), banco, null);
            Sale chicamochaTres = vender(fecha(0, 5), chicamocha, null, null, "LU-005:6", "BA-007:1");
            vender(fecha(0, 6), null, nequi, "TRANSFERENCIA", "LU-009:4");
            gasto(caja, "Insumos de aseo y cafeteria", 88000.0, fecha(0, 6));
            comprar("DO-4788", distrillantas, fecha(0, 7), banco, "TRANSFERENCIA", "LL-005:8", "LL-003:4");
            vender(fecha(0, 8), puente, banco, "TRANSFERENCIA", "LU-002:12", "LU-003:6");
            abonar(tenzaDos, fecha(0, 9), banco, 1500000.0);
            // El cliente devolvio la bateria: no era la de su carro.
            Sale devuelta = vender(fecha(0, 10), null, caja, "EFECTIVO", "BA-006:1");
            salesService.anularFactura(devuelta.getId());
            gasto(banco, "Energia electrica", 405900.0, fecha(0, 10));
            vender(fecha(0, 11), hunza, null, null, "LL-005:4", "LU-007:4");
            pagar(lacDos, fecha(0, 12), banco, 1500000.0);
            gasto(banco, "Acueducto, alcantarillado y aseo", 128400.0, fecha(0, 12));
            vender(fecha(0, 13), martha, caja, "EFECTIVO", "LU-008:4");
            abonar(chicamochaTres, fecha(0, 14), caja, 500000.0);
            gasto(banco, "Internet y telefonia", 119900.0, fecha(0, 14));
            vender(fecha(0, 15), tenza, banco, "TRANSFERENCIA", "LL-010:2", "LU-006:6");
            gasto(banco, "Nomina primera quincena", 1850000.0, fecha(0, 15));
            vender(fecha(0, 17), null, caja, "EFECTIVO", "LL-003:2");
            vender(fecha(0, 19), luis, nequi, "TRANSFERENCIA", "LU-001:4", "BA-004:1");
            // Ultima semana: cambio de llantas de flota y ventas de mostrador.
            vender(fecha(0, 20), taxis, null, null, "LL-006:4");
            vender(fecha(0, 21), null, caja, "EFECTIVO", "BA-006:1");
            vender(fecha(0, 22), chicamocha, null, null, "LL-005:3", "LU-001:8");
            vender(fecha(0, 23), jorge, banco, "TARJETA_DEBITO", "LL-008:1");
            vender(fecha(0, 24), hunza, banco, "TRANSFERENCIA", "LL-009:1", "LL-001:3");

            log.info("Perfil dev: datos de demostracion en [{}]: 3 categorias, {} productos, {} terceros, "
                            + "{} ventas, {} compras y {} movimientos en tres meses", TENANT_SLUG,
                    inventoryService.findAllProducts().size(), counterpartyService.findAll().size(),
                    salesService.findAllSales().size(), purchasesService.findAllPurchases().size(),
                    treasuryService.buscarMovimientos(null, null, null, null).size());
        } catch (RuntimeException ex) {
            log.warn("Perfil dev: fallo la siembra de demostracion en el paso [{}]: {}. El arranque sigue "
                    + "con los datos creados hasta ese punto.", paso, SafeLog.describe(ex));
        } finally {
            TenantContext.clear();
        }
    }

    /**
     * Dia del mes de hace mesesAtras meses (0 es el actual). Un dia que el mes no tiene pasa
     * al ultimo, y una fecha posterior a hoy pasa a hoy: el mes actual siempre tiene datos y
     * nada queda en el futuro. Como las fechas van en orden, un abono nunca queda antes de su
     * factura.
     */
    private LocalDate fecha(int mesesAtras, int dia) {
        YearMonth mes = YearMonth.now(clock).minusMonths(mesesAtras);
        LocalDate fecha = mes.atDay(Math.min(dia, mes.lengthOfMonth()));
        LocalDate hoy = LocalDate.now(clock);
        return fecha.isAfter(hoy) ? hoy : fecha;
    }

    private ProductCategory configurarCategoria(String nombre, int amarillo, int rojo, double margen, TaxType iva) {
        ProductCategory categoria = inventoryService.findAllCategories().stream()
                .filter(c -> nombre.equals(c.getName()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No existe la categoria base " + nombre));
        return inventoryService.actualizarCategoria(categoria.getId(), nombre, categoria.getColor(), amarillo, rojo,
                margen, List.of(iva.getId()));
    }

    /**
     * Venta al precio sugerido del momento con RECARGO_VENTA, redondeado al peso entero. El costo de
     * cada linea lo congela SalesService con el costo vigente: por eso las operaciones van en
     * orden de fecha. Sin cuenta es a credito, con
     * vencimiento a 30 dias; sin cliente, al Consumidor final.
     */
    private Sale vender(LocalDate fecha, Counterparty cliente, Account cuenta, String medio, String... lineas) {
        List<SalesService.LineaFactura> detalle = Arrays.stream(lineas).map(linea -> {
            String[] partes = linea.split(":");
            Product producto = inventoryService.findProductById(productosPorCodigo.get(partes[0])).orElseThrow();
            double precio = Moneda.aPesosEnteros(
                    BigDecimal.valueOf(producto.getSuggestedPrice()).multiply(BigDecimal.valueOf(RECARGO_VENTA)));
            return new SalesService.LineaFactura(producto.getId(), Integer.parseInt(partes[1]), precio);
        }).toList();
        if (cuenta == null) {
            return salesService.crearFactura(null, cliente, fecha, fecha.plusDays(30), PaymentType.CREDITO,
                    null, null, detalle);
        }
        return salesService.crearFactura(null, cliente, fecha, null, PaymentType.CONTADO, cuenta, medio, detalle);
    }

    /**
     * Compra al costo actual del producto, o al de "@precio" si sube. Sin cuenta es a credito,
     * con vencimiento a 30 dias.
     */
    private Purchase comprar(String numero, Counterparty proveedor, LocalDate fecha, Account cuenta, String medio,
                             String... lineas) {
        List<PurchasesService.LineaCompra> detalle = Arrays.stream(lineas).map(linea -> {
            String[] partes = linea.split("[:@]");
            Product producto = inventoryService.findProductById(productosPorCodigo.get(partes[0])).orElseThrow();
            double precio = partes.length > 2 ? Double.parseDouble(partes[2]) : producto.getPurchaseCost();
            return new PurchasesService.LineaCompra(producto.getId(), Integer.parseInt(partes[1]), precio);
        }).toList();
        if (cuenta == null) {
            return purchasesService.crearFactura(numero, proveedor, fecha, fecha.plusDays(30), COMPRA_CREDITO,
                    null, null, detalle);
        }
        return purchasesService.crearFactura(numero, proveedor, fecha, null, COMPRA_CONTADO, cuenta, medio, detalle);
    }

    /** Abono a una venta a credito; sin monto, el saldo pendiente completo. */
    private void abonar(Sale venta, LocalDate fecha, Account cuenta, Double monto) {
        Sale actual = salesService.findSaleById(venta.getId()).orElseThrow();
        salesService.registrarAbono(actual.getId(), cuenta.getId(),
                monto != null ? monto : salesService.saldoPendiente(actual), fecha);
    }

    /** Pago a una compra a credito; sin monto, el saldo pendiente completo. */
    private void pagar(Purchase compra, LocalDate fecha, Account cuenta, Double monto) {
        Purchase actual = purchasesService.findPurchaseById(compra.getId()).orElseThrow();
        purchasesService.registrarPago(actual.getId(), cuenta.getId(),
                monto != null ? monto : purchasesService.saldoPendiente(actual), fecha);
    }

    private void gasto(Account cuenta, String concepto, double monto, LocalDate fecha) {
        treasuryService.registrarGastoOperativo(cuenta, concepto, monto, fecha, null);
    }

    private Account cuentaBase(String nombre) {
        Long id = treasuryService.findAllAccounts().stream()
                .filter(c -> nombre.equals(c.getName()))
                .map(Account::getId)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No existe la cuenta base " + nombre));
        return treasuryService.findAccountById(id).orElseThrow();
    }

    /**
     * Fija el saldo inicial de una cuenta de la siembra base. currentBalance se mueve en la
     * misma diferencia, asi se conserva saldo_actual = balance_inicial + movimientos activos.
     */
    private Account fijarSaldoInicial(Account cuenta, double saldoInicial) {
        cuenta.setCurrentBalance(cuenta.getCurrentBalance() + saldoInicial - cuenta.getInitialBalance());
        cuenta.setInitialBalance(saldoInicial);
        return treasuryService.saveAccount(cuenta);
    }

    private void producto(ProductCategory categoria, String codigo, String descripcion,
                          double costo, int cantidad) {
        Product producto = new Product();
        producto.setCode(codigo);
        producto.setDescription(descripcion);
        producto.setPurchaseCost(costo);
        producto.setQuantity(cantidad);
        producto.setCategory(categoria);
        productosPorCodigo.put(codigo, inventoryService.saveProduct(producto).getId());
    }

    private Counterparty tercero(CounterpartyRole rol, DocumentType tipo, String documento, String nombre,
                                 String celular) {
        Counterparty tercero = new Counterparty();
        tercero.setRole(rol);
        tercero.setDocumentType(tipo);
        tercero.setDocumentNumber(documento);
        tercero.setName(nombre);
        tercero.setPhone(celular);
        return counterpartyService.crear(tercero);
    }
}
