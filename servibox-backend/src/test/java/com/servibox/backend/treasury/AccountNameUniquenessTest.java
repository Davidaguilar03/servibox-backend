package com.servibox.backend.treasury;

import com.servibox.backend.tenant.Tenant;
import com.servibox.backend.tenant.TenantContext;
import com.servibox.backend.tenant.TenantRepository;
import com.servibox.backend.treasury.entity.Account;
import com.servibox.backend.treasury.entity.AccountType;
import com.servibox.backend.treasury.repository.AccountRepository;
import com.servibox.backend.treasury.service.DuplicateAccountNameException;
import com.servibox.backend.treasury.service.TreasuryService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
class AccountNameUniquenessTest {

    private static final String NOMBRE = "Caja General";

    @Autowired
    private TreasuryService treasuryService;

    @Autowired
    private AccountRepository accountRepository;

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

    private Account nuevaCuenta(String nombre) {
        Account cuenta = new Account();
        cuenta.setName(nombre);
        cuenta.setType(AccountType.CASH);
        cuenta.setInitialBalance(0.0);
        return cuenta;
    }

    @Test
    void dosCuentasConElMismoNombreEnElMismoTenantFalla() {
        TenantContext.setTenantId(tenantUno);
        treasuryService.saveAccount(nuevaCuenta(NOMBRE));

        assertThatThrownBy(() -> treasuryService.saveAccount(nuevaCuenta(NOMBRE)))
                .isInstanceOf(DuplicateAccountNameException.class)
                .hasMessage("Ya existe una cuenta con el nombre " + NOMBRE);
    }

    @Test
    void dosCuentasConElMismoNombreEnTenantsDistintosFunciona() {
        TenantContext.setTenantId(tenantUno);
        Account enTenantUno = treasuryService.saveAccount(nuevaCuenta(NOMBRE));

        TenantContext.setTenantId(tenantDos);
        Account enTenantDos = treasuryService.saveAccount(nuevaCuenta(NOMBRE));

        assertThat(enTenantUno.getName()).isEqualTo(enTenantDos.getName());
        assertThat(enTenantUno.getTenantId()).isEqualTo(tenantUno);
        assertThat(enTenantDos.getTenantId()).isEqualTo(tenantDos);
        assertThat(enTenantUno.getId()).isNotEqualTo(enTenantDos.getId());
    }

    /**
     * La validacion del service no reemplaza a la restriccion de base: si alguien escribe
     * por el repositorio directo, la base tiene que seguir frenandolo. Esto prueba que
     * uk_cuenta_tenant_name existe de verdad en el DDL.
     */
    @Test
    void laRestriccionDeBaseFrenaAunSaltandoseElService() {
        TenantContext.setTenantId(tenantUno);
        accountRepository.saveAndFlush(nuevaCuenta(NOMBRE));

        assertThatThrownBy(() -> accountRepository.saveAndFlush(nuevaCuenta(NOMBRE)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void guardarDeNuevoLaMismaCuentaSinCambiarleElNombreNoEsDuplicado() {
        TenantContext.setTenantId(tenantUno);
        Account guardada = treasuryService.saveAccount(nuevaCuenta(NOMBRE));

        guardada.setType(AccountType.BANK);

        assertThatCode(() -> treasuryService.saveAccount(guardada)).doesNotThrowAnyException();
    }

    @Test
    void unaCuentaNuevaArrancaConElSaldoDeApertura() {
        TenantContext.setTenantId(tenantUno);
        Account cuenta = nuevaCuenta("Caja Chica");
        cuenta.setInitialBalance(150000.0);

        Account guardada = treasuryService.saveAccount(cuenta);

        assertThat(guardada.getCurrentBalance()).isEqualTo(150000.0);
    }
}
