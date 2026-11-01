# Endurecimiento de seguridad y operacion antes del despliegue (tarea 51, extra), 2026-10-31

Solo backend. Rama feat/51-backend-hardening creada desde main en d7c360d (merge PR 50),
igual a origin/main tras fetch; 51 libre local, remoto y en PR (el ultimo PR es el 50,
mergeado). Los dos .idea ajenos quedan fuera. Sin frontend, commit, push ni PR.
SPRING_DATASOURCE_URL, SPRING_PROFILES_ACTIVE, DEV_DEMO_DATA y REGISTRATION_REQUESTS_ENABLED
no estaban definidas en el proceso. El backend de revision no estaba escuchando en 8080 al
comprobarlo (ningun proceso en ese puerto); no se arranco ni se toco su base. Docker estaba
apagado al empezar; David lo encendio para PG16 y el control HTTP.

## Hallazgos del paso 0 (antes de implementar)

* Registro inmediato: POST `/api/auth/register`, `AuthController.register` llama a
  `TenantRegistrationService.registrar`. Responde 403 `RegistrationDisabledException` si
  `servibox.registration.enabled` (REGISTRATION_ENABLED, default true) es false o si la
  compuerta de solicitudes (REGISTRATION_REQUESTS_ENABLED, default false) esta encendida;
  funciona con la compuerta apagada y REGISTRATION_ENABLED en true, que son los defaults
  tambien en prod: hoy prod arrancaria con el registro inmediato ABIERTO. No esta cubierto
  por el limite de tasa publico (tarea 49 solo cubre las dos rutas de solicitudes; la prueba
  PublicRateLimitTest lo confirma). Lo usan: TenantRegistrationTest,
  TenantRegistrationDisabledTest, TenantRegistrationRollbackTest, BackendPolishTest,
  RegistrationRequestTest, RegistrationRequestsDisabledTest y PublicRateLimitTest (MockMvc,
  perfil test o test+dev) y el script de siembra externo de dev. DevDataInitializer NO lo usa
  (siembra directo con repositorios). El frontend ya no lo llama. `provisionar` (activacion)
  es otro metodo y no pasa por la compuerta.
* Logins: tenant (`AuthController.authenticate`) lanza 401 sin BCrypt si el tenant no existe
  o esta inactivo/suspendido y si el usuario no existe o esta inactivo; solo con usuario
  activo llama `passwordEncoder.matches`. Plataforma (`PlatformAuthController.authenticate`)
  lanza 401 sin BCrypt si el administrador no existe o esta inactivo, y tambien si la clave
  tiene mas de 72 bytes UTF-8 (cortocircuito antes de `matches`). `BCryptPasswordEncoder` por
  defecto, costo 10 (`$2a$10$`). En Spring Security 7.1.1 `matches` con mas de 72 bytes
  devuelve false sin lanzar.
* Reenvio: `PlatformRegistrationRequestController.resendActivation` no recibe el principal;
  `RegistrationActivationService.resend(id)` bloquea la solicitud, valida APPROVED y reserva,
  intervalo y cupo, revoca lo vigente, cancela su correo e `issue` nueva generacion, en una
  transaccion. NO queda registrado quien lo hizo. AUDITORIA_PLATAFORMA (V11): id, accion
  VARCHAR(30) con CHECK de las 4 acciones de tenant, id_administrador NOT NULL con FK
  RESTRICT, id_tenant_objetivo NOT NULL sin FK, id_usuario_objetivo, motivo, fecha. Sin
  columna para la solicitud: hace falta V12.
* Outbox: estados PENDING, SENT y FAILED; sin retencion ni purga. Tareas programadas:
  `OutboxWorker.poll` (fixedDelay MAIL_OUTBOX_POLL_INTERVAL) habilitada por
  `OutboxSchedulingConfiguration` solo en servidor web y si scheduler-enabled no es false
  (el perfil test la apaga). FAILED no tiene fecha de fallo (solo creacion, proximo intento y
  expira_en); SENT tiene fecha_envio. `cancelPending` (JPQL) deja FAILED sin limpiar
  reclamado_hasta. `OutboxCipher`: AES-256-GCM con SHA-256 de MAIL_OUTBOX_ENCRYPTION_KEY, una
  sola clave; cambiarla deja lo pendiente en FAILED PAYLOAD_INVALIDO. `SmtpMailTransport`:
  solo STARTTLS (enable y required), sin checkserveridentity explicito.
* Prod: `ProductionConfiguration` es `@Profile("prod")`; perfil `prod`, fijado por
  SPRING_PROFILES_ACTIVE en la imagen. Exige JWT_SECRET, PLATFORM_JWT_SECRET,
  MAIL_OUTBOX_ENCRYPTION_KEY (32 bytes, distintas, sin dev-/test-) y ACTIVATION_BASE_URL https;
  application-prod exige DB_URL, DB_USERNAME y DB_PASSWORD. WARN si falta la cabecera de IP.

## Implementado

Decisiones en [03-DECISIONS](../03-DECISIONS.md); variables y procedimientos en
[DEPLOYMENT.md](../../DEPLOYMENT.md); columnas en [04-DATA-MODEL](../04-DATA-MODEL.md).

1. `auth.LoginTimingEqualizer` (hash fijo por arranque, mismo encoder y costo, candidato
   fijo). `AuthController` y `PlatformAuthController` lo llaman una vez en cada camino sin
   contrasena real (incluida la clave de mas de 72 bytes en plataforma). Mismo 401.
2. `TenantRegistrationService.registrar`: con perfil prod, 404 `Recurso no encontrado` antes
   de cualquier compuerta (inyecta `Environment`). Dev y test sin cambios.
3. V12 (`id_solicitud_objetivo` VARCHAR(36) sin FK, `id_tenant_objetivo` admite NULL, CHECK de
   accion con RESEND_ACTIVATION y CHECK ck_auditoria_objetivo). `PlatformAuditAction` y
   `PlatformAuditEntry` ampliados; `resend(id, adminId)` inserta la fila al final de su
   transaccion; el controlador pasa el principal.
4. `mail.OutboxPurger`: programado con la configuracion del worker; lotes de 500 con SKIP
   LOCKED; SENT por fecha_envio y FAILED por expira_en; nunca PENDING ni lease vigente; Clock
   inyectado; MAIL_OUTBOX_PURGE_ENABLED, MAIL_OUTBOX_PURGE_INTERVAL,
   MAIL_OUTBOX_RETENTION_SENT_DAYS y MAIL_OUTBOX_RETENTION_FAILED_DAYS.
5. `SmtpMailTransport`: SMTP_SECURITY (starttls por defecto, ssl), validado al crear el
   transporte; checkserveridentity true en ambos modos; nunca ssl.trust.
6. `OutboxCipher`: clave anterior opcional solo para descifrar; `ProductionConfiguration`
   la valida en prod.

Variables nuevas y defaults: MAIL_OUTBOX_ENCRYPTION_KEY_PREVIOUS (vacia),
MAIL_OUTBOX_PURGE_ENABLED (true), MAIL_OUTBOX_PURGE_INTERVAL (1h),
MAIL_OUTBOX_RETENTION_SENT_DAYS (30), MAIL_OUTBOX_RETENTION_FAILED_DAYS (90),
SMTP_SECURITY (starttls).

## Contrato para el frontend

Sin mensajes HTTP nuevos. En prod POST /api/auth/register pasa de 201 o 403 a 404
`Recurso no encontrado` (mensaje ya existente). Los 401 de login no cambian. Mensajes
internos de arranque (no son respuestas HTTP):

* `SMTP_SECURITY debe ser starttls o ssl`
* `MAIL_OUTBOX_ENCRYPTION_KEY_PREVIOUS requiere al menos 32 bytes y debe ser distinta de MAIL_OUTBOX_ENCRYPTION_KEY`
* `MAIL_OUTBOX_ENCRYPTION_KEY_PREVIOUS es opcional en prod; si se define requiere al menos 32 bytes, debe ser distinta de la clave actual y de las claves JWT y no de desarrollo o pruebas`
* La retencion invalida reutiliza `Configuracion del outbox de correo invalida`.

## Pruebas

Conteos separados por motor; no se suman.

* JUnit H2 base: 653 (nota de la tarea 50).
* JUnit H2 suite completa con `mvnw clean package`: 677 = 653 + 24 nuevas, cero fallos,
  errores u omitidas, 04:36 min, BUILD SUCCESS y JAR generado.
* Nuevas: LoginTimingEqualizerTest (5: con espias, tenant inexistente, tenant suspendido,
  usuario inexistente y usuario inactivo pagan exactamente un relleno y una llamada a
  `matches`, mismo 401 exacto; contrasena incorrecta y correcta no pagan relleno y hacen una
  sola comparacion; plataforma igual con inexistente, inactivo y mas de 72 bytes; el hash de
  relleno tiene el mismo prefijo de costo); ProductionRegistrationClosedTest (1, perfiles
  test+prod sobre H2: registro inmediato, solicitudes y activar en 404 sin crear filas con la
  compuerta apagada); ProductionRegistrationGateOnTest (1: compuerta encendida y
  REGISTRATION_ENABLED=false, sigue 404 y no 403); RegistrationActivationTest (+2: el reenvio
  deja una fila RESEND_ACTIVATION con admin 1, la solicitud, sin tenant, usuario ni motivo y la
  misma fecha de la generacion 2; con un administrador inexistente la FK falla y el reenvio
  entero se deshace); OutboxPurgerTest (5, reloj fijo: bordes de 30 y 90 dias, PENDING de 300
  dias y FAILED con lease vigente intactos, activaciones y solicitudes intactas, repetir no
  borra; 1003 filas en 3 lotes; apagada no borra; 4 instancias simultaneas sobre 1200 filas
  borran 1200 en total sin error; retencion desde las variables y validacion);
  OutboxKeyRotationWorkerTest (1, worker real: entrega lo cifrado con la clave vieja, lo
  nuevo sale con la actual y lo de una tercera clave queda FAILED PAYLOAD_INVALIDO);
  OutboxCipherRotationTest (3); SmtpMailTransportConfigurationTest (+4: mapeo starttls, mapeo
  ssl con puerto 465 y sin propiedades STARTTLS ni ssl.trust, valores invalidos no arrancan,
  SMTP_SECURITY llega a la propiedad con default starttls); ProductionConfigurationTest (+1:
  clave anterior valida arranca; corta, igual a la actual o a las JWT y dev-/test- no);
  V12ResendAuditMigrationTest (1).
* Ajustadas: `RegistrationActivationTest.limpiar` borra `auditoria_plataforma` antes de los
  administradores (el reenvio ahora deja filas con FK RESTRICT al administrador; la usan
  tambien RegistrationActivationConcurrencyTest y ScheduledOutboxTest);
  `reenvioRespetaIntervaloCupoDiarioYEstado` agrega que solo los 5 reenvios exitosos quedan
  auditados. Ninguna asercion retirada. Las pruebas del registro inmediato en dev/test
  (TenantRegistrationTest y las demas listadas arriba) siguen sin cambios y en verde.
* Primera corrida focalizada: 4 fallos de las pruebas nuevas, ninguno del producto: el perfil
  test pisaba las claves pasadas como variables en el contexto prod (se pasaron como
  propiedades), un valor " " que el runner recorta a vacio, y `getProtocol()` que es null por
  defecto en JavaMailSenderImpl. Un primer `clean package` fallo antes de compilar porque mi
  shell tenia abierta `target/surefire-reports`; se repitio completo.
* PG16 (PostgreSQL 16.15 en contenedor desechable propio etiquetado, puerto solo en
  127.0.0.1, clave efimera en memoria, base NUEVA por clase): 40/40.
  V12ResendAuditMigrationTest 1/1 (V1 a V11, datos de V11: tenant suspendido con version y
  motivo, usuario con auth_version 2, administrador, solicitud APPROVED y dos filas de
  auditoria SUSPEND_TENANT y REVOKE_USER_SESSIONS; luego V12: filas intactas y CHECK nuevos);
  V11PlatformGovernanceMigrationTest 1/1; OutboxPurgerTest 5/5; OutboxKeyRotationWorkerTest
  1/1; RegistrationActivationTest 15/15; LoginTimingEqualizerTest 5/5; PlatformTenantTest
  11/11; SchemaConformanceTest 1/1. Flyway en 12 en cada base de Spring. Catalogo:
  ck_auditoria_accion, ck_auditoria_objetivo, fk_auditoria_administrador; id_tenant_objetivo
  e id_solicitud_objetivo nullable. Contenedor y volumen eliminados.
* Control HTTP real: JAR empaquetado, puerto 18094, perfil prod, contra otro PostgreSQL 16.15
  desechable nuevo; JWT_SECRET, PLATFORM_JWT_SECRET, MAIL_OUTBOX_ENCRYPTION_KEY, clave de la
  base y del administrador generadas al vuelo en el proceso, ni impresas ni guardadas.
  `--platform-bootstrap-stdin`: salida 0, 1 administrador; Flyway 1 a 12. Arranque 1
  (compuerta apagada, sin cabecera de IP): `Started`, perfil prod, WARN de
  PUBLIC_RATE_LIMIT_CLIENT_IP_HEADER presente, aviso de outbox sin SMTP; POST
  /api/auth/register 404 `{"error":"Recurso no encontrado","status":404}` con 0 tenants;
  POST /api/registration-requests y /activate 404 con 0 solicitudes; login plataforma 200,
  clave mala e inexistente 401 identicos; tenant y usuario insertados por SQL (prod no tiene
  alta inmediata) con hash BCrypt de una clave aleatoria: login tenant 200, GET /api/tenant
  200, clave mala, usuario inexistente y tenant inexistente 401 identicos. Arranque 2
  (compuerta encendida, X-Real-IP configurada, reenvio sin espera): sin WARN de IP; registro
  inmediato sigue 404; solicitud 202, aprobar 200, auditoria 0 filas; reenviar 202 y sin
  token 401; auditoria 1 fila `RESEND_ACTIVATION|admin=1|solicitud_ok=true|tenant=null|
  usuario=null|motivo=null`, misma fecha que la generacion 2; correos FAILED REVOCADO y
  PENDING. Procesos detenidos, puerto 18094 libre, contenedor eliminado. No se uso 8080.
* `git diff --check` sin hallazgos. No hay lint backend configurado. Sin navegador.

## Revision de la superficie de produccion (solo reporte, sin cambios)

Observado en el arranque prod real de arriba y en el codigo.

| Severidad | Hallazgo | Recomendacion |
|-|-|-|
| Media | Spring Boot autoconfigura `UserDetailsServiceAutoConfiguration` y escribe en el log de prod `Using generated security password: ...` (un usuario `user` en memoria con clave aleatoria). No sirve hoy (sin httpBasic ni formLogin), pero es una credencial en los logs y una cuenta latente si alguien activa httpBasic | Excluir esa autoconfiguracion o declarar un `UserDetailsService` vacio |
| Media | `GlobalExceptionHandler` devuelve `ex.getMessage()` de cualquier `IllegalStateException` e `IllegalArgumentException` como 400. Las propias del dominio son mensajes controlados, pero las de librerias (Spring, Hibernate, JDK) salen tal cual y pueden nombrar clases, paquetes o valores internos | Excepciones de dominio propias para los 400 y mensaje generico para IAE/ISE ajenas |
| Media | Sin HSTS: Spring Security solo la envia en peticiones HTTPS y detras del proxy la app ve HTTP (sin forward headers, a proposito por la tarea 49) | Configurar HSTS en el proxy o en el frontend del mismo origen al publicar |
| Baja | Logs que pueden llevar datos personales: `OutboxWorker` registra `ex.getMessage()` del transporte (los errores SMTP suelen incluir el correo del destinatario); `log.error("Error no controlado", ex)` y el log de bloqueos incluyen mensajes de base (en PostgreSQL un duplicado trae el valor, por ejemplo un correo o NIT) | Registrar solo la clase o un codigo, o filtrar el mensaje |
| Baja | El JAR trae `h2` y `spring-boot-h2console`. La consola no se habilita (no hay `spring.h2.console.enabled`) y `/h2-console` responde 401; prod fija el driver PostgreSQL | Mover h2 y h2console a un perfil o scope de pruebas/dev cuando se toque el pom |
| Baja | Rutas desconocidas, `/error`, `/actuator`, `/v3/api-docs` y `/swagger-ui/index.html` responden 401 `No autenticado` (denyAll por defecto), no 404. No hay actuator ni springdoc en el JAR. No revela datos | Ninguna; si se prefiere 404 para rutas inexistentes, mapear en la cadena |
| Info | CORS: sin configuracion; un preflight de otro origen recibe 401 sin Access-Control-Allow-Origin. Correcto para la topologia de mismo origen | Mantener; no abrir CORS |
| Info | Cabeceras presentes en prod: X-Content-Type-Options nosniff, X-Frame-Options DENY, Cache-Control no-store, Pragma, Expires, X-XSS-Protection 0. Sin Content-Security-Policy (API JSON, bajo impacto) | CSP en el frontend |
| Info | Trazas: el 500 generico no incluye stacktrace; JSON roto da 400 generico. `server.error.*` en sus defaults (sin trazas ni mensajes) | Mantener |
| Info | Valores por defecto en prod: REGISTRATION_ENABLED=true ya no abre nada en prod (tarea 51); PUBLIC_RATE_LIMIT_CLIENT_IP_HEADER vacia solo avisa (WARN); devtools no va en el JAR; sin show-sql ni niveles de log en DEBUG | Seguir la lista de comprobaciones de DEPLOYMENT.md |

## Limitaciones y riesgos

* El relleno iguala el costo BCrypt, no todo el tiempo: los caminos siguen difiriendo en
  consultas a la base (tenant inexistente no consulta usuarios). Es mucho menor que BCrypt,
  pero no es cero. Sin medicion de tiempos (pedido: sin pruebas de tiempo).
* Login tenant con clave de mas de 72 bytes y usuario activo: `matches` devuelve false sin
  calcular BCrypt (comportamiento de Spring Security 7.1); queda mas rapido que una clave
  incorrecta normal. No se cambio (el login tenant no tiene tope de longitud; fuera de alcance).
* La purga mide FAILED desde expira_en: un FAILED por revocacion temprana se conserva hasta 24 h
  mas que su retencion exacta. Una purga parcial por bloqueos de otra instancia termina en la
  siguiente vuelta.
* Rotacion: no hay version de clave en el payload; con varias replicas, todas deben recibir
  ambas variables a la vez. Retirar la anterior antes de que venzan los PENDING viejos los deja
  FAILED.
* SMTPS no se probo contra un servidor real (sin proveedor); solo el mapeo de propiedades.
* En prod la unica via de alta es la solicitud: con la compuerta apagada no se pueden crear
  servitecas por la API (el control HTTP inserto el tenant por SQL solo para probar el login).
* El backend de revision en 8080 no estaba corriendo; no se verifico su estado mas alla de eso.
