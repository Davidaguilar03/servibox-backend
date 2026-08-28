package com.servibox.backend.inventory.entity;

import com.servibox.backend.shared.TenantAwareEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * Parametros del calculo de precio sugerido. En Autollantas es un singleton con id 1;
 * aqui es una fila por tenant, porque cada negocio tiene sus propios porcentajes.
 */
@Entity
@Data
@EqualsAndHashCode(callSuper = true)
@Table(name = "CONFIGURACION_FINANCIERA")
public class FinancialSettings extends TenantAwareEntity {

    @Column(name = "porcentaje_gastos")
    private Double expensesRate;

    @Column(name = "porcentaje_dian")
    private Double dianRate;

    /** Fraccion por mil, no porcentaje normal: 0.0082 es 8.2 por mil. */
    @Column(name = "porcentaje_ica")
    private Double icaRate;

    @Column(name = "porcentaje_comision_tarjeta")
    private Double cardCommissionRate;
}
