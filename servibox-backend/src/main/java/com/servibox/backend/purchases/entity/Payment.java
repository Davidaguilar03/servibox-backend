package com.servibox.backend.purchases.entity;

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
 * Un pago a una factura de compra a credito. En el negocio se llama **pago**, no abono:
 * abono es lo que recibe el taller de un cliente. Ver 02-CONVENTIONS.md.
 *
 * En Autollantas esta clase vive en treasury; aqui vive en purchases, junto a la factura.
 */
@Entity
@Data
@EqualsAndHashCode(callSuper = true)
@Table(name = "PAGOS")
public class Payment extends TenantAwareEntity {

    @ManyToOne
    @JoinColumn(name = "id_compra")
    private Purchase purchase;

    @ManyToOne
    @JoinColumn(name = "id_cuenta")
    private Account account;

    @Column(name = "valor_pago")
    private Double amount;

    @Column(name = "fecha_pago")
    private LocalDate date;
}
