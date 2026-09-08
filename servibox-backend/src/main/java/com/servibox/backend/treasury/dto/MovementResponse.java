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
        /** Que genero el movimiento (SALE, PURCHASE, ...), o null si es suelto. */
        String sourceType,
        /** Id del registro de origen, interpretado segun sourceType. Null si es suelto. */
        Long sourceId
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
                movement.getSourceType() != null ? movement.getSourceType().name() : null,
                movement.getSourceId()
        );
    }
}
