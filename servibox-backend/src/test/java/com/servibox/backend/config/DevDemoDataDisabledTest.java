package com.servibox.backend.config;

import com.servibox.backend.counterparties.service.CounterpartyService;
import com.servibox.backend.inventory.service.InventoryService;
import com.servibox.backend.purchases.service.PurchasesService;
import com.servibox.backend.sales.service.SalesService;
import com.servibox.backend.tenant.TenantContext;
import com.servibox.backend.tenant.TenantRepository;
import com.servibox.backend.treasury.service.TreasuryService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/** Con servibox.dev.demo-data en false el perfil dev deja solo la siembra base. */
@SpringBootTest
@ActiveProfiles({"test", "dev"})
@TestPropertySource(properties = "servibox.dev.demo-data=false")
class DevDemoDataDisabledTest {

    @Autowired
    private TenantRepository tenantRepository;

    @Autowired
    private InventoryService inventoryService;

    @Autowired
    private TreasuryService treasuryService;

    @Autowired
    private SalesService salesService;

    @Autowired
    private PurchasesService purchasesService;

    @Autowired
    private CounterpartyService counterpartyService;

    @AfterEach
    void cleanUp() {
        TenantContext.clear();
    }

    @Test
    void conLaPropiedadEnFalseNoSiembraDatosDeDemostracion() {
        TenantContext.setTenantId(tenantRepository.findBySlug("demo").orElseThrow().getId());

        // La siembra base sigue: 8 categorias y 2 cuentas en cero.
        assertThat(inventoryService.findAllCategories()).hasSize(8);
        assertThat(treasuryService.findAllAccounts()).hasSize(2);
        assertThat(treasuryService.balanceGlobal()).isZero();

        assertThat(inventoryService.findAllProducts()).isEmpty();
        assertThat(counterpartyService.findAll()).isEmpty();
        assertThat(salesService.findAllSales()).isEmpty();
        assertThat(purchasesService.findAllPurchases()).isEmpty();
        assertThat(treasuryService.buscarMovimientos(null, null, null, null)).isEmpty();
    }
}
