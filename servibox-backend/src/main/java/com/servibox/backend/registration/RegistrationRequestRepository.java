package com.servibox.backend.registration;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface RegistrationRequestRepository extends JpaRepository<RegistrationRequest, String> {

    Optional<RegistrationRequest> findByIdempotencyKey(String idempotencyKey);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from RegistrationRequest r where r.reservedSlug = :slug")
    Optional<RegistrationRequest> lockByReservedSlug(@Param("slug") String slug);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from RegistrationRequest r where r.id = :id")
    Optional<RegistrationRequest> lockById(@Param("id") String id);

    Page<RegistrationRequest> findByStatus(RegistrationRequestStatus status, Pageable pageable);

    long countByNitAndIdNot(String nit, String id);

    long countByNormalizedEmailAndIdNot(String normalizedEmail, String id);
}
