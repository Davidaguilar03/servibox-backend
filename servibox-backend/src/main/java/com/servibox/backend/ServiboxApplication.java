package com.servibox.backend;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;

/**
 * Sin UserDetailsServiceAutoConfiguration: la app no usa usuarios en memoria (sus logins son
 * propios, con JWT) y esa autoconfiguracion crea un usuario con una clave aleatoria que escribe
 * en el log al arrancar. Ver 03-DECISIONS.md (tarea 52).
 */
@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
public class ServiboxApplication {

	public static void main(String[] args) {
		if (java.util.Arrays.stream(args).anyMatch(a -> a.startsWith("--platform-bootstrap"))) {
			if (args.length != 1 || !(args[0].equals("--platform-bootstrap") || args[0].equals("--platform-bootstrap-stdin"))) {
				System.err.println("Bootstrap requiere un unico selector, sin credenciales en argumentos");
				System.exit(1);
			}
			System.exit(com.servibox.backend.platform.PlatformBootstrap.run(args[0].equals("--platform-bootstrap-stdin")));
			return;
		}
		SpringApplication.run(ServiboxApplication.class, args);
	}

}
