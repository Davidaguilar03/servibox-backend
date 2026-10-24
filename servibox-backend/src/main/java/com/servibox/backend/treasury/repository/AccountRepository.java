package com.servibox.backend.treasury.repository;

import com.servibox.backend.treasury.entity.Account;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;

public interface AccountRepository extends JpaRepository<Account, Long> {

    Optional<Account> findByNameAndTenantId(String name, Long tenantId);

    /**
     * Busqueda por id que si respeta el tenant. El @Filter de Hibernate NO se aplica a
     * EntityManager.find(), asi que el findById heredado de JpaRepository devuelve la
     * cuenta aunque sea de otro tenant. Las consultas derivadas como esta si pasan por el
     * filtro, y ademas el tenant va explicito en el where.
     */
    Optional<Account> findByIdAndTenantId(Long id, Long tenantId);

    /**
     * findByIdAndTenantId con bloqueo pesimista de escritura (SELECT ... FOR UPDATE): la fila
     * queda bloqueada hasta que termina la transaccion. No refresca una entidad que ya estaba
     * en el contexto de persistencia; quien la usa la refresca. Ver Bloqueos en
     * 02-CONVENTIONS.md.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT a FROM Account a WHERE a.id = :id AND a.tenantId = :tenantId")
    Optional<Account> findByIdAndTenantIdForUpdate(Long id, Long tenantId);
}
