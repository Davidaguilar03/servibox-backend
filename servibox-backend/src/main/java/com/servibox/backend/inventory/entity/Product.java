package com.servibox.backend.inventory.entity;

import com.servibox.backend.shared.TenantAwareEntity;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * El codigo es unico dentro de un tenant, no globalmente: dos talleres distintos pueden
 * usar cada uno el codigo "LLA-001" para productos que no tienen nada que ver. Mismo
 * criterio que (id_tenant, username) en User.
 */
@Entity
@Data
@EqualsAndHashCode(callSuper = true)
@AttributeOverride(name = "id", column = @Column(name = "id_producto"))
@Table(
        name = "PRODUCTOS",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_producto_tenant_code",
                columnNames = {"id_tenant", "codigo_producto"}
        )
)
public class Product extends TenantAwareEntity {

    @Column(name = "codigo_producto", length = 50, nullable = false)
    private String code;

    @Column(name = "descripcion_producto", length = 255, nullable = false)
    private String description;

    /** Costo sin IVA. */
    @JdbcTypeCode(SqlTypes.NUMERIC)
    @Column(name = "costo_compra", precision = 14, scale = 2, nullable = false)
    private Double purchaseCost;

    /** Nivel de stock. Se llama quantity, NO stock, igual que en Autollantas. */
    @Column(name = "cantidad", nullable = false)
    private Integer quantity;

    @JdbcTypeCode(SqlTypes.NUMERIC)
    @Column(name = "iva_producto", precision = 14, scale = 2, nullable = false)
    private Double taxAmount;

    @JdbcTypeCode(SqlTypes.NUMERIC)
    @Column(name = "precio_sugerido", precision = 14, scale = 2, nullable = false)
    private Double suggestedPrice;

    @ManyToOne
    @JoinColumn(name = "id_categoria_producto", nullable = false)
    private ProductCategory category;
}
