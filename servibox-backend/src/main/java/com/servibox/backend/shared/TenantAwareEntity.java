package com.servibox.backend.shared;

import jakarta.persistence.Column;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.PrePersist;
import com.servibox.backend.tenant.TenantContext;
import lombok.Data;
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
 */
@MappedSuperclass
@Data
@EntityListeners(AuditingEntityListener.class)
@FilterDef(
        name = TenantAwareEntity.TENANT_FILTER,
        parameters = @ParamDef(name = TenantAwareEntity.TENANT_PARAM, type = Long.class)
)
@Filter(name = TenantAwareEntity.TENANT_FILTER, condition = "tenant_id = :tenantId")
public abstract class TenantAwareEntity {

    public static final String TENANT_FILTER = "tenantFilter";
    public static final String TENANT_PARAM = "tenantId";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private Long tenantId;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    /**
     * Asigna el tenant activo antes de insertar. Ninguna entidad de negocio debe fijar
     * tenantId a mano: si el contexto no tiene tenant, la insercion falla de una vez en
     * vez de dejar una fila huerfana con tenant_id nulo.
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
