package com.servibox.backend.treasury;

import com.servibox.backend.tenant.Tenant;
import com.servibox.backend.tenant.TenantContext;
import com.servibox.backend.tenant.TenantRepository;
import com.servibox.backend.treasury.entity.Account;
import com.servibox.backend.treasury.entity.AccountType;
import com.servibox.backend.treasury.entity.Movement;
import com.servibox.backend.treasury.entity.MovementSourceType;
import com.servibox.backend.treasury.entity.MovementType;
import com.servibox.backend.treasury.entity.Transfer;
import com.servibox.backend.treasury.service.InvalidTransferException;
import com.servibox.backend.treasury.service.TreasuryService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Movimientos y transferencias sobre el saldo real de las cuentas. El invariante que
 * importa: un movimiento cambia el balance global, una transferencia no.
 */
@SpringBootTest
@ActiveProfiles("test")
class TreasuryMovementsTest {

    @Autowired
    private TreasuryService treasuryService;

    @Autowired
    private TenantRepository tenantRepository;

    private Account cuentaA;
    private Account cuentaB;

    @BeforeEach
    void seed() {
        Tenant tenant = new Tenant();
        String slug = "taller-" + System.nanoTime();
        tenant.setName(slug);
        tenant.setSlug(slug);
        tenant.setActive(Boolean.TRUE);
        TenantContext.setTenantId(tenantRepository.save(tenant).getId());

        cuentaA = treasuryService.saveAccount(nuevaCuenta("Caja General", AccountType.CASH));
        cuentaB = treasuryService.saveAccount(nuevaCuenta("Bancolombia", AccountType.BANK));
    }

    @AfterEach
    void cleanUp() {
        TenantContext.clear();
    }

    private Account nuevaCuenta(String nombre, AccountType tipo) {
        Account cuenta = new Account();
        cuenta.setName(nombre);
        cuenta.setType(tipo);
        cuenta.setInitialBalance(0.0);
        return cuenta;
    }

    private double saldoDe(Account cuenta) {
        return treasuryService.findAccountById(cuenta.getId()).orElseThrow().getCurrentBalance();
    }

    @Test
    void unIngresoSumaAlSaldoYUnEgresoResta() {
        assertThat(saldoDe(cuentaA)).isEqualTo(0.0);

        treasuryService.registrarMovimiento(cuentaA, MovementType.INGRESO, "Venta de contado", 50000.0);
        assertThat(saldoDe(cuentaA)).isEqualTo(50000.0);

        treasuryService.registrarMovimiento(cuentaA, MovementType.EGRESO, "Pago de servicios", 20000.0);
        assertThat(saldoDe(cuentaA)).isEqualTo(30000.0);
    }

    @Test
    void elMovimientoQuedaGuardadoYAsociadoASuCuenta() {
        Movement movimiento = treasuryService.registrarMovimiento(
                cuentaA, MovementType.INGRESO, "Venta de contado", 50000.0);

        assertThat(movimiento.getId()).isNotNull();
        assertThat(movimiento.getType()).isEqualTo(MovementType.INGRESO);
        assertThat(movimiento.getConcept()).isEqualTo("Venta de contado");
        assertThat(movimiento.getDate()).isNotNull();

        assertThat(treasuryService.findMovementsByAccountId(cuentaA.getId()))
                .extracting(Movement::getConcept)
                .containsExactly("Venta de contado");
    }

    @Test
    void unaTransferenciaDebitaElOrigenAcreditaElDestinoYConservaElDinero() {
        treasuryService.registrarMovimiento(cuentaA, MovementType.INGRESO, "Saldo de apertura", 50000.0);
        treasuryService.registrarMovimiento(cuentaA, MovementType.EGRESO, "Pago de servicios", 20000.0);
        assertThat(saldoDe(cuentaA)).isEqualTo(30000.0);
        assertThat(saldoDe(cuentaB)).isEqualTo(0.0);

        double totalAntes = treasuryService.balanceGlobal();

        Transfer transferencia = treasuryService.registrarTransferencia(
                cuentaA, cuentaB, "Consignacion del dia", 10000.0);

        assertThat(saldoDe(cuentaA)).isEqualTo(20000.0);
        assertThat(saldoDe(cuentaB)).isEqualTo(10000.0);

        // Conservacion del dinero: mover plata entre cuentas propias no crea ni destruye saldo.
        assertThat(saldoDe(cuentaA) + saldoDe(cuentaB)).isEqualTo(30000.0);
        assertThat(treasuryService.balanceGlobal()).isEqualTo(totalAntes);

        assertThat(transferencia.getId()).isNotNull();
        assertThat(transferencia.getOriginAccount().getId()).isEqualTo(cuentaA.getId());
        assertThat(transferencia.getDestinationAccount().getId()).isEqualTo(cuentaB.getId());
    }

    /**
     * La transferencia deja rastro en el historial de las dos cuentas. Antes movia los
     * saldos directamente y no generaba ningun Movement, asi que el extracto de una
     * cuenta no explicaba todos sus cambios de saldo. Ver 03-DECISIONS.md.
     */
    @Test
    void unaTransferenciaGeneraElEgresoYElIngresoApuntandoAlTransfer() {
        treasuryService.registrarMovimiento(cuentaA, MovementType.INGRESO, "Saldo de apertura", 30000.0);

        Transfer transferencia = treasuryService.registrarTransferencia(
                cuentaA, cuentaB, "Consignacion del dia", 10000.0);

        Movement egreso = treasuryService.findMovementsByAccountId(cuentaA.getId()).stream()
                .filter(m -> m.getSourceType() == MovementSourceType.TRANSFER)
                .findFirst()
                .orElseThrow();
        assertThat(egreso.getType()).isEqualTo(MovementType.EGRESO);
        assertThat(egreso.getAmount()).isEqualTo(10000.0);
        assertThat(egreso.getConcept()).isEqualTo("Transferencia a Bancolombia");
        assertThat(egreso.getSourceId()).isEqualTo(transferencia.getId());
        assertThat(egreso.getAccount().getId()).isEqualTo(cuentaA.getId());

        List<Movement> deB = treasuryService.findMovementsByAccountId(cuentaB.getId());
        assertThat(deB).hasSize(1);
        Movement ingreso = deB.get(0);
        assertThat(ingreso.getType()).isEqualTo(MovementType.INGRESO);
        assertThat(ingreso.getAmount()).isEqualTo(10000.0);
        assertThat(ingreso.getConcept()).isEqualTo("Transferencia desde Caja General");
        assertThat(ingreso.getSourceType()).isEqualTo(MovementSourceType.TRANSFER);
        assertThat(ingreso.getSourceId()).isEqualTo(transferencia.getId());
        assertThat(ingreso.getAccount().getId()).isEqualTo(cuentaB.getId());

        // Los dos movimientos salen del mismo Transfer.
        assertThat(egreso.getSourceId()).isEqualTo(ingreso.getSourceId());
    }

    /** Un ingreso o egreso registrado a mano no viene de ninguna transferencia. */
    @Test
    void unMovimientoSueltoNoTieneTransferDeOrigen() {
        treasuryService.registrarMovimiento(cuentaA, MovementType.INGRESO, "Venta de contado", 50000.0);

        assertThat(treasuryService.findMovementsByAccountId(cuentaA.getId()))
                .singleElement()
                .satisfies(m -> {
                    assertThat(m.getSourceType()).isNull();
                    assertThat(m.getSourceId()).isNull();
                });
    }

    /** Una transferencia rechazada no deja movimientos colgando. */
    @Test
    void unaTransferenciaRechazadaNoGeneraMovimientos() {
        assertThatThrownBy(() -> treasuryService.registrarTransferencia(cuentaA, cuentaB, "Cero", 0.0))
                .isInstanceOf(InvalidTransferException.class);

        assertThat(treasuryService.findMovementsByAccountId(cuentaA.getId())).isEmpty();
        assertThat(treasuryService.findMovementsByAccountId(cuentaB.getId())).isEmpty();
    }

    @Test
    void rechazaTransferirUnaCuentaASiMisma() {
        assertThatThrownBy(() -> treasuryService.registrarTransferencia(
                cuentaA, cuentaA, "Sin sentido", 10000.0))
                .isInstanceOf(InvalidTransferException.class)
                .hasMessage("La cuenta origen y la cuenta destino no pueden ser la misma");
    }

    @Test
    void rechazaMontosCeroONegativos() {
        assertThatThrownBy(() -> treasuryService.registrarTransferencia(cuentaA, cuentaB, "Cero", 0.0))
                .isInstanceOf(InvalidTransferException.class)
                .hasMessage("El monto de la transferencia debe ser mayor a cero");

        assertThatThrownBy(() -> treasuryService.registrarTransferencia(cuentaA, cuentaB, "Negativo", -5000.0))
                .isInstanceOf(InvalidTransferException.class)
                .hasMessage("El monto de la transferencia debe ser mayor a cero");
    }

    @Test
    void unaTransferenciaRechazadaNoMueveNingunSaldo() {
        treasuryService.registrarMovimiento(cuentaA, MovementType.INGRESO, "Saldo de apertura", 30000.0);

        assertThatThrownBy(() -> treasuryService.registrarTransferencia(cuentaA, cuentaB, "Cero", 0.0))
                .isInstanceOf(InvalidTransferException.class);

        assertThat(saldoDe(cuentaA)).isEqualTo(30000.0);
        assertThat(saldoDe(cuentaB)).isEqualTo(0.0);
    }

    @Test
    void elBalanceGlobalSumaLasCuentasDelTenantActivo() {
        treasuryService.registrarMovimiento(cuentaA, MovementType.INGRESO, "Venta", 50000.0);
        treasuryService.registrarMovimiento(cuentaB, MovementType.INGRESO, "Consignacion", 25000.0);

        assertThat(treasuryService.balanceGlobal()).isEqualTo(75000.0);
    }
}
