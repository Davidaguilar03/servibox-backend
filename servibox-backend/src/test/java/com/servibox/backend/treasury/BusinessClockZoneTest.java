package com.servibox.backend.treasury;

import com.servibox.backend.counterparties.entity.Counterparty;
import com.servibox.backend.inventory.entity.Product;
import com.servibox.backend.purchases.entity.Purchase;
import com.servibox.backend.purchases.service.PurchasesService;
import com.servibox.backend.sales.SalesFixture;
import com.servibox.backend.sales.entity.PaymentType;
import com.servibox.backend.sales.entity.Sale;
import com.servibox.backend.sales.service.SalesService;
import com.servibox.backend.tenant.TenantContext;
import com.servibox.backend.treasury.entity.Account;
import com.servibox.backend.treasury.entity.Movement;
import com.servibox.backend.treasury.service.TreasuryService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * El "hoy" de negocio sale de la zona configurada, no de la del servidor. Con el instante
 * 2026-10-19T03:00:00Z, que en Bogota todavia es el 18 de octubre a las 22:00, todas las
 * fechas por defecto valen 2026-10-18. El Clock fijo vive solo en el contexto de esta clase.
 */
@SpringBootTest
@ActiveProfiles("test")
class BusinessClockZoneTest {

    private static final Instant INSTANTE = Instant.parse("2026-10-19T03:00:00Z");
    private static final LocalDate HOY_EN_BOGOTA = LocalDate.of(2026, 10, 18);

    @TestConfiguration
    static class RelojFijo {

        /** Mismo instante para todos, con la zona de la propiedad servibox.business.time-zone. */
        @Bean
        @Primary
        Clock relojFijo(@Value("${servibox.business.time-zone}") ZoneId zona) {
            return Clock.fixed(INSTANTE, zona);
        }
    }

    @Autowired
    private SalesFixture fixture;

    @Autowired
    private SalesService salesService;

    @Autowired
    private PurchasesService purchasesService;

    @Autowired
    private TreasuryService treasuryService;

    @Autowired
    @Qualifier("businessClock")
    private Clock relojDeProduccion;

    private Product llanta;
    private Account caja;
    private Counterparty proveedor;

    @BeforeEach
    void seed() {
        fixture.usarTenant(fixture.crearTenant());
        llanta = fixture.crearProducto(fixture.crearCategoriaConIva(), "LLA-001", 100000.0, 10);
        caja = fixture.crearCuenta("Caja General");
        proveedor = fixture.crearProveedor("Llantas del Norte", "800333444");
    }

    @AfterEach
    void cleanUp() {
        TenantContext.clear();
    }

    @Test
    void laZonaDeNegocioPorDefectoEsBogota() {
        assertThat(relojDeProduccion.getZone()).isEqualTo(ZoneId.of("America/Bogota"));
        // Sanidad del escenario: en UTC ese instante ya es el dia siguiente.
        assertThat(LocalDate.ofInstant(INSTANTE, ZoneId.of("UTC"))).isEqualTo(HOY_EN_BOGOTA.plusDays(1));
    }

    @Test
    void lasFechasPorDefectoUsanElDiaDeBogotaYNoElDeUtc() {
        Movement ingreso = treasuryService.registrarIngresoOcasional(caja, "Fondo", 500000.0, null, null);
        assertThat(ingreso.getDate()).isEqualTo(HOY_EN_BOGOTA);

        Sale venta = salesService.crearFactura(null, null, null, HOY_EN_BOGOTA, PaymentType.CREDITO, null, null,
                List.of(new SalesService.LineaFactura(llanta.getId(), 1, 100000.0)));
        assertThat(venta.getInvoiceDate()).isEqualTo(HOY_EN_BOGOTA);

        Purchase compra = purchasesService.crearFactura("FAC-00001", proveedor, null, HOY_EN_BOGOTA,
                com.servibox.backend.purchases.entity.PaymentType.CREDITO, null, null,
                List.of(new PurchasesService.LineaCompra(llanta.getId(), 1, 100000.0)));
        assertThat(compra.getInvoiceDate()).isEqualTo(HOY_EN_BOGOTA);

        Movement abono = salesService.registrarAbono(venta.getId(), caja.getId(), 10000.0, null);
        assertThat(abono.getDate()).isEqualTo(HOY_EN_BOGOTA);

        Movement pago = purchasesService.registrarPago(compra.getId(), caja.getId(), 10000.0, null);
        assertThat(pago.getDate()).isEqualTo(HOY_EN_BOGOTA);

        // El 19 todavia es futuro en Bogota.
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        salesService.registrarAbono(venta.getId(), caja.getId(), 1000.0, HOY_EN_BOGOTA.plusDays(1)))
                .hasMessage("La fecha del abono (2026-10-19) no puede ser posterior a hoy (2026-10-18)");
    }
}
