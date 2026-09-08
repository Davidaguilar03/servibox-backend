package com.servibox.backend.treasury.repository;

import com.servibox.backend.treasury.entity.Account;
import org.springframework.data.jpa.repository.JpaRepository;

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
}
