package com.servibox.backend.treasury.dto;

import com.servibox.backend.treasury.entity.OperationalExpense;

import java.time.LocalDate;

public record OperationalExpenseResponse(
        Long id,
        Long accountId,
        String accountName,
        String concept,
        Double amount,
        LocalDate date,
        String notes
) {

    public static OperationalExpenseResponse from(OperationalExpense expense) {
        return new OperationalExpenseResponse(
                expense.getId(),
                expense.getAccount() != null ? expense.getAccount().getId() : null,
                expense.getAccount() != null ? expense.getAccount().getName() : null,
                expense.getConcept(),
                expense.getAmount(),
                expense.getDate(),
                expense.getNotes()
        );
    }
}
