package com.servibox.backend.inventory.entity;

import com.servibox.backend.shared.TenantAwareEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Entity
@Data
@EqualsAndHashCode(callSuper = true)
@Table(name = "TIPOS_IMPUESTO")
public class TaxType extends TenantAwareEntity {

    @Column(name = "nombre_impuesto")
    private String name;

    @Column(name = "tasa_impuesto")
    private Double rate;

    @Column(name = "descripcion_impuesto")
    private String description;

    @Column(name = "aplica_transaccion")
    private Boolean appliesToTransaction;

    /** IVA recuperable. Solo estos entran en el taxAmount del producto. */
    @Column(name = "es_iva")
    private Boolean isVat = Boolean.FALSE;
}
