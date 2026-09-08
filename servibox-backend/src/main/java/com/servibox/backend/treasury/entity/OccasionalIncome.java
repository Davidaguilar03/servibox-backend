package com.servibox.backend.treasury.entity;

import com.servibox.backend.shared.TenantAwareEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDate;

/**
 * Ingreso puntual que no viene de una venta: reintegros, venta de chatarra, un aporte del
 * socio. Al registrarse suma al saldo de su cuenta como cualquier otro INGRESO.
 *
 * Autollantas tiene ademas un campo `notes` que aqui no se porto, ver 03-DECISIONS.md.
 */
@Entity
@Data
@EqualsAndHashCode(callSuper = true)
@Table(name = "INGRESOS_OCASIONALES")
public class OccasionalIncome extends TenantAwareEntity {

    @Column(name = "concepto_ingreso")
    private String concept;

    @Column(name = "monto_ingreso")
    private Double amount;

    @ManyToOne
    @JoinColumn(name = "id_cuenta")
    private Account account;

    /** Fecha de negocio, no de auditoria. */
    @Column(name = "fecha_ingreso")
    private LocalDate date;
}
