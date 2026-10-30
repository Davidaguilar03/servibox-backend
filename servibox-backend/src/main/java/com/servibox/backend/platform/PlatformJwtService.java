package com.servibox.backend.platform;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.Set;

@Service
public class PlatformJwtService {
    private final SecretKey key;
    private final long expirationMs;

    public PlatformJwtService(@Value("${servibox.platform.jwt.secret}") String secret,
                              @Value("${servibox.platform.jwt.expiration-ms}") long expirationMs,
                              @Value("${servibox.jwt.secret}") String tenantSecret) {
        if (secret.equals(tenantSecret) || expirationMs <= 0 || expirationMs > 86400000) {
            throw new IllegalArgumentException("Configuracion JWT de plataforma invalida");
        }
        key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.expirationMs = expirationMs;
    }

    public String generateToken(PlatformAdmin admin) {
        Date now = new Date();
        return Jwts.builder().subject(admin.getId().toString())
                .issuer("servibox-platform").audience().add("platform").and()
                .claim("identityType", "PLATFORM").claim("authVersion", admin.getAuthVersion())
                .issuedAt(now).expiration(new Date(now.getTime() + expirationMs))
                .signWith(key).compact();
    }

    public Claims validateAndParse(String token) {
        Claims claims = Jwts.parser().verifyWith(key).requireIssuer("servibox-platform")
                .require("identityType", "PLATFORM").build().parseSignedClaims(token).getPayload();
        Object version = claims.get("authVersion");
        if (!Set.of("platform").equals(claims.getAudience())
                || claims.containsKey("tenantId") || claims.getIssuedAt() == null || claims.getExpiration() == null
                || !(claims.get("iat") instanceof Number) || !(claims.get("exp") instanceof Number)
                || claims.getIssuedAt().after(new Date()) || !claims.getExpiration().after(claims.getIssuedAt())
                || claims.getSubject() == null || !claims.getSubject().matches("[1-9][0-9]{0,18}")
                || !(version instanceof Integer || version instanceof Long) || ((Number) version).longValue() < 0) {
            throw new JwtException("JWT de plataforma invalido");
        }
        Long.parseLong(claims.getSubject());
        return claims;
    }
}
