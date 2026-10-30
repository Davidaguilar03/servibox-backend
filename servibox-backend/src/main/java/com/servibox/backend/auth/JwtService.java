package com.servibox.backend.auth;

import com.servibox.backend.auth.entity.User;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.JwtException;
import com.servibox.backend.auth.entity.Role;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.Set;

@Service
public class JwtService {

    public static final String CLAIM_TENANT_ID = "tenantId";
    public static final String CLAIM_ROLE = "role";

    private final SecretKey key;
    private final long expirationMs;

    public JwtService(
            @Value("${servibox.jwt.secret}") String secret,
            @Value("${servibox.jwt.expiration-ms}") long expirationMs) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.expirationMs = expirationMs;
    }

    public String generateToken(User user) {
        Date ahora = new Date();
        return Jwts.builder()
                .subject(user.getUsername())
                .claim(CLAIM_TENANT_ID, user.getTenantId())
                .claim(CLAIM_ROLE, user.getRole().name())
                .issuer("servibox-tenant").audience().add("tenant").and()
                .claim("identityType", "TENANT")
                .issuedAt(ahora)
                .expiration(new Date(ahora.getTime() + expirationMs))
                .signWith(key)
                .compact();
    }

    /**
     * Verifica firma y expiracion. Lanza JwtException si el token es invalido o vencido.
     */
    public Claims validateAndParse(String token) {
        Claims claims = Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
        Object tenantId = claims.get(CLAIM_TENANT_ID);
        if (!(tenantId instanceof Integer || tenantId instanceof Long) || ((Number) tenantId).longValue() <= 0
                || claims.getSubject() == null || claims.getSubject().isBlank()
                || claims.getIssuedAt() == null || claims.getExpiration() == null
                || !(claims.get("iat") instanceof Number) || !(claims.get("exp") instanceof Number)
                || claims.getIssuedAt().after(new Date()) || !claims.getExpiration().after(claims.getIssuedAt())) {
            throw new JwtException("JWT tenant invalido");
        }
        Role.valueOf(extractRole(claims) == null ? "" : extractRole(claims));
        // Heredado: ausencia conjunta de tipo, audiencia e issuer, firmado con la clave tenant.
        boolean legacy = !claims.containsKey("identityType") && !claims.containsKey("aud") && !claims.containsKey("iss");
        if (!legacy && (!"TENANT".equals(claims.get("identityType"))
                || !Set.of("tenant").equals(claims.getAudience()) || !"servibox-tenant".equals(claims.getIssuer()))) {
            throw new JwtException("Ambito JWT tenant invalido");
        }
        return claims;
    }

    public Long extractTenantId(Claims claims) {
        return claims.get(CLAIM_TENANT_ID, Number.class).longValue();
    }

    public String extractUsername(Claims claims) {
        return claims.getSubject();
    }

    public String extractRole(Claims claims) {
        return claims.get(CLAIM_ROLE, String.class);
    }
}
