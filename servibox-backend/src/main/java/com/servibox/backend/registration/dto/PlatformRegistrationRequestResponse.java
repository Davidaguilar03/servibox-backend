package com.servibox.backend.registration.dto;

import com.servibox.backend.registration.RegistrationRequest;
import com.servibox.backend.registration.RegistrationRequestStatus;

import java.time.LocalDateTime;

/**
 * Solicitud vista por la plataforma. Sin clave de reintento, hash ni datos comerciales.
 * matches solo en el detalle (null en el listado): coincidencias indicativas, no bloquean.
 * policyVersion y policyAcceptedAt solo en el detalle (RegistrationRequestPage los omite del
 * listado), nulos en solicitudes anteriores a V14.
 */
public record PlatformRegistrationRequestResponse(
        String id,
        RegistrationRequestStatus status,
        String businessName,
        String nit,
        String phone,
        String slug,
        String contactName,
        String contactEmail,
        LocalDateTime createdAt,
        LocalDateTime reservationExpiresAt,
        boolean reservationExpired,
        LocalDateTime reviewedAt,
        Long reviewerId,
        String rejectionReason,
        long version,
        Matches matches,
        String policyVersion,
        LocalDateTime policyAcceptedAt
) {

    /** Solo cantidades: otras solicitudes con el mismo NIT o correo y tenants con el mismo NIT. */
    public record Matches(long requestsWithSameNit, long requestsWithSameEmail, long tenantsWithSameNit) {
    }

    public static PlatformRegistrationRequestResponse from(RegistrationRequest r, LocalDateTime now, Matches matches) {
        boolean reserva = r.getStatus() == RegistrationRequestStatus.PENDING
                || r.getStatus() == RegistrationRequestStatus.APPROVED;
        return new PlatformRegistrationRequestResponse(r.getId(), r.getStatus(), r.getBusinessName(), r.getNit(),
                r.getPhone(), r.getRequestedSlug(), r.getContactName(), r.getContactEmail(), r.getCreatedAt(),
                r.getReservationExpiresAt(), reserva && !r.getReservationExpiresAt().isAfter(now),
                r.getReviewedAt(), r.getReviewerId(), r.getRejectionReason(), r.getVersion(), matches,
                r.getPolicyVersion(), r.getPolicyAcceptedAt());
    }
}
