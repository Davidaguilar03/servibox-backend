package com.servibox.backend.registration;

/** PENDING y APPROVED reservan el slug; REJECTED y CANCELLED lo liberan; ACTIVATED lo pasa a su tenant. */
public enum RegistrationRequestStatus {
    PENDING, APPROVED, REJECTED, CANCELLED, ACTIVATED
}
