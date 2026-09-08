package com.servibox.backend.shared;

import com.servibox.backend.auth.InvalidCredentialsException;
import com.servibox.backend.inventory.service.DuplicateProductCodeException;
import com.servibox.backend.treasury.service.DuplicateAccountNameException;
import com.servibox.backend.treasury.service.InvalidTransferException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    /**
     * Principalmente la que lanza TenantAwareEntity en @PrePersist cuando no hay tenant
     * activo en el contexto. Es un error de uso, no una falla del servidor.
     */
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ErrorResponse> manejarEstadoInvalido(IllegalStateException ex) {
        log.warn("Peticion rechazada por estado invalido: {}", ex.getMessage());
        return construir(HttpStatus.BAD_REQUEST, ex.getMessage());
    }

    /** Choque con una restriccion de unicidad, por ejemplo dos productos con el mismo codigo. */
    @ExceptionHandler(DuplicateProductCodeException.class)
    public ResponseEntity<ErrorResponse> manejarDuplicado(DuplicateProductCodeException ex) {
        return construir(HttpStatus.CONFLICT, ex.getMessage());
    }

    /** Dos cuentas del mismo tenant con el mismo nombre. */
    @ExceptionHandler(DuplicateAccountNameException.class)
    public ResponseEntity<ErrorResponse> manejarCuentaDuplicada(DuplicateAccountNameException ex) {
        return construir(HttpStatus.CONFLICT, ex.getMessage());
    }

    /** Transferencia sin sentido: misma cuenta en los dos extremos, o monto no positivo. */
    @ExceptionHandler(InvalidTransferException.class)
    public ResponseEntity<ErrorResponse> manejarTransferenciaInvalida(InvalidTransferException ex) {
        return construir(HttpStatus.BAD_REQUEST, ex.getMessage());
    }

    /** Referencia a algo que no existe, por ejemplo una categoria o producto por id. */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> manejarArgumentoInvalido(IllegalArgumentException ex) {
        return construir(HttpStatus.BAD_REQUEST, ex.getMessage());
    }

    @ExceptionHandler({InvalidCredentialsException.class, AuthenticationException.class})
    public ResponseEntity<ErrorResponse> manejarCredenciales(Exception ex) {
        return construir(HttpStatus.UNAUTHORIZED, "Credenciales invalidas");
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> manejarValidacion(MethodArgumentNotValidException ex) {
        return construir(HttpStatus.BAD_REQUEST, "Peticion invalida");
    }

    /** Recurso pedido por la ruta que no existe para el tenant activo. */
    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ErrorResponse> manejarRecursoInexistente(ResourceNotFoundException ex) {
        return construir(HttpStatus.NOT_FOUND, ex.getMessage());
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ErrorResponse> manejarNoEncontrado(NoResourceFoundException ex) {
        return construir(HttpStatus.NOT_FOUND, "Recurso no encontrado");
    }

    /**
     * Ultimo recurso. El detalle va al log del servidor, nunca al cliente: un stacktrace
     * en la respuesta le regala al atacante versiones de librerias y rutas internas.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> manejarNoControlada(Exception ex) {
        log.error("Error no controlado", ex);
        return construir(HttpStatus.INTERNAL_SERVER_ERROR, "Error interno del servidor");
    }

    private ResponseEntity<ErrorResponse> construir(HttpStatus status, String mensaje) {
        return ResponseEntity.status(status).body(new ErrorResponse(mensaje, status.value()));
    }
}
