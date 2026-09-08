package com.servibox.backend.treasury.dto;

import com.servibox.backend.treasury.entity.Account;
import com.servibox.backend.treasury.entity.AccountType;

public record AccountResponse(
        Long id,
        String name,
        Double initialBalance,
        Double currentBalance,
        AccountType type
) {

    public static AccountResponse from(Account account) {
        return new AccountResponse(
                account.getId(),
                account.getName(),
                account.getInitialBalance(),
                account.getCurrentBalance(),
                account.getType()
        );
    }
}
