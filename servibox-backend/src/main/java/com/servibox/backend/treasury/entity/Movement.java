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

    /**
     * Que clase de cosa genero este movimiento, o null si es un movimiento suelto
     * registrado a mano.
     *
     * Junto con sourceId reemplaza a las cuatro relaciones ManyToOne opcionales
     * (sourceTransfer, sourceSale, sourcePurchase, sourceOccasionalIncome) que habia
     * antes. Ver 03-DECISIONS.md.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "tipo_origen_movimiento")
    private MovementSourceType sourceType;

    /**
     * Id del registro que genero el movimiento, interpretado segun sourceType. Null en un
     * movimiento suelto, y siempre acompanado de sourceType cuando no lo es.
     *
     * **No es una clave foranea a proposito**: apunta a tablas distintas segun el tipo. La
     * seguridad de tipos no se pierde porque nadie escribe un Movement con un id suelto:
     * todo pasa por metodos de TreasuryService que reciben la entidad real.
     */
    @Column(name = "id_origen_movimiento")
    private Long sourceId;
}
