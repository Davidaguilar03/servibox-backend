package com.servibox.backend.treasury.entity;

import com.servibox.backend.shared.TenantAwareEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDate;

/**
 * Un ingreso o egreso sobre una cuenta. Cada movimiento ya viene aplicado al
 * currentBalance de su cuenta, ver TreasuryService.registrarMovimiento.
 *
 * concept es una divergencia deliberada respecto de Autollantas, donde MOVIMIENTOS no
 * tiene columna de concepto y la descripcion se deriva de (tabla_origen, id_origen).
 * Ver 03-DECISIONS.md.
 */
@Entity
@Data
@EqualsAndHashCode(callSuper = true)
@Table(name = "MOVIMIENTOS")
public class Movement extends TenantAwareEntity {

    @ManyToOne
    @JoinColumn(name = "id_cuenta")
    private Account account;

    @Enumerated(EnumType.STRING)
    @Column(name = "tipo_movimiento")
    private MovementType type;

    @Column(name = "concepto_movimiento")
    private String concept;

    @Column(name = "monto_movimiento")
    private Double amount;

    /** Fecha de negocio, no de auditoria. createdAt de la clase base es otra cosa. */
    @Column(name = "fecha_movimiento")
    private LocalDate date;
}
