package com.servibox.backend.tenant;

import com.servibox.backend.shared.TenantAwareEntity;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.Session;
import org.springframework.orm.jpa.EntityManagerHolder;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Habilita el filtro de Hibernate sobre la sesion apenas arranca la transaccion, tomando
 * el tenant de TenantContext.
 *
 * Se engancha en la transaccion y no en un interceptor de Spring MVC a proposito: asi el
 * aislamiento tambien aplica fuera de un request HTTP (tests, tareas programadas, codigo
 * asincrono) y no depende de que spring.jpa.open-in-view siga activo. Todo acceso via
 * repositorios de Spring Data pasa por aqui, porque SimpleJpaRepository es transaccional.
 *
 * Nota: se probo antes envolver el EntityManagerFactory en un proxy para habilitar el
 * filtro al crear cada EntityManager. Eso rompe la gestion de transacciones de Spring
 * (los save quedan sin flush, id null, cero filas). No repetir ese camino.
 */
public class TenantAwareJpaTransactionManager extends JpaTransactionManager {

    public TenantAwareJpaTransactionManager(EntityManagerFactory entityManagerFactory) {
        super(entityManagerFactory);
    }

    @Override
    protected void doBegin(Object transaction, TransactionDefinition definition) {
        super.doBegin(transaction, definition);

        Long tenantId = TenantContext.getTenantId();
        if (tenantId == null) {
            return;
        }
        Object resource = TransactionSynchronizationManager.getResource(getEntityManagerFactory());
        if (!(resource instanceof EntityManagerHolder holder)) {
            return;
        }
        holder.getEntityManager()
                .unwrap(Session.class)
                .enableFilter(TenantAwareEntity.TENANT_FILTER)
                .setParameter(TenantAwareEntity.TENANT_PARAM, tenantId);
    }
}
