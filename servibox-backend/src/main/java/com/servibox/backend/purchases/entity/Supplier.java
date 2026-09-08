package com.servibox.backend.purchases.entity;

import com.servibox.backend.shared.TenantAwareEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * Proveedor al que se le compra. El NIT es unico dentro de un tenant, no globalmente.
 *
 * Autollantas no tiene `businessName` como campo aparte: su formulario tiene un solo campo
 * rotulado "Nombre/razon social" que va a `nombre_proveedor`. Ver 03-DECISIONS.md.
 */
@Entity
@Data
@EqualsAndHashCode(callSuper = true)
@Table(
        name = "PROVEEDORES",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_proveedor_tenant_document",
                columnNames = {"tenant_id", "numero_nit_proveedor"}
        )
)
public class Supplier extends TenantAwareEntity {

    @Column(name = "nombre_proveedor")
    private String name;

    @Column(name = "razon_social_proveedor")
    private String businessName;

    /** NIT. Nullable, y en SQL varios NULL no chocan con la restriccion unique. */
    @Column(name = "numero_nit_proveedor")
    private String document;

    @Column(name = "correo_proveedor")
    private String email;

    @Column(name = "celular_proveedor")
    private String phone;
}
