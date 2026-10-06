package com.servibox.backend.counterparties.entity;

import com.servibox.backend.shared.TenantAwareEntity;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * Tercero: persona o empresa con la que el taller comercia. Unifica el cliente de una
 * venta y el proveedor de una compra; cual de los dos es lo dice `role`. Existe una sola
 * vez por tenant, identificado por su numero de documento. Ver 04-DATA-MODEL.md, TERCEROS.
 *
 * La PK usa la columna del diccionario (`id_tercero`) con @AttributeOverride sobre el `id`
 * de TenantAwareEntity. El tenant se queda en `tenant_id` y no en `id_tenant`: el @Filter
 * de la clase base tiene la columna escrita en su condicion SQL, y renombrarla aqui
 * romperia el aislamiento de esta tabla. Se alinea en el paso de Flyway.
 */
@Entity
@Data
@EqualsAndHashCode(callSuper = true)
@AttributeOverride(name = "id", column = @Column(name = "id_tercero"))
@Table(
        name = "TERCEROS",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_tercero_tenant_document",
                columnNames = {"tenant_id", "numero_documento"}
        )
)
public class Counterparty extends TenantAwareEntity {

    @Enumerated(EnumType.STRING)
    @Column(name = "tipo_tercero", length = 20, nullable = false)
    private CounterpartyRole role;

    @Column(name = "nombre_razon_social", length = 150, nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "tipo_documento", length = 20, nullable = false)
    private DocumentType documentType;

    @Column(name = "numero_documento", length = 25, nullable = false)
    private String documentNumber;

    @Column(name = "correo", length = 150)
    private String email;

    @Column(name = "celular", length = 25)
    private String phone;
}
