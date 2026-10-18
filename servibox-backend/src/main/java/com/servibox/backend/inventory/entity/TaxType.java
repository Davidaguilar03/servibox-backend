package com.servibox.backend.inventory.entity;

import com.servibox.backend.shared.TenantAwareEntity;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Data
@EqualsAndHashCode(callSuper = true)
@AttributeOverride(name = "id", column = @Column(name = "id_impuesto"))
@Table(name = "TIPOS_IMPUESTO")
public class TaxType extends TenantAwareEntity {

    @Column(name = "nombre_impuesto", length = 100, nullable = false)
    private String name;

    @JdbcTypeCode(SqlTypes.NUMERIC)
    @Column(name = "tasa_impuesto", precision = 8, scale = 6, nullable = false)
    private Double rate;

    @Column(name = "descripcion_impuesto", length = 255)
    private String description;

    @Column(name = "aplica_transaccion", nullable = false)
    private Boolean appliesToTransaction;

    /** IVA recuperable. Solo estos entran en el taxAmount del producto. */
    @Column(name = "es_iva", nullable = false)
    private Boolean isVat = Boolean.FALSE;
}
