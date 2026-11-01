# Preparacion del despliegue en VPS (despliegue 1), 2026-11-01

Solo backend. Rama chore/53-deploy-readiness: ya existia, sin commits propios y apuntando a
main en a78e0e3 (merge PR 52), igual a origin/main tras fetch; se reutilizo. No habia otra
rama de un intento anterior de esta tarea (chore/43-backend-deployment es la tarea 43, ya
mergeada). Los dos .idea ajenos quedan fuera. Sin frontend, commit, push ni PR.

## Paso 0 (investigacion, antes de implementar)

* Ya existian `Dockerfile` (multietapa Temurin 21 JDK/JRE por digest, usuario 10001, perfil
  prod fijo) y `.dockerignore` (lista blanca: pom, wrapper, src) de la tarea 43. Faltaban:
  limite de memoria de la JVM, docker-compose, Caddy, respaldos y guia de VPS. No hay
  HEALTHCHECK en la imagen; `GET /api/health` publico devuelve solo `{"status":"UP"}`, sin
  consultar la base ni exponer datos: sirve para el healthcheck del compose. La imagen JRE trae
  curl y bash.
* Variables: `application.properties` lee todo con `${VAR:default}`; `application-prod`
  exige DB_URL, DB_USERNAME y DB_PASSWORD (sin default: no arranca) y `ProductionConfiguration`
  valida JWT_SECRET, PLATFORM_JWT_SECRET, MAIL_OUTBOX_ENCRYPTION_KEY (32 bytes, distintas, sin
  dev-/test-) y ACTIVATION_BASE_URL https; con SMTP_HOST exige MAIL_FROM y SMTP_SECURITY
  valido. Sin SMTP_HOST arranca con aviso. REGISTRATION_REQUESTS_ENABLED default false (404).
  Tabla completa en ../../deploy/VPS.md.
* CORS: no habia ninguna configuracion (decision 2026-10-29: mismo origen con /api). Con el
  frontend en servibox.co y la API en api.servibox.co el navegador bloquearia todo. La API no
  estaba abierta: un preflight de otro origen recibia 401 sin Access-Control-Allow-Origin.
* IP real: `PublicRateLimiter.clientIp` usa la IP del socket si
  PUBLIC_RATE_LIMIT_CLIENT_IP_HEADER esta vacia; si no, la ultima entrada de esa cabecera si
  parece IP. Para que no se pueda falsificar, el proxy debe reemplazar la cabecera (no
  agregarla) y el puerto del backend no debe ser alcanzable sin pasar por el proxy.
* Bootstrap: `--platform-bootstrap` exige consola real (readPassword); con
  `docker compose run --rm --no-deps backend --platform-bootstrap` desde SSH hay TTY, la
  contrasena no queda en el comando ni en el historial y --rm borra la salida.
* Railway: menciones en DEPLOYMENT.md, en el WARN de ProductionConfiguration y en comentarios
  de application(-prod).properties y SecurityConfig.

## Implementado

* `SecurityConfig`: CORS por CORS_ALLOWED_ORIGINS en ambas cadenas (detalle en 03-DECISIONS).
  `ProductionConfiguration`: valida los origenes en prod; WARN de IP sin Railway.
  `application.properties`: `servibox.cors.allowed-origins=${CORS_ALLOWED_ORIGINS:}`.
  Comentarios de Railway cambiados a Caddy/compose.
* `Dockerfile`: `-XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError`.
* `deploy/`: docker-compose.prod.yml, Caddyfile, .env.example, backup.sh, restore.sh,
  .gitignore (.env, frontend/, backups/) y .gitattributes (LF para sh, yml, Caddyfile).
* `docs/deploy/VPS.md` (guia completa) y punteros en DEPLOYMENT.md y 01-ARCHITECTURE.

Mensaje nuevo de arranque (no es respuesta HTTP, no va al mapa del frontend):
`CORS_ALLOWED_ORIGINS acepta solo origenes https exactos separados por coma, sin comodines ni rutas`

## Pruebas

Conteos por motor, sin sumar.

* JUnit H2 suite completa con `mvnw clean package`: 695 = 689 base + 6 nuevas, cero fallos,
  errores u omitidas, BUILD SUCCESS. Nuevas: CorsConfigurationTest (4: preflight del origen
  permitido 200 con ACAO y sin credenciales en login tenant, login plataforma y ruta
  protegida; otro origen 403 sin ACAO en preflight y en GET; GET real expone Retry-After y
  Content-Disposition y el 401 trae ACAO; sin variable, preflight 401 sin ACAO como antes);
  ProductionConfigurationTest (+2: acepta https exactos y vacia; rechaza `*`,
  `https://*.servibox.co`, http, barra final, ruta y sin esquema). Ajustadas: ninguna.
* PG16 real con el compose (copia de deploy/ en el scratchpad, imagen construida del repo,
  Caddy 2 con dominios api.localhost y localhost y CA interna, puertos solo en 127.0.0.1
  18080/18443 por override; secretos generados con `openssl rand -hex 32`, nunca impresos):
  * PostgreSQL 16.15; Flyway `Successfully applied 12 migrations ... now at version v12`
    (V1 a V12 en orden); perfil prod; `Started ServiboxApplication`; sin WARN de IP; unico WARN
    el del outbox sin SMTP. Healthcheck del backend healthy. postgres y backend sin puertos
    publicados.
  * Memoria en reposo: backend 363 MiB de 900, postgres 79 de 512, Caddy 14 de 128; heap
    maximo calculado 676 MB (sin limite seria 5.6 GB en este equipo).
  * Por Caddy: salud 200 `{"status":"UP"}` con una sola `Strict-Transport-Security:
    max-age=15552000`; frontend 200 con HSTS, ruta inexistente 200 text/html (index.html),
    asset 200 text/javascript; HTTP 308 a https. POST /api/registration-requests,
    .../activate y /api/auth/register: 404 `{"error":"Recurso no encontrado","status":404}`.
    CORS: preflight de https://localhost 200 con ACAO; de otro origen 403 sin ACAO.
  * X-Real-IP: seis logins fallidos con X-Real-IP falso distinto: 401 x5 y 429 el sexto
    (Caddy reemplaza el valor). Otro cliente (contenedor en la red, otra IP real) mismo
    usuario: 401 y 401 (cuota propia); el host otra vez: 429. La cuota sigue la IP real.
  * Bootstrap `--platform-bootstrap-stdin` por `compose run -T`: salida 0, la clave no aparece
    en la salida; login plataforma 200, clave mala 401. `--platform-bootstrap` sin TTY: salida
    1 (rechazado); reejecucion con otra cuenta: salida 1. El modo interactivo con TTY no se
    pudo probar desde este entorno (sin terminal).
  * Datos: tenant y usuario por SQL (prod no tiene alta inmediata), login tenant 200 y 3
    categorias por la API (201).
  * Respaldo: backup.sh salida 0, archivo de 70281 bytes. Retencion: un .dump de hace 15 dias
    borrado, uno de hace 13 conservado. restore.sh: respuesta incorrecta cancela (salida 1);
    restaurar en servibox_verif: Flyway 12, 3 categorias, 1 tenant, 1 administrador.
    Produccion: tras el respaldo se agrego un tenant (2); restaurar detuvo el backend, recreo la
    base y lo arranco: 1 tenant, 3 categorias, 1 usuario, 1 administrador, Flyway 12; backend
    healthy (`Current version ... 12`) y login tenant 200.
  * Logs de los tres servicios: 0 apariciones de las 4 claves del .env, de la clave del
    usuario ni de `generated security password`.
  * Contenedores, volumenes, red, imagen local y carpeta temporal eliminados.
* Primera corrida de restore.sh: `docker compose exec -T` consumia la entrada estandar y se
  comia la confirmacion (cancelaba aunque se escribiera bien). Corregido con `< /dev/null` en
  los exec que no leen entrada; segunda corrida correcta.
* `git diff --check` sin hallazgos. No hay lint backend. Sin navegador.

## Limitaciones y riesgos

* Frontend: debe llamar a https://api.servibox.co. No se leyo ni cambio servibox-frontend; si
  usa rutas relativas `/api`, en servibox.co caerian en el SPA fallback (index.html). Hay que
  confirmarlo en su repo antes del build de produccion.
* Let's Encrypt real, DNS de Porkbun, ufw y el servidor no se probaron (fuera de alcance);
  localmente Caddy uso su CA interna.
* Imagenes `postgres:16` y `caddy:2` por etiqueta mayor, no por digest: una actualizacion
  menor llega al hacer pull. El backend se construye en el servidor (necesita Maven Central).
* Respaldos en el mismo servidor; la copia externa es manual. Volver atras tras una migracion
  nueva exige restaurar el respaldo y se pierde lo intermedio.
* Limites de tasa y logins en memoria: un reinicio o despliegue los borra (sin cambios).
* Los scripts deben quedar ejecutables en Git: en el cierre agregar con
  `git add --chmod=+x deploy/backup.sh deploy/restore.sh` (la guia tambien hace chmod +x).
