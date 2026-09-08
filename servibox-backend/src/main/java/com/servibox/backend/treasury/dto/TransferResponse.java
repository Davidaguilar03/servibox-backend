package com.servibox.backend.treasury.dto;

import com.servibox.backend.treasury.entity.Transfer;

import java.time.LocalDate;

public record TransferResponse(
        Long id,
        Long originAccountId,
        String originAccountName,
        Long destinationAccountId,
        String destinationAccountName,
        String concept,
        Double amount,
        LocalDate date
) {

    public static TransferResponse from(Transfer transfer) {
        return new TransferResponse(
                transfer.getId(),
                transfer.getOriginAccount() != null ? transfer.getOriginAccount().getId() : null,
                transfer.getOriginAccount() != null ? transfer.getOriginAccount().getName() : null,
                transfer.getDestinationAccount() != null ? transfer.getDestinationAccount().getId() : null,
                transfer.getDestinationAccount() != null ? transfer.getDestinationAccount().getName() : null,
                transfer.getConcept(),
                transfer.getAmount(),
                transfer.getDate()
        );
    }
}
