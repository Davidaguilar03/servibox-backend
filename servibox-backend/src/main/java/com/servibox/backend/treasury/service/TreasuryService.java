package com.servibox.backend.treasury.service;

import com.servibox.backend.tenant.TenantContext;
import com.servibox.backend.treasury.entity.Account;
import com.servibox.backend.treasury.entity.Movement;
import com.servibox.backend.treasury.entity.MovementType;
import com.servibox.backend.treasury.entity.Transfer;
import com.servibox.backend.treasury.repository.AccountRepository;
import com.servibox.backend.treasury.repository.MovementRepository;
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
        return aplicarMovimiento(recargar(cuenta), tipo, concepto, monto, null);
    }

    /**
     * Unico sitio donde se crea un Movement y se mueve el saldo de su cuenta. Las dos
     * cosas van juntas a proposito: un movimiento guardado sin mover el saldo, o un saldo
     * movido sin movimiento que lo explique, es un descuadre silencioso.
     *
     * Por eso tampoco hay una segunda via para tocar currentBalance: la transferencia
     * pasa por aqui igual que el movimiento suelto, solo que con el Transfer de origen.
     * Mientras el signo del saldo se decida en un unico `if`, no hay forma de que las dos
     * rutas se desincronicen.
     */
    private Movement aplicarMovimiento(Account cuenta, MovementType tipo, String concepto,
                                       Double monto, Transfer origen) {
        Movement movimiento = new Movement();
        movimiento.setAccount(cuenta);
        movimiento.setType(tipo);
        movimiento.setConcept(concepto);
        movimiento.setAmount(monto);
        movimiento.setDate(LocalDate.now());
        movimiento.setSourceTransfer(origen);
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
     * origen y un INGRESO en el destino, los dos apuntando al Transfer con sourceTransfer,
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
                "Transferencia a " + destino.getName(), monto, guardada);
        aplicarMovimiento(destino, MovementType.INGRESO,
                "Transferencia desde " + origen.getName(), monto, guardada);

        return guardada;
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
