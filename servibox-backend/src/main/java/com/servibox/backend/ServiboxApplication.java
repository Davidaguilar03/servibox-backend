package com.servibox.backend;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
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
