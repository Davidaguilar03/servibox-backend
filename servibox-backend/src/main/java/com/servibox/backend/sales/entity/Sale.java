package com.servibox.backend.sales.entity;

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
 * Factura de venta. El numero de factura es unico dentro de un tenant y la restriccion
 * esta en la base, a diferencia de Autollantas que solo lo valida en la aplicacion.
 * Ver 03-DECISIONS.md.
 */
@Entity
@Data
@EqualsAndHashCode(callSuper = true)
@Table(
        name = "VENTAS",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_venta_tenant_invoice_number",
                columnNames = {"tenant_id", "numero_factura_venta"}
        )
)
public class Sale extends TenantAwareEntity {

    @Column(name = "numero_factura_venta")
    private String invoiceNumber;

    @ManyToOne
    @JoinColumn(name = "id_cliente")
    private Customer customer;

    @Column(name = "fecha_venta")
    private LocalDate invoiceDate;

    @Column(name = "fecha_vencimiento_venta")
    private LocalDate dueDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "forma_pago_venta")
    private PaymentType paymentType;

    /** Nullable: una factura a CREDITO todavia no tiene cuenta de cobro asignada. */
    @ManyToOne
    @JoinColumn(name = "id_cuenta")
    private Account account;

    @Column(name = "medio_pago_venta")
    private String paymentMethod;

    @Enumerated(EnumType.STRING)
    @Column(name = "estado_venta")
    private SaleStatus status;

    /** Suma de precio por cantidad de las lineas, sin IVA. */
    @Column(name = "subtotal_venta")
    private Double subtotal;

    /** IVA generado por la venta menos el IVA descontable de los productos vendidos. */
    @Column(name = "iva_por_pagar_venta")
    private Double ivaPorPagar;

    /** subtotal mas el IVA generado. Es lo que paga el cliente. */
    @Column(name = "total_venta")
    private Double total;
}
