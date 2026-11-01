package com.servibox.backend.auth;

import com.servibox.backend.auth.dto.LoginRequest;
import com.servibox.backend.auth.dto.LoginResponse;
import com.servibox.backend.auth.dto.RegisterRequest;
import com.servibox.backend.auth.entity.User;
import com.servibox.backend.auth.repository.UserRepository;
import com.servibox.backend.tenant.Tenant;
import com.servibox.backend.tenant.TenantRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final TenantRepository tenantRepository;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final TenantRegistrationService tenantRegistrationService;
    private final LoginThrottle loginThrottle;
    private final LoginTimingEqualizer timingEqualizer;

    /**
     * Tenant inexistente o inactivo, usuario inexistente o inactivo y contrasena
     * incorrecta lanzan todos la misma excepcion. GlobalExceptionHandler la convierte en
     * un 401 con el mismo mensaje generico para los cuatro casos, y LoginThrottle los cuenta
     * igual (tambien para cuentas que no existen).
     */
    @PostMapping("/login")
    public LoginResponse login(@Valid @RequestBody LoginRequest request, HttpServletRequest http) {
        return loginThrottle.attempt(http, LoginThrottle.tenantAccount(request.tenantSlug(), request.username()),
                () -> authenticate(request));
    }

    /** Sin contrasena real que evaluar, paga una comparacion BCrypt de relleno: mismo tiempo que una incorrecta. */
    private LoginResponse authenticate(LoginRequest request) {
        Tenant tenant = tenantRepository.findBySlug(request.tenantSlug())
                .filter(t -> Boolean.TRUE.equals(t.getActive()))
                .orElse(null);
        if (tenant == null) {
            timingEqualizer.compareDummy();
            throw new InvalidCredentialsException();
        }

        Optional<User> user = userRepository.findByUsernameAndTenantId(
                request.username(), tenant.getId());
        // Mas de 72 bytes: BCrypt no la evaluaria (Spring devuelve false sin calcular); paga el relleno.
        if (user.isEmpty() || !user.get().isActive()
                || request.password().getBytes(StandardCharsets.UTF_8).length > 72) {
            timingEqualizer.compareDummy();
            throw new InvalidCredentialsException();
        }
        if (!passwordEncoder.matches(request.password(), user.get().getPasswordHash())) {
            throw new InvalidCredentialsException();
        }

        return LoginResponse.from(jwtService.generateToken(user.get()), user.get(), tenant);
    }

    /**
     * Registro publico de una serviteca. Sin @Valid: las reglas y sus mensajes estan en
     * TenantRegistrationService. Responde la misma sesion que el login.
     */
    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public LoginResponse register(@RequestBody RegisterRequest request) {
        return tenantRegistrationService.registrar(request);
    }
}
