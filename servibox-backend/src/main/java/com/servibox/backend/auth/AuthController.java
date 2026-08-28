package com.servibox.backend.auth;

import com.servibox.backend.auth.dto.LoginRequest;
import com.servibox.backend.auth.dto.LoginResponse;
import com.servibox.backend.auth.entity.User;
import com.servibox.backend.auth.repository.UserRepository;
import com.servibox.backend.tenant.Tenant;
import com.servibox.backend.tenant.TenantRepository;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Optional;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final TenantRepository tenantRepository;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    /**
     * Tenant inexistente o inactivo, usuario inexistente o inactivo y contrasena
     * incorrecta lanzan todos la misma excepcion. GlobalExceptionHandler la convierte en
     * un 401 con el mismo mensaje generico para los cuatro casos.
     */
    @PostMapping("/login")
    public LoginResponse login(@Valid @RequestBody LoginRequest request) {
        Tenant tenant = tenantRepository.findBySlug(request.tenantSlug())
                .filter(t -> Boolean.TRUE.equals(t.getActive()))
                .orElseThrow(InvalidCredentialsException::new);

        Optional<User> user = userRepository.findByUsernameAndTenantId(
                request.username(), tenant.getId());
        if (user.isEmpty() || !user.get().isActive()) {
            throw new InvalidCredentialsException();
        }
        if (!passwordEncoder.matches(request.password(), user.get().getPasswordHash())) {
            throw new InvalidCredentialsException();
        }

        return new LoginResponse(jwtService.generateToken(user.get()));
    }
}
