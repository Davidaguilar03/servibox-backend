package com.servibox.backend.purchases.dto;

import com.servibox.backend.purchases.entity.PaymentType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;
import java.util.List;

public record PurchaseRequest(
        @NotBlank String invoiceNumber,
        Long supplierId,
        LocalDate invoiceDate,
        LocalDate dueDate,
        @NotNull PaymentType paymentType,
        /** Obligatoria si paymentType es CONTADO; en CREDITO se ignora. */
        Long accountId,
        String paymentMethod,
        @NotEmpty @Valid List<PurchaseDetailRequest> details
) {
}
