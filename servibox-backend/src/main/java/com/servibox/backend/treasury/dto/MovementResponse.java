package com.servibox.backend.treasury.dto;

import com.servibox.backend.treasury.entity.Movement;
import com.servibox.backend.treasury.entity.MovementType;

import java.time.LocalDate;

public record MovementResponse(
        Long id,
        Long accountId,
        String accountName,
        MovementType type,
        String concept,
        Double amount,
        LocalDate date,
        /** Id del Transfer que genero el movimiento, o null si es un movimiento suelto. */
        Long sourceTransferId,
        /** Id de la Sale que genero el movimiento, o null si no viene de una venta. */
        Long sourceSaleId,
        /** Id de la Purchase que genero el movimiento, o null si no viene de una compra. */
        Long sourcePurchaseId
) {

    public static MovementResponse from(Movement movement) {
        return new MovementResponse(
                movement.getId(),
                movement.getAccount() != null ? movement.getAccount().getId() : null,
                movement.getAccount() != null ? movement.getAccount().getName() : null,
                movement.getType(),
                movement.getConcept(),
                movement.getAmount(),
                movement.getDate(),
                movement.getSourceTransfer() != null ? movement.getSourceTransfer().getId() : null,
                movement.getSourceSale() != null ? movement.getSourceSale().getId() : null,
                movement.getSourcePurchase() != null ? movement.getSourcePurchase().getId() : null
        );
    }
}
