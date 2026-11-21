package com.servibox.backend.config;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class VersionController {

    public record VersionResponse(String name, String version, String buildTime) {
    }

    private final ObjectProvider<BuildProperties> buildProperties;

    public VersionController(ObjectProvider<BuildProperties> buildProperties) {
        this.buildProperties = buildProperties;
    }

    // Sin META-INF/build-info.properties (por ejemplo, corriendo desde el IDE sin Maven) no hay
    // BuildProperties: responde "dev" en vez de fallar.
    @GetMapping("/api/version")
    public VersionResponse version() {
        BuildProperties build = buildProperties.getIfAvailable();
        if (build == null) {
            return new VersionResponse("ServiBox", "dev", null);
        }
        return new VersionResponse("ServiBox", build.getVersion(),
                build.getTime() == null ? null : build.getTime().toString());
    }
}
