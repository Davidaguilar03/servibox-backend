package com.servibox.backend.counterparties.repository;

import com.servibox.backend.counterparties.entity.Counterparty;
import com.servibox.backend.counterparties.entity.CounterpartyRole;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface CounterpartyRepository extends JpaRepository<Counterparty, Long> {

    /**
     * Nunca usar el findById heredado sobre una entidad TenantAware: el @Filter de
     * Hibernate no se aplica a EntityManager.find() y devuelve filas de otros tenants.
     * Ver 02-CONVENTIONS.md.
     */
    Optional<Counterparty> findByIdAndTenantId(Long id, Long tenantId);

    /**
     * findByIdAndTenantId con bloqueo pesimista de escritura (SELECT ... FOR UPDATE): la fila
     * queda bloqueada hasta que termina la transaccion. No refresca una entidad que ya estaba
     * en el contexto de persistencia; quien la usa la refresca. Ver Bloqueos en
     * 02-CONVENTIONS.md.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT c FROM Counterparty c WHERE c.id = :id AND c.tenantId = :tenantId")
    Optional<Counterparty> findByIdAndTenantIdForUpdate(Long id, Long tenantId);

    Optional<Counterparty> findByDocumentNumberAndTenantId(String documentNumber, Long tenantId);

    List<Counterparty> findByTenantIdOrderByNameAsc(Long tenantId);

    List<Counterparty> findByTenantIdAndRoleInOrderByNameAsc(Long tenantId, Collection<CounterpartyRole> roles);
}
