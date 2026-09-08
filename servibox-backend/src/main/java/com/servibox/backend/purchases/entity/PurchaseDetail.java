package com.servibox.backend.purchases.entity;

import com.servibox.backend.inventory.entity.Product;
import com.servibox.backend.shared.TenantAwareEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** Una linea de la factura de compra. */
@Entity
@Data
@EqualsAndHashCode(callSuper = true)
@Table(name = "DETALLE_COMPRAS")
public class PurchaseDetail extends TenantAwareEntity {

    @ManyToOne
    @JoinColumn(name = "id_compra")
    private Purchase purchase;

    @ManyToOne
    @JoinColumn(name = "id_producto")
    private Product product;

    @Column(name = "cantidad_compra")
    private Integer quantity;

    /** Costo de compra unitario, sin IVA. */
    @Column(name = "precio_compra")
    private Double price;

    /**
     * IVA de la linea, congelado al facturar igual que en SaleDetail. Autollantas guarda
     * en `impuesto_compra` el IVA **por unidad**; aqui es el de la linea completa, para que
     * sea el mismo criterio que en ventas. Ver 03-DECISIONS.md.
     */
    @Column(name = "iva_linea_compra")
    private Double ivaAmount;
}
