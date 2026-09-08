package com.servibox.backend.treasury.service;

import com.servibox.backend.purchases.entity.Purchase;
import com.servibox.backend.purchases.service.InsufficientBalanceException;
import com.servibox.backend.sales.entity.Sale;
import com.servibox.backend.tenant.TenantContext;
import com.servibox.backend.treasury.entity.Account;
import com.servibox.backend.treasury.entity.Movement;
import com.servibox.backend.treasury.entity.MovementSourceType;
import com.servibox.backend.treasury.entity.MovementType;
import com.servibox.backend.treasury.entity.OccasionalIncome;
import com.servibox.backend.treasury.entity.Transfer;
import com.servibox.backend.treasury.repository.AccountRepository;
import com.servibox.backend.treasury.repository.MovementRepository;
import com.servibox.backend.treasury.repository.OccasionalIncomeRepository;
import com.servibox.backend.treasury.repository.TransferRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class TreasuryService {

    private final AccountRepository accountRepository;
    private final MovementRepository movementRepository;
    private final TransferRepository transferRepository;
    private final OccasionalIncomeRepository occasionalIncomeRepository;

    @Transactional(readOnly = true)
    public List<Account> findAllAccounts() {
        return accountRepository.findAll();
    }

    /**
     * No usa accountRepository.findById: el @Filter de Hibernate no se aplica a
     * EntityManager.find(), asi que ese camino devuelve cuentas de otros tenants.
     * Verificado con TreasuryTenantIsolationTest.
     */
    @Transactional(readOnly = true)
    public Optional<Account> findAccountById(Long id) {
        Long tenantId = TenantContext.getTenantId();
        if (tenantId == null) {
            return Optional.empty();
        }
        return accountRepository.findByIdAndTenantId(id, tenantId);
    }

    @Transactional(readOnly = true)
    public List<Movement> findMovementsByAccountId(Long accountId) {
        return movementRepository.findByAccountIdOrderByDateDesc(accountId);
    }

    /**
     * Una cuenta nueva arranca con currentBalance igual a su initialBalance: mientras no
     * haya movimientos, el saldo vivo es el saldo de apertura.
     */
    @Transactional
    public Account saveAccount(Account account) {
        validarNombreUnico(account);
        if (account.getInitialBalance() == null) {
            account.setInitialBalance(0.0);
        }
        if (account.getCurrentBalance() == null) {
            account.setCurrentBalance(account.getInitialBalance());
        }
        return accountRepository.save(account);
    }

    /**
     * La base tambien lo impide con uk_cuenta_tenant_name, pero esa restriccion salta
     * como un error crudo de constraint violation que no le sirve a nadie. Esto lo
     * convierte en un mensaje legible antes de llegar al INSERT.
     *
     * Al editar hay que excluir el propio registro: guardar una cuenta sin cambiarle el
     * nombre no es un duplicado.
     */
    private void validarNombreUnico(Account account) {
        if (account.getName() == null || account.getName().isBlank()) {
            return;
        }
        Long tenantId = TenantContext.getTenantId();
        if (tenantId == null) {
            return;
        }
        accountRepository.findByNameAndTenantId(account.getName(), tenantId)
                .filter(existente -> !existente.getId().equals(account.getId()))
                .ifPresent(existente -> {
                    throw new DuplicateAccountNameException(account.getName());
                });
    }

    /**
     * Registra un ingreso o egreso suelto, de los que no vienen de una transferencia.
     */
    @Transactional
    public Movement registrarMovimiento(Account cuenta, MovementType tipo, String concepto, Double monto) {
        return aplicarMovimiento(recargar(cuenta), tipo, concepto, monto, null, null);
    }

    /**
     * Ingreso generado por una venta: el del contado al facturar, o el de un abono. Queda
     * ligado a la factura por (SALE, id) para poder revertirlo al anularla.
     */
    @Transactional
    public Movement registrarIngresoDeVenta(Account cuenta, String concepto, Double monto, Sale venta) {
        return aplicarMovimiento(recargar(cuenta), MovementType.INGRESO, concepto, monto,
                MovementSourceType.SALE, venta.getId());
    }

    /**
     * Unico sitio donde se crea un Movement y se mueve el saldo de su cuenta. Las dos
     * cosas van juntas a proposito: un movimiento guardado sin mover el saldo, o un saldo
     * movido sin movimiento que lo explique, es un descuadre silencioso.
     *
     * Por eso tampoco hay una segunda via para tocar currentBalance: la transferencia
     * pasa por aqui igual que el movimiento suelto, solo que con su origen.
     * Mientras el signo del saldo se decida en un unico `if`, no hay forma de que las dos
     * rutas se desincronicen.
     *
     * El origen viaja como (tipo, id) y no como una entidad por cada clase posible. Los
     * metodos publicos de arriba son los que reciben la entidad tipada y sacan el id de
     * ella; aqui abajo ya da igual de que tabla venia.
     */
    private Movement aplicarMovimiento(Account cuenta, MovementType tipo, String concepto,
                                       Double monto, MovementSourceType tipoOrigen, Long idOrigen) {
        Movement movimiento = new Movement();
        movimiento.setAccount(cuenta);
        movimiento.setType(tipo);
        movimiento.setConcept(concepto);
        movimiento.setAmount(monto);
        movimiento.setDate(LocalDate.now());
        movimiento.setSourceType(tipoOrigen);
        movimiento.setSourceId(idOrigen);
        Movement guardado = movementRepository.save(movimiento);

        double saldo = saldoDe(cuenta);
        cuenta.setCurrentBalance(tipo == MovementType.INGRESO ? saldo + monto : saldo - monto);
        accountRepository.save(cuenta);

        return guardado;
    }

    /**
     * Debita el origen y acredita el destino por el mismo monto, en una sola transaccion:
     * o se mueven los dos saldos o no se mueve ninguno. El dinero se conserva, el balance
     * global del tenant queda igual que antes.
     *
     * Los saldos no se tocan aqui directamente: la transferencia genera un EGRESO en el
     * origen y un INGRESO en el destino, los dos apuntando al Transfer con (TRANSFER, id),
     * y son esos movimientos los que mueven el saldo. Asi el historial de una cuenta
     * explica todos sus cambios de saldo, igual que en Autollantas.
     */
    @Transactional
    public Transfer registrarTransferencia(Account cuentaOrigen, Account cuentaDestino,
                                           String concepto, Double monto) {
        if (monto == null || monto <= 0) {
            throw new InvalidTransferException("El monto de la transferencia debe ser mayor a cero");
        }
        if (cuentaOrigen == null || cuentaDestino == null) {
            throw new InvalidTransferException("La transferencia necesita cuenta origen y cuenta destino");
        }
        if (cuentaOrigen.getId() != null && cuentaOrigen.getId().equals(cuentaDestino.getId())) {
            throw new InvalidTransferException("La cuenta origen y la cuenta destino no pueden ser la misma");
        }

        Account origen = recargar(cuentaOrigen);
        Account destino = recargar(cuentaDestino);

        Transfer transferencia = new Transfer();
        transferencia.setOriginAccount(origen);
        transferencia.setDestinationAccount(destino);
        transferencia.setAmount(monto);
        transferencia.setConcept(concepto);
        transferencia.setDate(LocalDate.now());
        Transfer guardada = transferRepository.save(transferencia);

        aplicarMovimiento(origen, MovementType.EGRESO,
                "Transferencia a " + destino.getName(), monto,
                MovementSourceType.TRANSFER, guardada.getId());
        aplicarMovimiento(destino, MovementType.INGRESO,
                "Transferencia desde " + origen.getName(), monto,
                MovementSourceType.TRANSFER, guardada.getId());

        return guardada;
    }

    @Transactional(readOnly = true)
    public List<OccasionalIncome> findAllOccasionalIncomes() {
        return occasionalIncomeRepository.findAll();
    }

    @Transactional(readOnly = true)
    public Optional<OccasionalIncome> findOccasionalIncomeById(Long id) {
        Long tenantId = TenantContext.getTenantId();
        if (tenantId == null) {
            return Optional.empty();
        }
        return occasionalIncomeRepository.findByIdAndTenantId(id, tenantId);
    }

    /**
     * Ingreso puntual que no viene de una venta. Pasa por aplicarMovimiento como todo lo
     * demas: el saldo lo mueve el Movement, no este metodo.
     */
    @Transactional
    public OccasionalIncome registrarIngresoOcasional(Account cuenta, String concepto,
                                                      Double monto, LocalDate fecha) {
        if (cuenta == null) {
            throw new IllegalArgumentException("El ingreso ocasional necesita una cuenta");
        }
        if (monto == null || monto <= 0) {
            throw new IllegalArgumentException("El monto del ingreso debe ser mayor a cero");
        }

        Account gestionada = recargar(cuenta);

        OccasionalIncome ingreso = new OccasionalIncome();
        ingreso.setConcept(concepto);
        ingreso.setAmount(monto);
        ingreso.setAccount(gestionada);
        ingreso.setDate(fecha != null ? fecha : LocalDate.now());
        OccasionalIncome guardado = occasionalIncomeRepository.save(ingreso);

        aplicarMovimiento(gestionada, MovementType.INGRESO, concepto, monto,
                MovementSourceType.OCCASIONAL_INCOME, guardado.getId());

        return guardado;
    }

    /**
     * Deshace un ingreso ocasional: borra su movimiento, resta el importe del saldo y
     * **elimina la fila**. Es lo que hace `deleteOccasionalIncome` de Autollantas, donde la
     * accion en la interfaz se llama "Eliminar".
     *
     * Ojo con el nombre: aqui "anular" no deja un estado ANULADA como en una factura de
     * venta, el registro desaparece. Un ingreso ocasional no es un documento fiscal, no hay
     * nada que conservar. Ver 03-DECISIONS.md.
     *
     * Comparte con la anulacion de ventas la excepcion a la regla de que aplicarMovimiento
     * es el unico punto que toca currentBalance, y por el mismo motivo.
     */
    @Transactional
    public void anularIngresoOcasional(Long ingresoId) {
        OccasionalIncome ingreso = findOccasionalIncomeById(ingresoId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Ingreso ocasional no encontrado: " + ingresoId));

        revertir(movimientosDe(MovementSourceType.OCCASIONAL_INCOME, ingreso.getId()));
        occasionalIncomeRepository.delete(ingreso);
    }

    /**
     * Egreso generado por una compra: el del contado al facturar, o el de un pago. Queda
     * ligado a la factura por (PURCHASE, id) para poder revertirlo al anularla.
     *
     * Valida que la cuenta tenga saldo. En Autollantas esa comprobacion vive en el
     * formulario; aqui vive donde se mueve el dinero, que es el unico sitio por el que
     * pasan todos los egresos.
     */
    @Transactional
    public Movement registrarEgresoDeCompra(Account cuenta, String concepto, Double monto, Purchase compra) {
        Account gestionada = recargar(cuenta);
        exigirSaldoSuficiente(gestionada, monto);
        return aplicarMovimiento(gestionada, MovementType.EGRESO, concepto, monto,
                MovementSourceType.PURCHASE, compra.getId());
    }

    /** Lanza InsufficientBalanceException si la cuenta no puede cubrir el monto. */
    @Transactional(readOnly = true)
    public void exigirSaldoSuficiente(Account cuenta, Double monto) {
        Account gestionada = recargar(cuenta);
        double saldo = saldoDe(gestionada);
        double requerido = monto != null ? monto : 0.0;
        if (saldo < requerido) {
            throw new InsufficientBalanceException(gestionada.getName(), saldo, requerido);
        }
    }

    /**
     * Deshace en tesoreria todo lo que genero una compra. Espejo de
     * revertirMovimientosDeVenta, con la misma excepcion a la regla de aplicarMovimiento.
     */
    @Transactional
    public void revertirMovimientosDeCompra(Purchase compra) {
        revertir(movimientosDe(MovementSourceType.PURCHASE, compra.getId()));
    }

    /**
     * Borra los movimientos que genero una venta y deshace su efecto sobre el saldo de la
     * cuenta. Es lo que hace `SalesService.cancelSale` de Autollantas al anular.
     *
     * **Es la unica excepcion a la regla de que aplicarMovimiento es el unico punto que
     * toca currentBalance**, y esta aqui y no en SalesService a proposito: si el saldo se
     * va a mover por fuera del camino normal, que al menos sea dentro del modulo que es
     * dueno del saldo. El motivo de que sea una excepcion y no un contra-movimiento esta
     * en 03-DECISIONS.md.
     */
    @Transactional
    public void revertirMovimientosDeVenta(Sale venta) {
        revertir(movimientosDe(MovementSourceType.SALE, venta.getId()));
    }

    /** Los movimientos de un origen concreto, dentro del tenant activo. */
    private List<Movement> movimientosDe(MovementSourceType tipo, Long id) {
        Long tenantId = TenantContext.getTenantId();
        if (tenantId == null) {
            return List.of();
        }
        return movementRepository.findBySourceTypeAndSourceIdAndTenantId(tipo, id, tenantId);
    }

    /** Deshace cada movimiento al reves: un INGRESO se resta, un EGRESO se suma. */
    private void revertir(List<Movement> movimientos) {
        for (Movement movimiento : movimientos) {
            Account cuenta = movimiento.getAccount();
            double monto = movimiento.getAmount() != null ? movimiento.getAmount() : 0.0;
            if (cuenta != null) {
                Account gestionada = recargar(cuenta);
                gestionada.setCurrentBalance(movimiento.getType() == MovementType.INGRESO
                        ? saldoDe(gestionada) - monto
                        : saldoDe(gestionada) + monto);
                accountRepository.save(gestionada);
            }
            movementRepository.delete(movimiento);
        }
    }

    /**
     * El "Total Global" de Accounts.fxml: suma de los saldos vivos de todas las cuentas.
     * El filtro de Hibernate ya limita findAll al tenant activo.
     */
    @Transactional(readOnly = true)
    public double balanceGlobal() {
        return accountRepository.findAll().stream()
                .mapToDouble(this::saldoDe)
                .sum();
    }

    @Transactional(readOnly = true)
    public List<Transfer> findTransfersByAccountId(Long accountId) {
        return transferRepository.findByOriginAccountIdOrDestinationAccountIdOrderByDateDesc(
                accountId, accountId);
    }

    /**
     * Trae la instancia gestionada por la sesion. Si llega una cuenta desprendida con un
     * saldo viejo en memoria, actualizarla escribiria un saldo equivocado.
     */
    private Account recargar(Account cuenta) {
        if (cuenta.getId() == null) {
            return cuenta;
        }
        return findAccountById(cuenta.getId()).orElse(cuenta);
    }

    private double saldoDe(Account cuenta) {
        return cuenta.getCurrentBalance() != null ? cuenta.getCurrentBalance() : 0.0;
    }
}
