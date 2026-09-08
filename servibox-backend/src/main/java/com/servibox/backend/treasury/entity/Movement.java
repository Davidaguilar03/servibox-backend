package com.servibox.backend.treasury.entity;

import com.servibox.backend.sales.entity.Sale;
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

    /**
     * La transferencia que genero este movimiento, o null si el movimiento es suelto
     * (un ingreso o egreso registrado a mano).
     *
     * Es la version acotada del par (tabla_origen, id_origen) de Autollantas: alli el
     * origen es una referencia polimorfica a siete tablas resuelta con un switch sobre
     * nombres en String. Aqui, mientras el unico origen automatico sea la transferencia,
     * una relacion real con integridad referencial dice lo mismo y la base la puede
     * validar. Cuando existan Sales y Purchases habra que decidir como se generaliza, ver
     * 03-DECISIONS.md.
     */
    @ManyToOne
    @JoinColumn(name = "id_transferencia_origen")
    private Transfer sourceTransfer;

    /**
     * La venta que genero este movimiento, o null si no viene de una venta. Hermana de
     * sourceTransfer: entre las dos cubren los dos origenes automaticos que hoy existen.
     *
     * Que treasury conozca a sales no es ideal, pero es la misma direccion de dependencia
     * que ya tiene Autollantas (su Collection, en treasury, referencia Sale) y evita
     * volver al par polimorfico (tabla_origen, id_origen) sin integridad referencial.
     * Ver 03-DECISIONS.md.
     */
    @ManyToOne
    @JoinColumn(name = "id_venta_origen")
    private Sale sourceSale;
}
