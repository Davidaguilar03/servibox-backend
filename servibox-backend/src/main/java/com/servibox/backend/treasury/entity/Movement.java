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
 */
@Entity
@Data
@EqualsAndHashCode(callSuper = true)
@Table(name = "MOVIMIENTOS")
public class Movement extends TenantAwareEntity {

    @ManyToOne
    @JoinColumn(name = "id_cuenta")
    private Account account;

    /** Solo INGRESO o EGRESO: es lo que da el signo sobre el saldo. */
    @Enumerated(EnumType.STRING)
    @Column(name = "tipo_movimiento")
    private MovementType type;

    /** De donde viene el movimiento. TRANSFERENCIA es una categoria, no un tipo. */
    @Enumerated(EnumType.STRING)
    @Column(name = "categoria_movimiento", length = 50, nullable = false)
    private MovementCategory category;

    @Column(name = "concepto_movimiento")
    private String concept;

    @Column(name = "monto_movimiento")
    private Double amount;

    /** Fecha de negocio, no de auditoria. createdAt de la clase base es otra cosa. */
    @Column(name = "fecha_movimiento")
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
     * Factura que genero el movimiento (SALE o PURCHASE), o null. Desaparece en el paso 3,
     * ver MovementSourceType.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "tipo_origen_movimiento")
    private MovementSourceType sourceType;

    /**
     * Id de la factura de origen, interpretado segun sourceType. **No es una clave
     * foranea a proposito**: apunta a tablas distintas segun el tipo.
     */
    @Column(name = "id_origen_movimiento")
    private Long sourceId;
}
