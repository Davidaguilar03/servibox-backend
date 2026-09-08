package com.servibox.backend.treasury.controller;

import com.servibox.backend.treasury.dto.AccountRequest;
import com.servibox.backend.treasury.dto.AccountResponse;
import com.servibox.backend.treasury.dto.BalanceResponse;
import com.servibox.backend.treasury.dto.MovementRequest;
import com.servibox.backend.treasury.dto.MovementResponse;
import com.servibox.backend.treasury.dto.OccasionalIncomeRequest;
import com.servibox.backend.treasury.dto.OccasionalIncomeResponse;
import com.servibox.backend.treasury.dto.OperationalExpenseRequest;
import com.servibox.backend.treasury.dto.OperationalExpenseResponse;
import com.servibox.backend.treasury.dto.TransferRequest;
import com.servibox.backend.treasury.dto.TransferResponse;
import com.servibox.backend.shared.ResourceNotFoundException;
import com.servibox.backend.treasury.entity.Account;
import com.servibox.backend.treasury.service.TreasuryService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/treasury")
@RequiredArgsConstructor
public class TreasuryController {

    private final TreasuryService treasuryService;

    @GetMapping("/accounts")
    public List<AccountResponse> listarCuentas() {
        return treasuryService.findAllAccounts().stream().map(AccountResponse::from).toList();
    }

    @PostMapping("/accounts")
    @ResponseStatus(HttpStatus.CREATED)
    public AccountResponse crearCuenta(@Valid @RequestBody AccountRequest request) {
        Account account = new Account();
        account.setName(request.name());
        account.setInitialBalance(request.initialBalance());
        account.setType(request.type());
        return AccountResponse.from(treasuryService.saveAccount(account));
    }

    @GetMapping("/accounts/{id}/movements")
    public List<MovementResponse> listarMovimientos(@PathVariable Long id) {
        cuentaDeRuta(id);
        return treasuryService.findMovementsByAccountId(id).stream()
                .map(MovementResponse::from)
                .toList();
    }

    @PostMapping("/movements")
    @ResponseStatus(HttpStatus.CREATED)
    public MovementResponse registrarMovimiento(@Valid @RequestBody MovementRequest request) {
        Account cuenta = cuentaObligatoria(request.accountId());
        return MovementResponse.from(treasuryService.registrarMovimiento(
                cuenta, request.type(), request.concept(), request.amount()));
    }

    @PostMapping("/transfers")
    @ResponseStatus(HttpStatus.CREATED)
    public TransferResponse registrarTransferencia(@Valid @RequestBody TransferRequest request) {
        Account origen = cuentaObligatoria(request.originAccountId());
        Account destino = cuentaObligatoria(request.destinationAccountId());
        return TransferResponse.from(treasuryService.registrarTransferencia(
                origen, destino, request.concept(), request.amount()));
    }

    @GetMapping("/occasional-incomes")
    public List<OccasionalIncomeResponse> listarIngresosOcasionales() {
        return treasuryService.findAllOccasionalIncomes().stream()
                .map(OccasionalIncomeResponse::from)
                .toList();
    }

    @PostMapping("/occasional-incomes")
    @ResponseStatus(HttpStatus.CREATED)
    public OccasionalIncomeResponse registrarIngresoOcasional(
            @Valid @RequestBody OccasionalIncomeRequest request) {
        Account cuenta = cuentaObligatoria(request.accountId());
        return OccasionalIncomeResponse.from(treasuryService.registrarIngresoOcasional(
                cuenta, request.concept(), request.amount(), request.date()));
    }

    @GetMapping("/operational-expenses")
    public List<OperationalExpenseResponse> listarGastosOperativos() {
        return treasuryService.findAllOperationalExpenses().stream()
                .map(OperationalExpenseResponse::from)
                .toList();
    }

    @PostMapping("/operational-expenses")
    @ResponseStatus(HttpStatus.CREATED)
    public OperationalExpenseResponse registrarGastoOperativo(
            @Valid @RequestBody OperationalExpenseRequest request) {
        Account cuenta = cuentaObligatoria(request.accountId());
        return OperationalExpenseResponse.from(treasuryService.registrarGastoOperativo(
                cuenta, request.concept(), request.amount(), request.date(), request.notes()));
    }

    /**
     * PUT y no PATCH: el cuerpo trae el gasto completo, igual que el formulario de
     * Autollantas, que reenvia todos los campos al guardar en modo edicion.
     */
    @PutMapping("/operational-expenses/{id}")
    public OperationalExpenseResponse editarGastoOperativo(
            @PathVariable Long id, @Valid @RequestBody OperationalExpenseRequest request) {
        gastoDeRuta(id);
        Account cuenta = cuentaObligatoria(request.accountId());
        return OperationalExpenseResponse.from(treasuryService.editarGastoOperativo(
                id, cuenta, request.concept(), request.amount(), request.date(), request.notes()));
    }

    /**
     * DELETE y no un /annul: el registro **desaparece**, no queda en un estado ANULADA.
     * Es lo que hace "Eliminar" en Autollantas. Ver 02-CONVENTIONS.md.
     */
    @DeleteMapping("/operational-expenses/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void eliminarGastoOperativo(@PathVariable Long id) {
        gastoDeRuta(id);
        treasuryService.anularGastoOperativo(id);
    }

    @GetMapping("/balance")
    public BalanceResponse balanceGlobal() {
        return new BalanceResponse(treasuryService.balanceGlobal());
    }

    /**
     * Cuenta pedida por la ruta: si no existe para este tenant, 404. El mismo 404 cubre
     * "no existe" y "es de otro tenant", ver ResourceNotFoundException.
     */
    private Account cuentaDeRuta(Long id) {
        return treasuryService.findAccountById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Cuenta no encontrada: " + id));
    }

    /** Gasto operativo pedido por la ruta: si no existe para este tenant, 404. */
    private void gastoDeRuta(Long id) {
        treasuryService.findOperationalExpenseById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Gasto operativo no encontrado: " + id));
    }

    /**
     * Cuenta referida desde el cuerpo de la peticion: si no resuelve, el problema es la
     * peticion, asi que 400.
     */
    private Account cuentaObligatoria(Long id) {
        return treasuryService.findAccountById(id)
                .orElseThrow(() -> new IllegalArgumentException("Cuenta no encontrada: " + id));
    }
}
