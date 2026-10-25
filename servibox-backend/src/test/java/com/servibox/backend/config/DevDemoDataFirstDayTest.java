package com.servibox.backend.config;

import com.servibox.backend.purchases.service.PurchasesService;
import com.servibox.backend.reporting.dto.KpisResponse;
import com.servibox.backend.reporting.service.ReportingService;
import com.servibox.backend.sales.entity.Sale;
import com.servibox.backend.sales.service.SalesService;
import com.servibox.backend.tenant.TenantContext;
import com.servibox.backend.tenant.TenantRepository;
import com.servibox.backend.treasury.entity.Movement;
import com.servibox.backend.treasury.service.TreasuryService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * La siembra de demostracion con el Clock en el primer dia del mes (1 de marzo de 2026, con
 * febrero de 28 dias): las fechas que pasarian de hoy quedan en hoy, la siembra termina
 * completa y las cifras por mes son las mismas que en DevDemoDataTest.
 */
@SpringBootTest
@ActiveProfiles({"test", "dev"})
class DevDemoDataFirstDayTest {

    private static final LocalDate HOY = LocalDate.of(2026, 3, 1);

    @TestConfiguration
    static class RelojFijo {

        @Bean
        @Primary
        Clock relojFijo(@Value("${servibox.business.time-zone}") ZoneId zona) {
            return Clock.fixed(Instant.parse("2026-03-01T17:00:00Z"), zona);
        }
    }

    @Autowired
    private TenantRepository tenantRepository;

    @Autowired
    private SalesService salesService;

    @Autowired
    private PurchasesService purchasesService;

    @Autowired
    private TreasuryService treasuryService;

    @Autowired
    private ReportingService reportingService;

    @AfterEach
    void cleanUp() {
        TenantContext.clear();
    }

    @Test
    void elPrimerDiaDelMesLaSiembraTerminaSinFechasFuturasYConDatosHoy() {
        TenantContext.setTenantId(tenantRepository.findBySlug("demo").orElseThrow().getId());

        assertThat(salesService.findAllSales()).hasSize(40)
                .extracting(Sale::getInvoiceDate).allMatch(f -> !f.isAfter(HOY)).contains(HOY);
        assertThat(purchasesService.findAllPurchases()).hasSize(9);
        assertThat(treasuryService.buscarMovimientos(null, null, null, null))
                .extracting(Movement::getDate).allMatch(f -> !f.isAfter(HOY));

        kpis(YearMonth.of(2026, 1), 23985640.0, 14518000.0, 20156000.0, 13572000.0, 7294000.0, 0.0);
        kpis(YearMonth.of(2026, 2), 27494950.0, 10595760.0, 23105000.0, 15636000.0, 7221800.0, 310000.0);
        kpis(YearMonth.of(2026, 3), 17053890.0, 7097160.0, 14331000.0, 9606000.0, 5192200.0, 0.0);
    }

    private void kpis(YearMonth mes, double ventas, double compras, double ventasSinIva, double costo, double gastos,
                      double otros) {
        KpisResponse kpis = reportingService.getPeriodKpis(mes.atDay(1), mes.atEndOfMonth());
        assertThat(kpis.totalSales()).as("ventas " + mes).isCloseTo(ventas, within(0.001));
        assertThat(kpis.totalPurchases()).as("compras " + mes).isCloseTo(compras, within(0.001));
        assertThat(kpis.netSales()).as("ventas sin IVA " + mes).isCloseTo(ventasSinIva, within(0.001));
        assertThat(kpis.costOfSales()).as("costo " + mes).isCloseTo(costo, within(0.001));
        assertThat(kpis.operatingExpenses()).as("gastos " + mes).isCloseTo(gastos, within(0.001));
        assertThat(kpis.otherIncome()).as("otros " + mes).isCloseTo(otros, within(0.001));
    }
}
