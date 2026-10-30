package com.servibox.backend.platform;

import com.servibox.backend.ServiboxApplication;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;

import java.io.BufferedReader;
import java.io.Console;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

public final class PlatformBootstrap {
    private PlatformBootstrap() {
    }

    public static int run(boolean privateStdin) {
        char[] password = null;
        try {
            String email;
            if (privateStdin) {
                if (System.console() != null) {
                    throw new IllegalStateException("La entrada stdin de bootstrap requiere un pipe privado sin consola");
                }
                // Exclusivo de pipes privados/secret managers, nunca entrada interactiva con eco.
                BufferedReader reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
                email = readPrivateLine(reader, 150);
                password = readPrivateLine(reader, 72).toCharArray();
            } else {
                Console console = System.console();
                if (console == null) {
                    throw new IllegalStateException("Bootstrap requiere consola con entrada oculta o pipe privado explicito");
                }
                email = console.readLine("Email de plataforma: ");
                password = console.readPassword("Clave de plataforma: ");
            }
            SpringApplication app = new SpringApplication(ServiboxApplication.class);
            app.setWebApplicationType(WebApplicationType.NONE);
            app.addInitializers(context -> {
                if (context instanceof org.springframework.web.context.WebApplicationContext) {
                    throw new IllegalStateException("Bootstrap no admite un contexto HTTP");
                }
            });
            app.setAdditionalProfiles("platform-bootstrap");
            try (var context = app.run()) {
                context.getBean(PlatformBootstrapService.class).create(email, password);
            }
            System.out.println("Bootstrap de plataforma creado");
            return 0;
        } catch (Exception ex) {
            // No imprimir excepciones SQL ni valores de entrada.
            System.err.println("Bootstrap de plataforma rechazado; revise configuracion, entrada y existencia de cuenta");
            return 1;
        } finally {
            if (password != null) {
                Arrays.fill(password, '\0');
            }
        }
    }

    private static String readPrivateLine(BufferedReader reader, int max) throws java.io.IOException {
        StringBuilder line = new StringBuilder();
        int value;
        while ((value = reader.read()) != -1 && value != '\n') {
            if (value != '\r') {
                line.append((char) value);
            }
            if (line.length() > max) {
                throw new IllegalArgumentException("Entrada privada demasiado larga");
            }
        }
        if (value == -1 && line.isEmpty()) {
            throw new IllegalArgumentException("Entrada privada incompleta");
        }
        return line.toString();
    }
}
