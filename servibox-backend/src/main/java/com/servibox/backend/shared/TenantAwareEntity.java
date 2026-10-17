package com.servibox.backend.shared;

import jakarta.persistence.Column;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.PrePersist;
import com.servibox.backend.tenant.Tenant;
import com.servibox.backend.tenant.TenantContext;
import lombok.AccessLevel;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.FilterDef;
import org.hibernate.annotations.ParamDef;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

/**
 * Base de toda entidad de negocio que pertenece a un tenant.
 * El filtro de Hibernate se habilita por sesion, ver TenantFilterConfiguration.
 *
 * Nombres fisicos del diccionario (04-DATA-MODEL.md): la columna de tenant es id_tenant y
 * la PK de cada entidad se renombra a id_<entidad> con @AttributeOverride sobre `id`.
 */
@MappedSuperclass
@Data
@EntityListeners(AuditingEntityListener.class)
@FilterDef(
        name = TenantAwareEntity.TENANT_FILTER,
        parameters = @ParamDef(name = TenantAwareEntity.TENANT_PARAM, type = Long.class)
)
@Filter(name = TenantAwareEntity.TENANT_FILTER, condition = "id_tenant = :tenantId")
public abstract class TenantAwareEntity {

    public static final String TENANT_FILTER = "tenantFilter";
    public static final String TENANT_PARAM = "tenantId";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "id_tenant", nullable = false, updatable = false)
    private Long tenantId;

    /**
     * Solo existe para que la base tenga la FK id_tenant -> TENANTS del diccionario. Es de
     * solo lectura y sin accesores: el tenant se escribe y se lee por tenantId.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "id_tenant", insertable = false, updatable = false)
    @Getter(AccessLevel.NONE)
    @Setter(AccessLevel.NONE)
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private Tenant tenant;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    /**
     * Asigna el tenant activo antes de insertar. Ninguna entidad de negocio debe fijar
     * tenantId a mano: si el contexto no tiene tenant, la insercion falla de una vez en
     * vez de dejar una fila huerfana con id_tenant nulo.
     */
    @PrePersist
    void asignarTenant() {
        Long activo = TenantContext.getTenantId();
        if (activo == null) {
            throw new IllegalStateException(
                    "No se puede persistir una entidad sin un tenant activo en el contexto");
        }
        this.tenantId = activo;
    }
}
