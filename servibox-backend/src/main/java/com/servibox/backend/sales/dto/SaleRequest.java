package com.servibox.backend.sales.dto;

import com.servibox.backend.sales.entity.PaymentType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;
import java.util.List;

public record SaleRequest(
        @NotBlank String invoiceNumber,
        Long customerId,
        LocalDate invoiceDate,
        LocalDate dueDate,
        @NotNull PaymentType paymentType,
        /** Obligatoria si paymentType es CONTADO; en CREDITO se ignora. */
        Long accountId,
        String paymentMethod,
        @NotEmpty @Valid List<SaleDetailRequest> details
) {
}
