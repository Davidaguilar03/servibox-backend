package com.servibox.backend.treasury.dto;

import com.servibox.backend.treasury.entity.Movement;
import com.servibox.backend.treasury.entity.MovementCategory;
import com.servibox.backend.treasury.entity.MovementType;

import java.time.LocalDate;

public record MovementResponse(
        Long id,
        Long accountId,
        String accountName,
        MovementType type,
        MovementCategory category,
        String concept,
        Double amount,
        LocalDate date,
        String notes,
        /** Factura que genero el movimiento (SALE, PURCHASE), o null. */
        String sourceType,
        /** Id de la factura de origen, interpretado segun sourceType. */
        Long sourceId,
        /** En la pata INGRESO de una transferencia, el id de la pata EGRESO. */
        Long relatedMovementId
) {

    public static MovementResponse from(Movement movement) {
        return new MovementResponse(
                movement.getId(),
                movement.getAccount() != null ? movement.getAccount().getId() : null,
                movement.getAccount() != null ? movement.getAccount().getName() : null,
                movement.getType(),
                movement.getCategory(),
                movement.getConcept(),
                movement.getAmount(),
                movement.getDate(),
                movement.getNotes(),
                movement.getSourceType() != null ? movement.getSourceType().name() : null,
                movement.getSourceId(),
                movement.getRelatedMovement() != null ? movement.getRelatedMovement().getId() : null
        );
    }
}
