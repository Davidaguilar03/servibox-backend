package com.servibox.backend.sales.entity;

import com.servibox.backend.counterparties.entity.Counterparty;
import com.servibox.backend.shared.TenantAwareEntity;
import jakarta.persistence.AttributeOverride;
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
 *
 * No guarda cuenta: la cuenta de cada cobro vive en su movimiento RECAUDO_VENTA (el del
 * contado o el de un abono), que apunta a esta venta por id_venta.
 */
@Entity
@Data
@EqualsAndHashCode(callSuper = true)
@AttributeOverride(name = "id", column = @Column(name = "id_venta"))
@Table(
        name = "VENTAS",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_venta_tenant_invoice_number",
                columnNames = {"id_tenant", "numero_factura_venta"}
        )
)
public class Sale extends TenantAwareEntity {

    @Column(name = "numero_factura_venta", length = 50, nullable = false)
    private String invoiceNumber;

    /**
     * El cliente: un tercero CLIENTE o AMBOS. Obligatorio (NOT NULL): una venta sin
     * cliente identificado usa el Consumidor final del tenant.
     */
    @ManyToOne
    @JoinColumn(name = "id_tercero", nullable = false)
    private Counterparty customer;

    @Column(name = "fecha_venta", nullable = false)
    private LocalDate invoiceDate;

    @Column(name = "fecha_vencimiento_venta")
    private LocalDate dueDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "forma_pago_venta", length = 20, nullable = false)
    private PaymentType paymentType;

    @Column(name = "medio_pago_venta", length = 30)
    private String paymentMethod;

    @Enumerated(EnumType.STRING)
    @Column(name = "estado_venta", length = 20, nullable = false)
    private SaleStatus status;

    /** Suma de precio por cantidad de las lineas, sin IVA. */
    @Column(name = "subtotal_venta", nullable = false)
    private Double subtotal;

    /** IVA generado por la venta menos el IVA descontable de los productos vendidos. */
    @Column(name = "iva_por_pagar_venta", nullable = false)
    private Double ivaPorPagar;

    /** subtotal mas el IVA generado. Es lo que paga el cliente. */
    @Column(name = "total_venta", nullable = false)
    private Double total;
}
