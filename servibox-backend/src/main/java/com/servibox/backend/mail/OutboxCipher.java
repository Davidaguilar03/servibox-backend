package com.servibox.backend.mail;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * AES-256-GCM del cuerpo del correo en el outbox, con MAIL_OUTBOX_ENCRYPTION_KEY (clave
 * runtime propia, al menos 32 bytes; la AES es su SHA-256). Formato: Base64(iv de 12 bytes
 * mas texto cifrado con etiqueta). Cambiar la clave deja sin descifrar lo pendiente: el
 * worker lo marca FAILED y la plataforma reenvia la activacion.
 */
@Component
public class OutboxCipher {

    private static final byte[] AAD = "servibox-outbox-v1".getBytes(StandardCharsets.UTF_8);

    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    public OutboxCipher(@Value("${servibox.mail.outbox.encryption-key}") String secret) {
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException("MAIL_OUTBOX_ENCRYPTION_KEY requiere al menos 32 bytes");
        }
        try {
            key = new SecretKeySpec(MessageDigest.getInstance("SHA-256")
                    .digest(secret.getBytes(StandardCharsets.UTF_8)), "AES");
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException(ex);
        }
    }

    public String encrypt(String plain) {
        try {
            byte[] iv = new byte[12];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, iv));
            cipher.updateAAD(AAD);
            byte[] sealed = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(ByteBuffer.allocate(iv.length + sealed.length)
                    .put(iv).put(sealed).array());
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("No se pudo cifrar el correo", ex);
        }
    }

    /** Lanza GeneralSecurityException o IllegalArgumentException si la clave cambio o el dato se altero. */
    public String decrypt(String encoded) throws GeneralSecurityException {
        byte[] data = Base64.getDecoder().decode(encoded);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, data, 0, 12));
        cipher.updateAAD(AAD);
        return new String(cipher.doFinal(data, 12, data.length - 12), StandardCharsets.UTF_8);
    }
}
