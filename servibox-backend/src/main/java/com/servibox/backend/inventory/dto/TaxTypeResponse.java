package com.servibox.backend.inventory.dto;

import com.servibox.backend.inventory.entity.TaxType;

public record TaxTypeResponse(Long id, String name, Double rate, Boolean isVat) {

    public static TaxTypeResponse from(TaxType tax) {
        return new TaxTypeResponse(tax.getId(), tax.getName(), tax.getRate(), tax.getIsVat());
    }
}
