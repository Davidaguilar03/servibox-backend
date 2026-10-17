package com.servibox.backend.treasury.entity;

import com.servibox.backend.purchases.entity.Purchase;
import com.servibox.backend.sales.entity.Sale;
import com.servibox.backend.shared.TenantAwareEntity;
import jakarta.persistence.CheckConstraint;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

import java.time.LocalDate;

/**
 * El libro de movimientos de tesoreria: todo ingreso o egreso sobre una cuenta, ya
 * aplicado a su currentBalance. Gastos operativos, ingresos ocasionales y transferencias
 * no tienen tabla propia: son movimientos con su `category`. Ver 04-DATA-MODEL.md,
 * MOVIMIENTOS.
 *
 * concept es una divergencia deliberada respecto de Autollantas, donde MOVIMIENTOS no
 * tiene columna de concepto y la descripcion se deriva de (tabla_origen, id_origen).
 * Ver 03-DECISIONS.md.
 *
 * Un movimiento enlazado a una factura ANULADA es **inactivo**: sigue en la tabla, pero no
 * sale en listados ni cuenta en sumas ni en el saldo. Se deriva del estado de la factura,
 * ver MovementSpecifications.activo().
 *
 * ck_movimiento_factura: RECAUDO_VENTA lleva venta y no compra, PAGO_COMPRA lleva compra y
 * no venta, y las demas categorias no llevan ninguna. TreasuryService lo valida antes.
 * ck_movimiento_monto: el monto siempre es positivo; el signo lo da `type`.
 */
@Entity
@Data
@EqualsAndHashCode(callSuper = true)
@AttributeOverride(name = "id", column = @Column(name = "id_movimiento"))
@Table(name = "MOVIMIENTOS", check = {@CheckConstraint(
        name = "ck_movimiento_factura",
        constraint = "(categoria_movimiento = 'RECAUDO_VENTA' AND id_venta IS NOT NULL AND id_compra IS NULL)"
                + " OR (categoria_movimiento = 'PAGO_COMPRA' AND id_compra IS NOT NULL AND id_venta IS NULL)"
                + " OR (categoria_movimiento NOT IN ('RECAUDO_VENTA', 'PAGO_COMPRA')"
                + " AND id_venta IS NULL AND id_compra IS NULL)"),
        @CheckConstraint(name = "ck_movimiento_monto", constraint = "monto > 0")})
public class Movement extends TenantAwareEntity {

    @ManyToOne
    @JoinColumn(name = "id_cuenta", nullable = false)
    private Account account;

    /** Solo INGRESO o EGRESO: es lo que da el signo sobre el saldo. */
    @Enumerated(EnumType.STRING)
    @Column(name = "tipo_movimiento", length = 20, nullable = false)
    private MovementType type;

    /** De donde viene el movimiento. TRANSFERENCIA es una categoria, no un tipo. */
    @Enumerated(EnumType.STRING)
    @Column(name = "categoria_movimiento", length = 50, nullable = false)
    private MovementCategory category;

    @Column(name = "concepto", length = 255, nullable = false)
    private String concept;

    @Column(name = "monto", nullable = false)
    private Double amount;

    /** Fecha de negocio, no de auditoria. createdAt de la clase base es otra cosa. */
    @Column(name = "fecha_movimiento", nullable = false)
    private LocalDate date;

    /** Observaciones libres. Opcional. */
    @Column(name = "notas", length = 255)
    private String notes;

    /**
     * Contrapartida de una transferencia: la pata INGRESO apunta a la pata EGRESO. Null en
     * cualquier otro movimiento. Excluido de toString y equals: es una autorreferencia.
     */
    @ManyToOne
    @JoinColumn(name = "id_movimiento_relacionado")
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private Movement relatedMovement;

    /**
     * Venta de un RECAUDO_VENTA (el contado o un abono). Null en cualquier otra categoria.
     * Clave foranea real y sin cascada: un movimiento nunca se borra con su factura.
     */
    @ManyToOne
    @JoinColumn(name = "id_venta", foreignKey = @ForeignKey(name = "fk_movimiento_venta"))
    private Sale sale;

    /** Compra de un PAGO_COMPRA (el contado o un pago). Null en cualquier otra categoria. */
    @ManyToOne
    @JoinColumn(name = "id_compra", foreignKey = @ForeignKey(name = "fk_movimiento_compra"))
    private Purchase purchase;
}
