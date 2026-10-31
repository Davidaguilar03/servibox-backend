package com.servibox.backend.auth.repository;

import com.servibox.backend.auth.entity.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByUsernameAndTenantId(String username, Long tenantId);

    // Plataforma: sin TenantContext no hay filtro de Hibernate, asi que el tenant va explicito.

    Page<User> findByTenantId(Long tenantId, Pageable pageable);

    Optional<User> findByIdAndTenantId(Long id, Long tenantId);

    /** Filas [rol, activo, cantidad] de un tenant. */
    @Query("select u.role, u.active, count(u) from User u where u.tenantId = :tenantId"
            + " group by u.role, u.active order by u.role, u.active")
    List<Object[]> countByRoleAndActive(@Param("tenantId") Long tenantId);

    long countByActive(boolean active);

    /**
     * Invalida los JWT ya emitidos del usuario. Update directo: no toca updated_at, asi que
     * repetirlo devuelve el mismo usuario.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update User u set u.authVersion = u.authVersion + 1 where u.id = :id and u.tenantId = :tenantId")
    int revokeByIdAndTenantId(@Param("id") Long id, @Param("tenantId") Long tenantId);

    /** Invalida todos los JWT ya emitidos de los usuarios del tenant. Devuelve las filas tocadas. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update User u set u.authVersion = u.authVersion + 1 where u.tenantId = :tenantId")
    int revokeAllByTenantId(@Param("tenantId") Long tenantId);
}
