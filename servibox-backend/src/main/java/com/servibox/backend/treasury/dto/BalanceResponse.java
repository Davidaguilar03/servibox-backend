package com.servibox.backend.treasury.dto;

/** El "Total Global" de Accounts.fxml: suma de los saldos vivos del tenant activo. */
public record BalanceResponse(Double total) {
}
