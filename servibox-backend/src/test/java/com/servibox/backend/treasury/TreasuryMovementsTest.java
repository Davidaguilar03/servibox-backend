package com.servibox.backend.treasury;

import com.servibox.backend.shared.ResourceNotFoundException;
import com.servibox.backend.tenant.Tenant;
import com.servibox.backend.tenant.TenantContext;
import com.servibox.backend.tenant.TenantRepository;
import com.servibox.backend.treasury.entity.Account;
import com.servibox.backend.treasury.entity.AccountType;
import com.servibox.backend.treasury.entity.Movement;
import com.servibox.backend.treasury.entity.MovementCategory;
import com.servibox.backend.treasury.entity.MovementType;
import com.servibox.backend.treasury.repository.MovementRepository;
import com.servibox.backend.treasury.service.InvalidMovementOperationException;
import com.servibox.backend.treasury.service.InvalidTransferException;
import com.servibox.backend.treasury.service.TreasuryService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * El libro de movimientos: saldos, transferencias como dos movimientos y lo que la base y
 * el service impiden.
 */
@SpringBootTest
@ActiveProfiles("test")
class TreasuryMovementsTest {

    @Autowired
    private TreasuryService treasuryService;

    @Autowired
    private MovementRepository movementRepository;

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

    private void fondear(Account cuenta, double monto) {
        treasuryService.registrarIngresoOcasional(cuenta, "Saldo de apertura", monto, null, null);
    }

    private List<Movement> todos() {
        return treasuryService.buscarMovimientos(null, null, null, null);
    }

    @Test
    void unIngresoSumaAlSaldoYUnEgresoResta() {
        assertThat(saldoDe(cuentaA)).isEqualTo(0.0);

        treasuryService.registrarIngresoOcasional(cuentaA, "Venta de chatarra", 50000.0, null, null);
        assertThat(saldoDe(cuentaA)).isEqualTo(50000.0);

        treasuryService.registrarGastoOperativo(cuentaA, "Pago de servicios", 20000.0, null, null);
        assertThat(saldoDe(cuentaA)).isEqualTo(30000.0);
    }

    @Test
    void elMovimientoQuedaGuardadoYAsociadoASuCuenta() {
        Movement movimiento = treasuryService.registrarIngresoOcasional(
                cuentaA, "Venta de chatarra", 50000.0, null, null);

        assertThat(movimiento.getId()).isNotNull();
        assertThat(movimiento.getType()).isEqualTo(MovementType.INGRESO);
        assertThat(movimiento.getCategory()).isEqualTo(MovementCategory.INGRESO_OCASIONAL);
        assertThat(movimiento.getConcept()).isEqualTo("Venta de chatarra");
        assertThat(movimiento.getDate()).isEqualTo(LocalDate.now());

        assertThat(treasuryService.findMovementsByAccountId(cuentaA.getId()))
                .extracting(Movement::getConcept)
                .containsExactly("Venta de chatarra");
    }

    @Test
    void unaTransferenciaDebitaElOrigenAcreditaElDestinoYConservaElDinero() {
        fondear(cuentaA, 50000.0);
        treasuryService.registrarGastoOperativo(cuentaA, "Pago de servicios", 20000.0, null, null);
        assertThat(saldoDe(cuentaA)).isEqualTo(30000.0);
        assertThat(saldoDe(cuentaB)).isEqualTo(0.0);

        double totalAntes = treasuryService.balanceGlobal();

        TreasuryService.Transferencia transferencia = treasuryService.registrarTransferencia(
                cuentaA, cuentaB, "Consignacion del dia", 10000.0, null);

        assertThat(saldoDe(cuentaA)).isEqualTo(20000.0);
        assertThat(saldoDe(cuentaB)).isEqualTo(10000.0);

        // Conservacion del dinero: mover plata entre cuentas propias no crea ni destruye saldo.
        assertThat(saldoDe(cuentaA) + saldoDe(cuentaB)).isEqualTo(30000.0);
        assertThat(treasuryService.balanceGlobal()).isEqualTo(totalAntes);

        assertThat(transferencia.egreso().getAccount().getId()).isEqualTo(cuentaA.getId());
        assertThat(transferencia.ingreso().getAccount().getId()).isEqualTo(cuentaB.getId());
    }

    /**
     * Una transferencia no tiene tabla propia: son un EGRESO y un INGRESO de categoria
     * TRANSFERENCIA, y el INGRESO apunta al EGRESO. La suma de saldos no cambia.
     */
    @Test
    void unaTransferenciaSonDosMovimientosYElIngresoApuntaAlEgreso() {
        fondear(cuentaA, 30000.0);
        double sumaAntes = saldoDe(cuentaA) + saldoDe(cuentaB);
        LocalDate fecha = LocalDate.now().minusDays(2);

        TreasuryService.Transferencia transferencia = treasuryService.registrarTransferencia(
                cuentaA, cuentaB, "Consignacion del dia", 10000.0, fecha);

        Movement egreso = treasuryService.findMovementById(transferencia.egreso().getId()).orElseThrow();
        assertThat(egreso.getType()).isEqualTo(MovementType.EGRESO);
        assertThat(egreso.getCategory()).isEqualTo(MovementCategory.TRANSFERENCIA);
        assertThat(egreso.getAmount()).isEqualTo(10000.0);
        assertThat(egreso.getConcept()).isEqualTo("Transferencia a Bancolombia");
        assertThat(egreso.getNotes()).isEqualTo("Consignacion del dia");
        assertThat(egreso.getDate()).isEqualTo(fecha);
        assertThat(egreso.getAccount().getId()).isEqualTo(cuentaA.getId());
        assertThat(egreso.getRelatedMovement()).isNull();
        assertThat(egreso.getSale()).isNull();
        assertThat(egreso.getPurchase()).isNull();

        List<Movement> deB = treasuryService.findMovementsByAccountId(cuentaB.getId());
        assertThat(deB).singleElement().satisfies(ingreso -> {
            assertThat(ingreso.getId()).isEqualTo(transferencia.ingreso().getId());
            assertThat(ingreso.getType()).isEqualTo(MovementType.INGRESO);
            assertThat(ingreso.getCategory()).isEqualTo(MovementCategory.TRANSFERENCIA);
            assertThat(ingreso.getAmount()).isEqualTo(10000.0);
            assertThat(ingreso.getConcept()).isEqualTo("Transferencia desde Caja General");
            assertThat(ingreso.getDate()).isEqualTo(fecha);
            assertThat(ingreso.getRelatedMovement().getId()).isEqualTo(egreso.getId());
        });

        assertThat(saldoDe(cuentaA) + saldoDe(cuentaB)).isEqualTo(sumaAntes);
    }

    /** Un movimiento manual no viene de ninguna factura. */
    @Test
    void unMovimientoManualNoTieneFacturaDeOrigen() {
        treasuryService.registrarIngresoOcasional(cuentaA, "Venta de chatarra", 50000.0, null, null);

        assertThat(treasuryService.findMovementsByAccountId(cuentaA.getId()))
                .singleElement()
                .satisfies(m -> {
                    assertThat(m.getSale()).isNull();
                    assertThat(m.getPurchase()).isNull();
                    assertThat(m.getRelatedMovement()).isNull();
                });
    }

    /** Una transferencia rechazada no deja movimientos colgando. */
    @Test
    void unaTransferenciaRechazadaNoGeneraMovimientos() {
        assertThatThrownBy(() -> treasuryService.registrarTransferencia(cuentaA, cuentaB, "Cero", 0.0, null))
                .isInstanceOf(InvalidTransferException.class);

        assertThat(treasuryService.findMovementsByAccountId(cuentaA.getId())).isEmpty();
        assertThat(treasuryService.findMovementsByAccountId(cuentaB.getId())).isEmpty();
    }

    @Test
    void rechazaTransferirUnaCuentaASiMisma() {
        assertThatThrownBy(() -> treasuryService.registrarTransferencia(
                cuentaA, cuentaA, "Sin sentido", 10000.0, null))
                .isInstanceOf(InvalidTransferException.class)
                .hasMessage("La cuenta origen y la cuenta destino no pueden ser la misma");
    }

    @Test
    void rechazaMontosCeroONegativos() {
        assertThatThrownBy(() -> treasuryService.registrarTransferencia(cuentaA, cuentaB, "Cero", 0.0, null))
                .isInstanceOf(InvalidTransferException.class)
                .hasMessage("El monto de la transferencia debe ser mayor a cero");

        assertThatThrownBy(() -> treasuryService.registrarTransferencia(cuentaA, cuentaB, "Negativo", -5000.0, null))
                .isInstanceOf(InvalidTransferException.class)
                .hasMessage("El monto de la transferencia debe ser mayor a cero");
    }

    @Test
    void unaTransferenciaRechazadaNoMueveNingunSaldo() {
        fondear(cuentaA, 30000.0);

        assertThatThrownBy(() -> treasuryService.registrarTransferencia(cuentaA, cuentaB, "Cero", 0.0, null))
                .isInstanceOf(InvalidTransferException.class);

        assertThat(saldoDe(cuentaA)).isEqualTo(30000.0);
        assertThat(saldoDe(cuentaB)).isEqualTo(0.0);
    }

    @Test
    void elBalanceGlobalSumaLasCuentasDelTenantActivo() {
        fondear(cuentaA, 50000.0);
        fondear(cuentaB, 25000.0);

        assertThat(treasuryService.balanceGlobal()).isEqualTo(75000.0);
    }

    /** Las dos patas de una transferencia se gestionan desde la transferencia, no a mano. */
    @Test
    void editarOEliminarUnaPataDeTransferenciaSeRechazaSinTocarNada() {
        fondear(cuentaA, 30000.0);
        TreasuryService.Transferencia transferencia = treasuryService.registrarTransferencia(
                cuentaA, cuentaB, "Consignacion", 10000.0, null);
        List<Movement> antes = todos();

        for (Movement pata : List.of(transferencia.egreso(), transferencia.ingreso())) {
            assertThatThrownBy(() -> treasuryService.editarMovimientoManual(
                    pata.getId(), cuentaA, "Editado", 1.0, null, null))
                    .isInstanceOf(InvalidMovementOperationException.class)
                    .hasMessage("El movimiento " + pata.getId()
                            + " es de categoria TRANSFERENCIA y se gestiona desde su operacion");
            assertThatThrownBy(() -> treasuryService.eliminarMovimientoManual(pata.getId()))
                    .isInstanceOf(InvalidMovementOperationException.class);
        }

        assertThat(saldoDe(cuentaA)).isEqualTo(20000.0);
        assertThat(saldoDe(cuentaB)).isEqualTo(10000.0);
        assertThat(todos()).usingRecursiveFieldByFieldElementComparatorIgnoringFields("account", "relatedMovement")
                .containsExactlyElementsOf(antes);
    }

    @Test
    void editarOEliminarUnMovimientoQueNoExisteEsUn404() {
        assertThatThrownBy(() -> treasuryService.eliminarMovimientoManual(999999L))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("Movimiento no encontrado: 999999");
    }

    /** La base frena un movimiento sin categoria aunque se salte el service. */
    @Test
    void laBaseFrenaUnMovimientoSinCategoria() {
        Movement crudo = new Movement();
        crudo.setAccount(cuentaA);
        crudo.setType(MovementType.INGRESO);
        crudo.setConcept("Sin categoria");
        crudo.setAmount(1000.0);
        crudo.setDate(LocalDate.now());

        assertThatThrownBy(() -> movementRepository.saveAndFlush(crudo))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    /** id_movimiento_relacionado es una clave foranea de verdad. */
    @Test
    void laBaseFrenaUnMovimientoRelacionadoQueNoExiste() {
        Movement inexistente = new Movement();
        inexistente.setId(999999L);

        Movement crudo = new Movement();
        crudo.setAccount(cuentaA);
        crudo.setType(MovementType.INGRESO);
        crudo.setCategory(MovementCategory.TRANSFERENCIA);
        crudo.setConcept("Pata huerfana");
        crudo.setAmount(1000.0);
        crudo.setDate(LocalDate.now());
        crudo.setRelatedMovement(inexistente);

        assertThatThrownBy(() -> movementRepository.saveAndFlush(crudo))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
