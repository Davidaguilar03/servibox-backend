package com.servibox.backend.sales.entity;

import com.servibox.backend.inventory.entity.Product;
import com.servibox.backend.shared.TenantAwareEntity;
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
@Table(name = "DETALLE_VENTAS")
public class SaleDetail extends TenantAwareEntity {

    @ManyToOne
    @JoinColumn(name = "id_venta")
    private Sale sale;

    @ManyToOne
    @JoinColumn(name = "id_producto")
    private Product product;

    @Column(name = "cantidad_venta")
    private Integer quantity;

    /** Precio unitario sin IVA al que se vendio. */
    @Column(name = "precio_venta")
    private Double price;

    /**
     * IVA generado por la linea, congelado al momento de facturar: price * cantidad *
     * tasa de IVA vigente entonces. **No se recalcula despues.** Cambiarle el IVA a un
     * producto no puede mover el IVA de una factura ya emitida, porque esa factura ya se
     * entrego y se declaro. Es el mismo campo iva_generado_linea de Autollantas.
     */
    @Column(name = "iva_generado_linea")
    private Double ivaAmount;
}
