package com.servibox.backend.treasury.dto;

import com.servibox.backend.treasury.entity.Movement;
import com.servibox.backend.treasury.service.TreasuryService;

import java.time.LocalDate;

/**
 * Una transferencia no tiene fila propia: son sus dos movimientos. outgoingMovementId es
 * la pata EGRESO del origen e incomingMovementId la pata INGRESO del destino.
 */
public record TransferResponse(
        Long outgoingMovementId,
        Long incomingMovementId,
        Long originAccountId,
        String originAccountName,
        Long destinationAccountId,
        String destinationAccountName,
        String concept,
        Double amount,
        LocalDate date
) {

    public static TransferResponse from(TreasuryService.Transferencia transferencia) {
        Movement egreso = transferencia.egreso();
        Movement ingreso = transferencia.ingreso();
        return new TransferResponse(
                egreso.getId(),
                ingreso.getId(),
                egreso.getAccount().getId(),
                egreso.getAccount().getName(),
                ingreso.getAccount().getId(),
                ingreso.getAccount().getName(),
                egreso.getConcept(),
                egreso.getAmount(),
                egreso.getDate()
        );
    }
}
