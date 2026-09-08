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
 * Gasto puntual del taller que no viene de una factura de compra: arriendo, servicios,
 * papeleria. Es la imagen espejo de {@link OccasionalIncome}: al registrarse **resta** del
 * saldo de su cuenta como cualquier otro EGRESO.
 *
 * A diferencia del ingreso ocasional, aqui si se porto `notes`: en Autollantas el
 * formulario tiene un campo "Observaciones" que va a esta columna y **no esta entre los
 * campos obligatorios** (validateFields solo exige concepto, monto, cuenta y fecha).
 * Ver 03-DECISIONS.md.
 */
@Entity
@Data
@EqualsAndHashCode(callSuper = true)
@Table(name = "GASTOS_OPERATIVOS")
public class OperationalExpense extends TenantAwareEntity {

    @Column(name = "concepto_gasto")
    private String concept;

    @Column(name = "monto_gasto")
    private Double amount;

    @ManyToOne
    @JoinColumn(name = "id_cuenta")
    private Account account;

    /** Fecha de negocio, no de auditoria. */
    @Column(name = "fecha_gasto")
    private LocalDate date;

    /** Observaciones libres. Opcional, igual que en Autollantas. */
    @Column(name = "notes")
    private String notes;
}
