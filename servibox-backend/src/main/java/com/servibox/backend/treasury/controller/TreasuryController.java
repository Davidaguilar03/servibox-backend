package com.servibox.backend.treasury.controller;

import com.servibox.backend.treasury.dto.AccountRequest;
import com.servibox.backend.treasury.dto.AccountResponse;
import com.servibox.backend.treasury.dto.BalanceResponse;
import com.servibox.backend.treasury.dto.MovementRequest;
import com.servibox.backend.treasury.dto.MovementResponse;
import com.servibox.backend.treasury.dto.TransferRequest;
import com.servibox.backend.treasury.dto.TransferResponse;
import com.servibox.backend.treasury.entity.Account;
import com.servibox.backend.treasury.service.TreasuryService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
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
        cuentaObligatoria(id);
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

    @GetMapping("/balance")
    public BalanceResponse balanceGlobal() {
        return new BalanceResponse(treasuryService.balanceGlobal());
    }

    /**
     * El filtro de Hibernate ya limita la busqueda al tenant activo, asi que una cuenta de
     * otro tenant no se encuentra y sale por aqui como 400, nunca como acceso cruzado.
     */
    private Account cuentaObligatoria(Long id) {
        return treasuryService.findAccountById(id)
                .orElseThrow(() -> new IllegalArgumentException("Cuenta no encontrada: " + id));
    }
}
