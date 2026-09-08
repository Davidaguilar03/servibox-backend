package com.servibox.backend.purchases.entity;

import com.servibox.backend.shared.TenantAwareEntity;
import com.servibox.backend.treasury.entity.Account;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDate;

/**
 * Factura de compra. Espejo de Sale, con el signo invertido: suma stock y saca dinero.
 *
 * El numero de factura es unico dentro de un tenant con restriccion de base, igual que en
 * Sale y a diferencia de Autollantas, que lo valida solo en la aplicacion.
 */
@Entity
@Data
@EqualsAndHashCode(callSuper = true)
@Table(
        name = "COMPRAS",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_compra_tenant_invoice_number",
                columnNames = {"tenant_id", "numero_factura_compra"}
        )
)
public class Purchase extends TenantAwareEntity {

    @Column(name = "numero_factura_compra")
    private String invoiceNumber;

    @ManyToOne
    @JoinColumn(name = "id_proveedor")
    private Supplier supplier;

    @Column(name = "fecha_compra")
    private LocalDate invoiceDate;

    @Column(name = "fecha_vencimiento_compra")
    private LocalDate dueDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "forma_pago_compra")
    private PaymentType paymentType;

    /** Nullable: una compra a credito no tiene cuenta hasta que se le paga. */
    @ManyToOne
    @JoinColumn(name = "id_cuenta")
    private Account account;

    @Column(name = "medio_pago_compra")
    private String paymentMethod;

    @Enumerated(EnumType.STRING)
    @Column(name = "estado_compra")
    private PurchaseStatus status;

    /** Suma de precio de compra por cantidad, sin IVA. */
    @Column(name = "subtotal_compra")
    private Double subtotal;

    /** IVA de la compra. Es IVA descontable: se recupera contra el IVA de las ventas. */
    @Column(name = "iva_total_compra")
    private Double ivaTotal;

    @Column(name = "total_compra")
    private Double total;
}
