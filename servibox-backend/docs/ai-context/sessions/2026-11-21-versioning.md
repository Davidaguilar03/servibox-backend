# Versionado semantico y GET /api/version (tarea 58), 2026-11-21

Rama chore/58-versioning creada desde main en dd28639 (merge PR 57), igual a origin/main tras
fetch. Los dos .idea ajenos quedan fuera. Sin commit, push ni PR. Decision en
[03-DECISIONS](../03-DECISIONS.md); reglas en [02-CONVENTIONS](../02-CONVENTIONS.md), Versionado.

## Implementado

* `pom.xml`: version 1.1.0 y goal `build-info` del spring-boot-maven-plugin (fase
  generate-resources, corre en `test` y en `package`).
* `config.VersionController`: GET `/api/version` con `name`, `version`, `buildTime`
  (`Instant.toString`, UTC). Sin BuildProperties: `version` "dev", `buildTime` null.
* `SecurityConfig`: `/api/version` publico solo por GET, junto a `/api/health`. POST y subrutas
  siguen en 401.
* `Dockerfile` (raiz): copia `servibox-backend-*.jar`; antes nombraba 0.0.1-SNAPSHOT y con la
  version nueva la imagen no se habria construido. El build de Docker corre `./mvnw -B
  -DskipTests package`, que incluye generate-resources, asi que genera build-info igual que local.
* `CHANGELOG.md` en la raiz del repo (Keep a Changelog, 1.1.0 en desarrollo y 1.0.0 del
  2026-11-01). `docs/DEPLOYMENT.md`: nombre del JAR del bootstrap de plataforma.
* Sin mensajes de error nuevos. Sin migraciones.

## Pruebas

* `./mvnw test`: 749 pruebas, 0 fallos, 0 errores (745 base mas 4 nuevas en
  `config.VersionControllerTest`). Ninguna prueba existente ajustada.
* `./mvnw package`: `target/servibox-backend-1.1.0.jar` contiene META-INF/build-info.properties
  con `build.version=1.1.0` y `build.time=2026-11-21T17:35:21.733Z`.
* App en dev, puerto 8080: `curl -i http://localhost:8080/api/version` dio 200 con
  `{"name":"ServiBox","version":"1.1.0","buildTime":"2026-11-21T17:35:43.835Z"}`; POST dio 401.

## Limitaciones

* Docker estaba apagado: no se construyo la imagen. Lo de Docker se confirmo leyendo el
  Dockerfile, no ejecutandolo.
* Produccion (1.0.0) no tiene la ruta; el frontend no debe depender de ella hasta desplegar 1.1.0.
* La etiqueta v1.0.0 sobre el commit desplegado el 2026-11-01 queda pendiente (la crea David).
