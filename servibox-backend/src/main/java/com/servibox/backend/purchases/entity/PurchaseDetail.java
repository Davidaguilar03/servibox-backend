package com.servibox.backend.purchases.entity;

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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Una linea de la factura de compra. */
@Entity
@Data
@EqualsAndHashCode(callSuper = true)
@AttributeOverride(name = "id", column = @Column(name = "id_detalle_compra"))
@Table(name = "DETALLE_COMPRAS", check = @CheckConstraint(name = "ck_detalle_compra_cantidad", constraint = "cantidad_compra > 0"))
public class PurchaseDetail extends TenantAwareEntity {

    @ManyToOne
    @JoinColumn(name = "id_compra", nullable = false)
    private Purchase purchase;

    @ManyToOne
    @JoinColumn(name = "id_producto", nullable = false)
    private Product product;

    @Column(name = "cantidad_compra", nullable = false)
    private Integer quantity;

    /** Costo de compra unitario, sin IVA. */
    @JdbcTypeCode(SqlTypes.NUMERIC)
    @Column(name = "precio_compra", precision = 14, scale = 2, nullable = false)
    private Double price;

    /**
     * IVA de la linea, congelado al facturar igual que en SaleDetail. Autollantas guarda
     * en `impuesto_compra` el IVA **por unidad**; aqui es el de la linea completa, para que
     * sea el mismo criterio que en ventas. Ver 03-DECISIONS.md.
     */
    @JdbcTypeCode(SqlTypes.NUMERIC)
    @Column(name = "iva_linea_compra", precision = 14, scale = 2, nullable = false)
    private Double ivaAmount;
}
