package com.servibox.backend.inventory.entity;

import com.servibox.backend.shared.TenantAwareEntity;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.Table;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.HashSet;
import java.util.Set;

@Entity
@Data
@EqualsAndHashCode(callSuper = true)
@AttributeOverride(name = "id", column = @Column(name = "id_categoria_producto"))
@Table(name = "CATEGORIA_PRODUCTOS")
public class ProductCategory extends TenantAwareEntity {

    @Column(name = "nombre_categoria_producto", length = 100, nullable = false)
    private String name;

    @Column(name = "color_categoria", length = 20, nullable = false)
    private String color;

    @Column(name = "stock_min_amarillo", nullable = false)
    private Integer yellowStockMin;

    @Column(name = "stock_min_rojo", nullable = false)
    private Integer redStockMin;

    /** Fraccion, no porcentaje: 0.30 es 30 por ciento. */
    @JdbcTypeCode(SqlTypes.NUMERIC)
    @Column(name = "margen_utilidad", precision = 8, scale = 6, nullable = false)
    private Double targetMargin;

    /**
     * Tabla puente CATEGORIA_IMPUESTOS. Es un Set para que Hibernate le de la clave
     * primaria compuesta (id_categoria_producto, id_impuesto) del diccionario: con una
     * List sin orden la tabla queda sin PK y admite parejas repetidas.
     */
    @ManyToMany(fetch = FetchType.EAGER)
    @JoinTable(
            name = "CATEGORIA_IMPUESTOS",
            joinColumns = @JoinColumn(name = "id_categoria_producto"),
            inverseJoinColumns = @JoinColumn(name = "id_impuesto")
    )
    @ToString.Exclude
    private Set<TaxType> taxTypes = new HashSet<>();
}
