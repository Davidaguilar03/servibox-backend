package com.servibox.backend.registration;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Optional;

public interface RegistrationRequestRepository extends JpaRepository<RegistrationRequest, String>,
        JpaSpecificationExecutor<RegistrationRequest> {

    Optional<RegistrationRequest> findByIdempotencyKey(String idempotencyKey);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from RegistrationRequest r where r.reservedSlug = :slug")
    Optional<RegistrationRequest> lockByReservedSlug(@Param("slug") String slug);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from RegistrationRequest r where r.id = :id")
    Optional<RegistrationRequest> lockById(@Param("id") String id);

    long countByNitAndIdNot(String nit, String id);

    long countByNormalizedEmailAndIdNot(String normalizedEmail, String id);

    /** Reserva vigente de una solicitud sobre el slug; la consulta el registro inmediato. */
    boolean existsByReservedSlugAndReservationExpiresAtAfter(String slug, LocalDateTime now);
}
