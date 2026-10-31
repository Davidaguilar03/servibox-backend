# Activacion de cuenta, outbox y envio de correo (tarea 4B), 2026-10-30

Solo backend. Sin frontend, AGENTS.md, V1 a V9, tablas comerciales, commit, push ni PR.
Rama feat/46-activation-outbox-email, libre local y remota, creada desde main en 76f84ff
(merge PR 45), igual a origin/main tras fetch. Los dos .idea ajenos quedan fuera.
Decisiones en [03-DECISIONS](../03-DECISIONS.md); tablas en [modelo de datos](../04-DATA-MODEL.md);
variables en [DEPLOYMENT.md](../../DEPLOYMENT.md).

## Implementado

* V10: ACTIVATED e `id_tenant_activado` en SOLICITUDES_REGISTRO (CHECK de estado y de
  reserva recreados, CHECK ck_solicitud_activada), ACTIVACIONES_REGISTRO y
  CORREOS_SALIENTES. Corre igual en H2 2.4 y PostgreSQL 16.15.
* Aprobar emite generacion 1 y encola el correo en su transaccion. Reenviar revoca y emite
  otra. Activar crea tenant y ADMIN con `TenantRegistrationService.provisionar`, extraido
  de `registrar` sin cambiar su comportamiento; `validarUsuario` y `validarContrasena`
  extraidos con los mismos mensajes.
* Registro inmediato: rechaza ademas un slug con reserva vigente de una solicitud (mismo
  409 "Ya existe una serviteca con el identificador X"). Sin efecto con la compuerta
  siempre apagada, porque entonces no hay reservas.
* Paquete `mail`: outbox, cifrado AES-256-GCM, worker programado, transporte SMTP
  condicional. Dependencia nueva: spring-boot-starter-mail (version del parent).
* Palabras reservadas nuevas: `plataforma`, `activar`.

## Contrato

POST `/api/registration-requests/activate` (publico, compuerta REGISTRATION_REQUESTS_ENABLED).
Cuerpo `{"token","username","password"}`. 200
`{"status":"ACTIVATED","tenantSlug":"<slug>","message":"Cuenta activada; inicia sesion"}`.
Sin JWT ni sesion; el cliente va a `/login` con el slug. Compuerta apagada: 404
`Recurso no encontrado`.

POST `/api/platform/registration-requests/{id}/resend-activation` (PLATFORM_IDENTITY, sin
cuerpo). 202 con el mismo detalle de GET `/{id}`; nunca enlace, token ni digest.

Aprobar conserva su contrato (200 con el detalle) y ahora encola el primer correo.

El enlace del correo es `ACTIVATION_BASE_URL/activar#token=<43 caracteres Base64 URL>`. El
frontend debe leer el fragmento, limpiarlo de la barra y enviarlo solo por POST.

Mensajes nuevos para src/api/errorMessages.ts, formato `{"error","status"}`:

* 400 `El enlace de activacion no es valido` (token ausente, mal formado, alterado o inexistente)
* 410 `El enlace de activacion vencio o fue reemplazado` (caducado o revocado por reenvio)
* 409 `El enlace de activacion ya fue utilizado; inicia sesion`
* 429 `Espera un momento antes de reenviar la activacion`
* 429 `Se alcanzo el limite diario de reenvios de esta solicitud`
* Internos de arranque: `MAIL_OUTBOX_ENCRYPTION_KEY requiere al menos 32 bytes`,
  `MAIL_OUTBOX_ENCRYPTION_KEY es obligatoria en prod, requiere al menos 32 bytes, debe ser exclusiva y no de desarrollo o pruebas`,
  `ACTIVATION_BASE_URL es obligatoria en prod y debe usar https`,
  `Configuracion de activacion de cuentas invalida`,
  `Configuracion del outbox de correo invalida`,
  `MAIL_FROM es obligatorio cuando SMTP_HOST esta configurado`

Se reutilizan al activar: 400 de usuario y contrasena del registro, 409
`La solicitud ya no permite esta operacion` (no APPROVED o reserva vencida), 409
`El codigo solicitado no esta disponible` (slug tomado por un tenant) y el 409 generico de
bloqueo. Al reenviar: 404 `Solicitud no encontrada`, 409 `La solicitud ya no permite esta
operacion`, 401 sin token de plataforma.

## Pruebas

Conteos separados por motor; no se suman entre si.

* JUnit H2, base en la rama recien creada (igual a main): 557, cero fallos/errores/omitidas.
* JUnit H2, suite completa final: 581 = 557 base + 24 nuevas, cero fallos/errores/omitidas,
  03:25 min. Nuevas: 13 RegistrationActivationTest (ciclo completo con login y GET
  /api/tenant; token alterado, mal formado y ausente; usado; vencido; revocado por reenvio
  con correo pendiente cancelado; token solo de su solicitud y reserva vencida; slug tomado
  antes de activar; reglas de usuario/contrasena sin consumir el token; token crudo ausente
  de todas las filas de todas las tablas, de la salida de logs y de las respuestas;
  reenvio con intervalo, cupo, estado, 404 y 401; fallo temporal con retroceso; fallo
  permanente, intentos agotados, vencido y cuerpo ilegible; restricciones de la base),
  4 RegistrationActivationConcurrencyTest (10 hilos activando el mismo token; 5 activar
  contra 5 reenviar; 10 aprobaciones simultaneas con un enlace y un correo; 4 workers
  sobre 15 correos con transporte lento, 15 envios exactos), 1 ActivationWithoutTransportTest,
  1 ScheduledOutboxTest (tarea programada real), 3 SmtpMailTransportConfigurationTest,
  1 en RegistrationRequestsDisabledTest (activar con compuerta apagada 404) y 1 en
  ProductionConfigurationTest (clave del outbox y URL https).
* Ajustadas, sin retirar aserciones: SchemaConformanceTest y FlywayMigrationTest esperan
  18 tablas; ProductionConfigurationTest agrega clave de outbox y URL a sus tres fixtures
  validos; RegistrationRequestTest y RegistrationRequestConcurrencyTest borran correos y
  activaciones antes de las solicitudes (aprobar ahora los crea, FK); en
  RegistrationRequestTest el estado invalido del filtro pasa de ACTIVATED (ahora valido)
  a ARCHIVED, con el mismo 400 esperado.
* Primera suite completa con cambios: 581 con un error de la prueba nueva (nombre de
  tabla sin comillas en H2 al volcar todas las tablas); se corrigio la prueba, no el
  producto. Primera corrida focalizada: el ajuste ACTIVATED de arriba.
* PG16 (evidencia externa, PostgreSQL 16.15 en contenedor desechable propio etiquetado,
  puerto solo en 127.0.0.1, clave efimera fuera del repo): Flyway V1 a V10 sobre base
  vacia y validacion Hibernate correctas. Corrida 1: 40/40 (RegistrationActivationTest 13,
  RegistrationActivationConcurrencyTest 4, ActivationWithoutTransportTest 1,
  ScheduledOutboxTest 1, RegistrationRequestTest 16, RegistrationRequestConcurrencyTest 5).
  Dos repeticiones de la clase de concurrencia sobre la MISMA base fallaron en la
  preparacion (409 al crear la solicitud porque el tenant de la corrida 1 seguia ahi:
  las pruebas no borran tenants); descartadas como invalidas. Repetida dos veces sobre
  bases nuevas: 4/4 cada una. Restricciones de V10 presentes en pg_constraint. Contenedor
  detenido y eliminado con su volumen anonimo.
* Arranque real dev en puerto 18089 sin SMTP: V10 aplicada, aviso de outbox sin
  transporte, salud 200, activar 404 con la compuerta apagada; proceso detenido.
* Sin navegador ni correos reales. No hay lint backend configurado.

## Limitaciones

* Envio real a un buzon NO verificado; proveedor sin elegir. SENT es aceptado por el
  transporte. Solo STARTTLS (sin SMTPS 465).
* Plazos 24 h, 60 s y 5 por dia son propuesta no confirmada por David.
* Sin limite de tasa 429 por IP en la activacion publica (tarea 7 u 8): no abrir la
  compuerta en publico antes.
* El correo se envia aunque la compuerta este apagada; si se cierra con aprobadas sin
  activar, el enlace responde 404 hasta reabrir.
* El cuerpo y asunto del correo son texto plano fijo en el backend, sin plantilla HTML ni
  revision de copy. Fecha de vencimiento en BUSINESS_TIME_ZONE.
* Rotar MAIL_OUTBOX_ENCRYPTION_KEY sin version de clave: lo pendiente queda FAILED y se
  reenvia a mano.
* Sin purga programada de filas SENT/FAILED ni de activaciones viejas (no guardan secretos).
* Migracion V9 a V10 probada sobre base vacia en H2 y PG; no con solicitudes existentes.
* `platform` no se agrego a palabras reservadas (la tarea pidio plataforma y activar).
* El revisor del reenvio no se registra (no hay tabla de auditoria).

## Contradicciones con documentacion anterior

* La auditoria proponia POST `/auth/activation/complete` solo con token y password; se
  implemento la ruta pedida `/api/registration-requests/activate` con username.
* La auditoria proponia estados de outbox QUEUED/PROCESSING/ACCEPTED_BY_TRANSPORT; se
  usaron PENDING/SENT/FAILED pedidos, con el lease en `reclamado_hasta`.
* La auditoria proponia reenvio con expectedVersion e Idempotency-Key; la tarea no lo pide
  y el intervalo de 60 s cubre el reintento inmediato.
* La nota 4A y DEPLOYMENT decian que 4B dejaria la compuerta lista para abrir; sigue
  faltando el limite de tasa y el envio real.
