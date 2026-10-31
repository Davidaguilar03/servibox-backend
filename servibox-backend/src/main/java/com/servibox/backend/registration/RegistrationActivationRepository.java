package com.servibox.backend.registration;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface RegistrationActivationRepository extends JpaRepository<RegistrationActivation, Long> {

    /** Solo el id de la solicitud, sin cargar la activacion antes de bloquear la solicitud. */
    @Query("select a.requestId from RegistrationActivation a where a.tokenDigest = :digest")
    Optional<String> findRequestIdByDigest(@Param("digest") String digest);

    Optional<RegistrationActivation> findByTokenDigest(String tokenDigest);

    List<RegistrationActivation> findByRequestIdOrderByGenerationDesc(String requestId);
}
