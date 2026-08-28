package com.servibox.backend.inventory.entity;

import com.servibox.backend.shared.TenantAwareEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * El codigo es unico dentro de un tenant, no globalmente: dos talleres distintos pueden
 * usar cada uno el codigo "LLA-001" para productos que no tienen nada que ver. Mismo
 * criterio que (tenant_id, username) en User.
 */
@Entity
@Data
@EqualsAndHashCode(callSuper = true)
@Table(
        name = "PRODUCTOS",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_producto_tenant_code",
                columnNames = {"tenant_id", "codigo_producto"}
        )
)
public class Product extends TenantAwareEntity {

    @Column(name = "codigo_producto")
    private String code;

    @Column(name = "descripcion_producto")
    private String description;

    /** Costo sin IVA. */
    @Column(name = "costo_compra")
    private Double purchaseCost;

    /** Nivel de stock. Se llama quantity, NO stock, igual que en Autollantas. */
    @Column(name = "cantidad")
    private Integer quantity;

    @Column(name = "iva_producto")
    private Double taxAmount;

    @Column(name = "precio_sugerido")
    private Double suggestedPrice;

    @ManyToOne
    @JoinColumn(name = "id_categoria_producto")
    private ProductCategory category;
}
