package com.servibox.backend.sales.entity;

import com.servibox.backend.shared.TenantAwareEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * Cliente al que se le factura. En Autollantas es una entidad propia (tabla CLIENTES), no
 * campos sueltos dentro de la venta, y aqui se conserva asi.
 *
 * El documento es unico dentro de un tenant, no globalmente: el mismo NIT puede ser
 * cliente de dos talleres distintos. Mismo criterio que (tenant_id, username) en User,
 * (tenant_id, codigo_producto) en Product y (tenant_id, nombre_cuenta) en Account.
 */
@Entity
@Data
@EqualsAndHashCode(callSuper = true)
@Table(
        name = "CLIENTES",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_cliente_tenant_document",
                columnNames = {"tenant_id", "numero_documento_cliente"}
        )
)
public class Customer extends TenantAwareEntity {

    @Column(name = "nombre_cliente")
    private String name;

    /**
     * Nullable: Autollantas permite facturar a un cliente sin documento. La restriccion
     * unique no lo estorba porque en SQL varios NULL no chocan entre si.
     */
    @Column(name = "numero_documento_cliente")
    private String document;

    @Column(name = "correo_cliente")
    private String email;

    @Column(name = "celular_cliente")
    private String phone;
}
