package com.servibox.backend.sales.entity;

import com.servibox.backend.shared.TenantAwareEntity;
import com.servibox.backend.treasury.entity.Account;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDate;

/**
 * Un abono a una factura a credito. En el negocio se llama **abono**, no pago ni cobro,
 * ver 02-CONVENTIONS.md.
 *
 * En Autollantas esta clase vive en el paquete treasury; aqui vive en sales, que es donde
 * esta la factura a la que pertenece.
 */
@Entity
@Data
@EqualsAndHashCode(callSuper = true)
@Table(name = "RECAUDOS")
public class Collection extends TenantAwareEntity {

    @ManyToOne
    @JoinColumn(name = "id_venta")
    private Sale sale;

    @ManyToOne
    @JoinColumn(name = "id_cuenta")
    private Account account;

    @Column(name = "valor_recaudo")
    private Double amount;

    @Column(name = "fecha_recaudo")
    private LocalDate date;
}
