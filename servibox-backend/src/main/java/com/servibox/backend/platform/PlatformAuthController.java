package com.servibox.backend.platform;

import com.servibox.backend.auth.InvalidCredentialsException;
import com.servibox.backend.auth.LoginThrottle;
import com.servibox.backend.auth.LoginTimingEqualizer;
import com.servibox.backend.platform.dto.PlatformIdentityResponse;
import com.servibox.backend.platform.dto.PlatformLoginRequest;
import com.servibox.backend.platform.dto.PlatformLoginResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

@RestController
@RequestMapping("/api/platform/auth")
@RequiredArgsConstructor
public class PlatformAuthController {
    private final PlatformAdminRepository repository;
    private final PasswordEncoder passwordEncoder;
    private final PlatformJwtService jwtService;
    private final LoginThrottle loginThrottle;
    private final LoginTimingEqualizer timingEqualizer;

    @PostMapping("/login")
    public PlatformLoginResponse login(@Valid @RequestBody PlatformLoginRequest request, HttpServletRequest http) {
        return loginThrottle.attempt(http, LoginThrottle.platformAccount(request.email()), () -> authenticate(request));
    }

    private PlatformLoginResponse authenticate(PlatformLoginRequest request) {
        // Sin contrasena real que evaluar (inexistente, inactivo o clave de mas de 72 bytes): BCrypt de relleno.
        PlatformAdmin admin = repository.findByEmail(request.email().strip().toLowerCase(Locale.ROOT))
                .filter(PlatformAdmin::isActive).orElse(null);
        if (admin == null || request.password().getBytes(StandardCharsets.UTF_8).length > 72) {
            timingEqualizer.compareDummy();
            throw new InvalidCredentialsException();
        }
        if (!passwordEncoder.matches(request.password(), admin.getPasswordHash())) {
            throw new InvalidCredentialsException();
        }
        return new PlatformLoginResponse(jwtService.generateToken(admin), PlatformIdentityResponse.from(admin));
    }

    @GetMapping("/me")
    public PlatformIdentityResponse me(Authentication authentication) {
        return PlatformIdentityResponse.from(repository.findById((Long) authentication.getPrincipal())
                .filter(PlatformAdmin::isActive).orElseThrow(InvalidCredentialsException::new));
    }
}
