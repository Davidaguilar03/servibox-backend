package com.servibox.backend.treasury.dto;

import com.servibox.backend.treasury.entity.OccasionalIncome;

import java.time.LocalDate;

public record OccasionalIncomeResponse(
        Long id,
        Long accountId,
        String accountName,
        String concept,
        Double amount,
        LocalDate date
) {

    public static OccasionalIncomeResponse from(OccasionalIncome income) {
        return new OccasionalIncomeResponse(
                income.getId(),
                income.getAccount() != null ? income.getAccount().getId() : null,
                income.getAccount() != null ? income.getAccount().getName() : null,
                income.getConcept(),
                income.getAmount(),
                income.getDate()
        );
    }
}
