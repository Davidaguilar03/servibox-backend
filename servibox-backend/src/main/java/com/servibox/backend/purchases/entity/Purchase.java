package com.servibox.backend.purchases.entity;

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
 * Factura de compra. Espejo de Sale, con el signo invertido: suma stock y saca dinero.
 *
 * El numero de factura es unico dentro de un tenant con restriccion de base, igual que en
 * Sale y a diferencia de Autollantas, que lo valida solo en la aplicacion.
 *
 * No guarda cuenta: la cuenta de cada pago vive en su movimiento PAGO_COMPRA, que apunta a
 * esta compra por id_compra.
 */
@Entity
@Data
@EqualsAndHashCode(callSuper = true)
@AttributeOverride(name = "id", column = @Column(name = "id_compra"))
@Table(
        name = "COMPRAS",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_compra_tenant_invoice_number",
                columnNames = {"id_tenant", "numero_factura_compra"}
        )
)
public class Purchase extends TenantAwareEntity {

    @Column(name = "numero_factura_compra", length = 50, nullable = false)
    private String invoiceNumber;

    /**
     * El proveedor: un tercero PROVEEDOR o AMBOS. Obligatorio (NOT NULL), sin proveedor
     * por defecto: el service lo exige antes con un 400 legible.
     */
    @ManyToOne
    @JoinColumn(name = "id_tercero", nullable = false)
    private Counterparty supplier;

    @Column(name = "fecha_compra", nullable = false)
    private LocalDate invoiceDate;

    @Column(name = "fecha_vencimiento_compra")
    private LocalDate dueDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "forma_pago_compra", length = 20, nullable = false)
    private PaymentType paymentType;

    @Column(name = "medio_pago_compra", length = 30)
    private String paymentMethod;

    @Enumerated(EnumType.STRING)
    @Column(name = "estado_compra", length = 20, nullable = false)
    private PurchaseStatus status;

    /** Suma de precio de compra por cantidad, sin IVA. */
    @Column(name = "subtotal_compra", nullable = false)
    private Double subtotal;

    /** IVA de la compra. Es IVA descontable: se recupera contra el IVA de las ventas. */
    @Column(name = "iva_total_compra", nullable = false)
    private Double ivaTotal;

    @Column(name = "total_compra", nullable = false)
    private Double total;
}
