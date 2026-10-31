package com.servibox.backend.registration;

import com.servibox.backend.registration.dto.ActivationAcknowledgment;
import com.servibox.backend.registration.dto.ActivationBody;
import com.servibox.backend.registration.dto.RegistrationAcknowledgment;
import com.servibox.backend.registration.dto.RegistrationRequestBody;
import com.servibox.backend.shared.ResourceNotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Solicitud publica de registro y su activacion, sin sesion. Apagadas responden 404 como ruta no disponible. */
@RestController
@RequiredArgsConstructor
public class RegistrationRequestController {

    private final RegistrationRequestService service;
    private final RegistrationActivationService activationService;
    private final PublicRateLimiter rateLimiter;

    @Value("${servibox.registration-requests.enabled}")
    private boolean enabled;

    @PostMapping("/api/registration-requests")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public RegistrationAcknowledgment submit(
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @RequestBody RegistrationRequestBody body, HttpServletRequest http) {
        if (!enabled) {
            throw new ResourceNotFoundException("Recurso no encontrado");
        }
        // Despues de la compuerta: apagada no consume cuota. Un reintento con la misma clave cuenta como una peticion mas.
        rateLimiter.checkSubmission(http);
        try {
            return service.submit(key, body);
        } catch (DataIntegrityViolationException ex) {
            return service.afterConflict(key, body);
        }
    }

    /** Consume el enlace de activacion: crea tenant y ADMIN. Sin JWT; el cliente sigue al login. */
    @PostMapping("/api/registration-requests/activate")
    public ActivationAcknowledgment activate(@RequestBody ActivationBody body, HttpServletRequest http) {
        if (!enabled) {
            throw new ResourceNotFoundException("Recurso no encontrado");
        }
        rateLimiter.checkActivation(http);
        return activationService.activate(body);
    }
}
