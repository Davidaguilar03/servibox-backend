package com.servibox.backend.sales.entity;

import com.servibox.backend.inventory.entity.Product;
import com.servibox.backend.shared.TenantAwareEntity;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.CheckConstraint;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** Una linea de la factura. */
@Entity
@Data
@EqualsAndHashCode(callSuper = true)
@AttributeOverride(name = "id", column = @Column(name = "id_detalle_venta"))
@Table(name = "DETALLE_VENTAS", check = @CheckConstraint(name = "ck_detalle_venta_cantidad", constraint = "cantidad_venta > 0"))
public class SaleDetail extends TenantAwareEntity {

    @ManyToOne
    @JoinColumn(name = "id_venta", nullable = false)
    private Sale sale;

    @ManyToOne
    @JoinColumn(name = "id_producto", nullable = false)
    private Product product;

    @Column(name = "cantidad_venta", nullable = false)
    private Integer quantity;

    /** Precio unitario sin IVA al que se vendio. */
    @Column(name = "precio_venta", nullable = false)
    private Double price;

    /**
     * IVA generado por la linea, congelado al momento de facturar: price * cantidad *
     * tasa de IVA vigente entonces. **No se recalcula despues.** Cambiarle el IVA a un
     * producto no puede mover el IVA de una factura ya emitida, porque esa factura ya se
     * entrego y se declaro. Es el mismo campo iva_generado_linea de Autollantas.
     */
    @Column(name = "iva_generado_linea", nullable = false)
    private Double ivaAmount;

    /**
     * IVA neto a pagar de la linea: ivaAmount menos el IVA descontable de esas unidades
     * (producto.taxAmount * cantidad), congelado al facturar igual que ivaAmount. El
     * descontable se deduce como ivaAmount - ivaDifference. Lo usa el Reporte de IVA para
     * repartir el descontable por categoria. Es el diferencia_iva_linea de Autollantas.
     */
    @Column(name = "diferencia_iva_linea", nullable = false)
    private Double ivaDifference;
}
