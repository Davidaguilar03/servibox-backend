package com.servibox.backend.inventory.entity;

import com.servibox.backend.shared.TenantAwareEntity;
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

import java.util.ArrayList;
import java.util.List;

@Entity
@Data
@EqualsAndHashCode(callSuper = true)
@Table(name = "CATEGORIA_PRODUCTOS")
public class ProductCategory extends TenantAwareEntity {

    @Column(name = "nombre_categoria_producto")
    private String name;

    @Column(name = "color_categoria")
    private String color;

    @Column(name = "stock_min_amarillo")
    private Integer yellowStockMin;

    @Column(name = "stock_min_rojo")
    private Integer redStockMin;

    /** Fraccion, no porcentaje: 0.30 es 30 por ciento. */
    @Column(name = "margen_utilidad")
    private Double targetMargin;

    @ManyToMany(fetch = FetchType.EAGER)
    @JoinTable(
            name = "CATEGORIA_IMPUESTOS",
            joinColumns = @JoinColumn(name = "id_categoria_producto"),
            inverseJoinColumns = @JoinColumn(name = "id_impuesto")
    )
    @ToString.Exclude
    private List<TaxType> taxTypes = new ArrayList<>();
}
