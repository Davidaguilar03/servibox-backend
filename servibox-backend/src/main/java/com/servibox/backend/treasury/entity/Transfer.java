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
 * Movimiento de dinero entre dos cuentas del mismo tenant: debita el origen y acredita el
 * destino por el mismo monto, asi que el balance global no cambia.
 *
 * Autollantas llama a estas relaciones sourceAccount / destinationAccount; aqui se llaman
 * originAccount / destinationAccount, que es como aparecen en la vista (columnas
 * Origin / Destination de tablaTransferencias).
 */
@Entity
@Data
@EqualsAndHashCode(callSuper = true)
@Table(name = "TRANSFERENCIAS")
public class Transfer extends TenantAwareEntity {

    @ManyToOne
    @JoinColumn(name = "id_cuenta_origen")
    private Account originAccount;

    @ManyToOne
    @JoinColumn(name = "id_cuenta_destino")
    private Account destinationAccount;

    @Column(name = "monto_transferencia")
    private Double amount;

    @Column(name = "concepto_transferencia")
    private String concept;

    @Column(name = "fecha_transferencia")
    private LocalDate date;
}
