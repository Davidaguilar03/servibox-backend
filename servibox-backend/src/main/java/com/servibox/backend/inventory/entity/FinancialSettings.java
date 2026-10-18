package com.servibox.backend.inventory.entity;

import com.servibox.backend.shared.TenantAwareEntity;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Parametros del calculo de precio sugerido. En Autollantas es un singleton con id 1;
 * aqui es **una fila por tenant** (la relacion 1 a 1 del diccionario), porque cada negocio
 * tiene sus propios porcentajes. uk_configuracion_tenant lo garantiza en la base e
 * InventoryService.saveFinancialSettings lo valida antes con un mensaje legible.
 *
 * Todas las tasas son fracciones (0.05 es 5 por ciento), salvo icaRate, que es por mil.
 */
@Entity
@Data
@EqualsAndHashCode(callSuper = true)
@AttributeOverride(name = "id", column = @Column(name = "id_configuracion"))
@Table(
        name = "CONFIGURACION_FINANCIERA",
        uniqueConstraints = @UniqueConstraint(name = "uk_configuracion_tenant", columnNames = "id_tenant")
)
public class FinancialSettings extends TenantAwareEntity {

    @JdbcTypeCode(SqlTypes.NUMERIC)
    @Column(name = "porcentaje_gastos", precision = 8, scale = 6, nullable = false)
    private Double expensesRate;

    @JdbcTypeCode(SqlTypes.NUMERIC)
    @Column(name = "porcentaje_dian", precision = 8, scale = 6, nullable = false)
    private Double dianRate;

    /** Fraccion por mil, no porcentaje normal: 0.0082 es 8.2 por mil. */
    @JdbcTypeCode(SqlTypes.NUMERIC)
    @Column(name = "porcentaje_ica", precision = 8, scale = 6, nullable = false)
    private Double icaRate;

    /**
     * Comision del datafono sobre los pagos con tarjeta, como fraccion. Obligatoria: 0.0
     * si el negocio no la configura. Todavia no entra en ningun calculo.
     */
    @JdbcTypeCode(SqlTypes.NUMERIC)
    @Column(name = "porcentaje_comision_tarjeta", precision = 8, scale = 6, nullable = false)
    private Double cardCommissionRate = 0.0;
}
