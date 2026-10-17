package com.servibox.backend.treasury.controller;

import com.servibox.backend.shared.ResourceNotFoundException;
import com.servibox.backend.treasury.dto.AccountRequest;
import com.servibox.backend.treasury.dto.AccountResponse;
import com.servibox.backend.treasury.dto.BalanceResponse;
import com.servibox.backend.treasury.dto.MovementRequest;
import com.servibox.backend.treasury.dto.MovementResponse;
import com.servibox.backend.treasury.dto.TransferRequest;
import com.servibox.backend.treasury.dto.TransferResponse;
import com.servibox.backend.treasury.entity.Account;
import com.servibox.backend.treasury.entity.Movement;
import com.servibox.backend.treasury.entity.MovementCategory;
import com.servibox.backend.treasury.service.InvalidMovementOperationException;
import com.servibox.backend.treasury.service.TreasuryService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
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
    public List<MovementResponse> listarMovimientosDeCuenta(@PathVariable Long id) {
        cuentaDeRuta(id);
        return treasuryService.findMovementsByAccountId(id).stream()
                .map(MovementResponse::from)
                .toList();
    }

    /** Libro de movimientos del tenant, con filtros opcionales. */
    @GetMapping("/movements")
    public List<MovementResponse> listarMovimientos(
            @RequestParam(required = false) Long accountId,
            @RequestParam(required = false) MovementCategory category,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate desde,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate hasta) {
        return treasuryService.buscarMovimientos(accountId, category, desde, hasta).stream()
                .map(MovementResponse::from)
                .toList();
    }

    /** Movimiento manual: GASTO_OPERATIVO o INGRESO_OCASIONAL. Otra categoria es 400. */
    @PostMapping("/movements")
    @ResponseStatus(HttpStatus.CREATED)
    public MovementResponse registrarMovimiento(@Valid @RequestBody MovementRequest request) {
        Account cuenta = cuentaObligatoria(request.accountId());
        return MovementResponse.from(treasuryService.registrarMovimientoManual(cuenta, request.category(),
                request.concept(), request.amount(), request.date(), request.notes()));
    }

    /**
     * PUT y no PATCH: el cuerpo trae el movimiento completo, igual que el formulario de
     * Autollantas. La categoria del cuerpo tiene que ser la que ya tenia.
     */
    @PutMapping("/movements/{id}")
    public MovementResponse editarMovimiento(@PathVariable Long id, @Valid @RequestBody MovementRequest request) {
        Movement actual = movimientoDeRuta(id);
        // Si no es manual, el service lo rechaza con "se gestiona desde su operacion".
        if (actual.getCategory().esManual() && request.category() != actual.getCategory()) {
            throw new InvalidMovementOperationException("La categoria de un movimiento no se puede cambiar: el "
                    + id + " es " + actual.getCategory());
        }
        Account cuenta = cuentaObligatoria(request.accountId());
        return MovementResponse.from(treasuryService.editarMovimientoManual(id, cuenta, request.concept(),
                request.amount(), request.date(), request.notes()));
    }

    /** DELETE y no un /annul: el movimiento manual **desaparece**. Ver 02-CONVENTIONS.md. */
    @DeleteMapping("/movements/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void eliminarMovimiento(@PathVariable Long id) {
        movimientoDeRuta(id);
        treasuryService.eliminarMovimientoManual(id);
    }

    @PostMapping("/transfers")
    @ResponseStatus(HttpStatus.CREATED)
    public TransferResponse registrarTransferencia(@Valid @RequestBody TransferRequest request) {
        Account origen = cuentaObligatoria(request.originAccountId());
        Account destino = cuentaObligatoria(request.destinationAccountId());
        return TransferResponse.from(treasuryService.registrarTransferencia(
                origen, destino, request.concept(), request.amount(), null));
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

    /** Movimiento pedido por la ruta: si no existe para este tenant, 404. */
    private Movement movimientoDeRuta(Long id) {
        return treasuryService.findMovementById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Movimiento no encontrado: " + id));
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
