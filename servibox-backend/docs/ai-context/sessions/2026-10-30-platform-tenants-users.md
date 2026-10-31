# Panel de plataforma: tenants y usuarios (tarea 5A), 2026-10-30

Rama feat/47-platform-tenants-users, creada desde main en 2d5d022 (merge PR 46), igual a
origin/main tras fetch. Los dos .idea ajenos quedan fuera.

## Hallazgos del paso 0 (antes de implementar)

* Tenant (TENANTS): id, nombre, slug, nit, telefono, `activo_tenant` booleano y
  `fecha_creacion_tenant`. No tiene estado enumerado, version, motivo ni fechas de
  suspension. No extiende TenantAwareEntity, asi que el filtro Hibernate no le aplica.
  TenantRegistrationService.provisionar lo crea activo.
* Login tenant: AuthController rechaza con 401 generico si el tenant no esta activo.
  JWT tenant HMAC, 24 h por defecto (JWT_EXPIRATION_MS), sin refresh ni sesion en base.
  Claims sub=username, tenantId, role, iss, aud, identityType, iat, exp. Tokens heredados
  sin los tres marcadores se aceptan hasta expirar.
* JwtAuthenticationFilter en cada peticion: valida firma y claims, pone TenantContext y
  consulta USUARIOS por (username, tenantId) exigiendo usuario activo; el rol sale de la
  base. NO consulta el tenant: un tenant con activo_tenant=false sigue usando la API con
  tokens ya emitidos hasta que expiren. No hay version de usuario: no existe forma de
  invalidar un token de un usuario activo.
* Plataforma (tarea 3): PlatformAuthenticationFilter consulta activo y authVersion del
  administrador por peticion; es el patron a copiar para usuarios tenant.
* Consulta cruzada desde plataforma: la cadena de plataforma limpia TenantContext, asi que
  TenantAwareJpaTransactionManager no habilita el filtro; 4A y 4B leen tablas globales y
  TENANTS por id. Para USUARIOS (TenantAwareEntity) hay que filtrar por id_tenant
  explicito en la consulta.
* Bloqueo: 4A usa `@Lock(PESSIMISTIC_WRITE)` en `lockById` mas expectedVersion comparado a
  mano, con 409 "El recurso cambio; actualiza e intenta de nuevo".
* Auditoria de plataforma: NO existe. La tarea 3 no creo tabla de auditoria; lo unico es
  `id_revisor` en SOLICITUDES_REGISTRO (4A) y la nota 4B lo deja como limitacion ("no hay
  tabla de auditoria"). Contradice el prompt ("usando lo existente de la tarea 3").
  Se detuvo el trabajo y David eligio crear la tabla en V11.
* Conclusion: la revocacion inmediata no exige un cambio grande de autenticacion; basta
  copiar el patron authVersion de plataforma al JWT tenant y leer TENANTS por PK.

## Implementado

Decisiones en [03-DECISIONS](../03-DECISIONS.md); tablas en [modelo de datos](../04-DATA-MODEL.md).

* V11: TENANTS `version_tenant` (default 0), `fecha_suspension_tenant`,
  `motivo_suspension_tenant` y CHECK ck_tenant_version, ck_tenant_suspension (activo sin
  fecha ni motivo); USUARIOS `auth_version` (default 0, CHECK); AUDITORIA_PLATAFORMA (FK
  solo al administrador, CHECK de accion, indice por tenant y fecha). 19 tablas.
* `activo_tenant` sigue siendo el estado; `tenant.TenantStatus` lo deriva y define las
  transiciones (ACTIVE a SUSPENDED y viceversa).
* JWT tenant: claim `authVersion`; JwtAuthenticationFilter exige que coincida (ausente = 0)
  y que el tenant este activo. Login sin cambios (ya rechazaba tenant inactivo).
* `platform.PlatformTenantController`, `PlatformTenantService`, `PlatformAuditEntry`,
  `PlatformAuditRepository`, `PlatformTenantConflictException` y DTOs en `platform.dto`.
  SecurityConfig abre solo las 8 rutas nuevas con PLATFORM_IDENTITY.

## Contrato

Todas con `Authorization: Bearer <JWT plataforma>`. Fechas UTC sin zona. Errores
`{"error","status"}`. Sin token o con JWT tenant: 401.

GET `/api/platform/tenants?status=ACTIVE|SUSPENDED&q=texto&page=0&size=25` (size 1 a 100,
q hasta 100 caracteres, contiene en nombre o slug sin distinguir mayusculas, comodines
literales). Orden fecha de creacion desc, id desc.

```json
{"items":[{"id":7,"name":"Taller Alfa","slug":"alfa","nit":"900123456-7","phone":"3001234567",
"status":"SUSPENDED","version":1,"createdAt":"2026-10-30T20:01:02.123456",
"suspendedAt":"2026-10-30T21:00:00.5","suspensionReason":"Mora"}],
"page":0,"size":25,"totalElements":1,"totalPages":1}
```

GET `/api/platform/tenants/{id}`:

```json
{"tenant":{...igual que un item...},"totalUsers":3,
"userCounts":[{"role":"ADMIN","active":true,"count":1},{"role":"MECANICO","active":false,"count":1}],
"registrationRequest":{"id":"uuid","status":"ACTIVATED","createdAt":"...","reviewedAt":"..."}}
```

`registrationRequest` es null si el tenant no salio de una solicitud (registro inmediato).

GET `/api/platform/tenants/{id}/users?page=0&size=25` (orden id asc):
`{"items":[{"id":1,"username":"admin","fullName":"Ana","email":"a@x.co","role":"ADMIN",
"active":true,"createdAt":"...","updatedAt":"..."}],"page":0,...}`. Nunca hash ni version.

POST `/{id}/suspend` cuerpo `{"expectedVersion":0,"reason":"Mora"}` y POST `/{id}/reactivate`
cuerpo `{"expectedVersion":1,"reason":"opcional"}`: 200 con el detalle de GET `/{id}`.

POST `/{id}/users/{userId}/revoke`, cuerpo opcional `{"reason":"..."}`: 200 con el usuario
(misma forma que el listado). Repetirlo devuelve lo mismo.

POST `/{id}/revoke-sessions`, cuerpo opcional `{"reason":"..."}`: 200
`{"tenantId":7,"revokedUsers":3}`.

GET `/api/platform/metrics`:

```json
{"tenants":{"ACTIVE":12,"SUSPENDED":1},"users":{"total":40,"active":38,"inactive":2},
"registrationRequests":{"PENDING":2,"APPROVED":1,"REJECTED":0,"CANCELLED":0,"ACTIVATED":5},
"outboxMessages":{"PENDING":0,"SENT":6,"FAILED":1}}
```

Mensajes nuevos para src/api/errorMessages.ts:

* 404 `Tenant no encontrado`
* 404 `Usuario no encontrado` (usuario inexistente o de otro tenant)
* 400 `El motivo de suspension es obligatorio`
* 400 `El motivo no puede superar 500 caracteres`
* 400 `La busqueda no puede superar 100 caracteres`
* 409 `El tenant ya esta suspendido`
* 409 `El tenant ya esta activo`

Reutilizados: 400 `La version esperada es obligatoria`, 400 `Paginacion invalida`, 409 `El
recurso cambio; actualiza e intenta de nuevo`, 409 generico de bloqueo, 400 de enum en
`status`. El tenant suspendido ve 401 `Credenciales invalidas` al iniciar sesion y 401
`No autenticado` con su token viejo, igual que hoy con usuario inactivo.

## Pruebas

Conteos separados; no se suman entre motores.

* JUnit H2 base (main al crear la rama, segun la nota 4B): 581.
* JUnit H2 suite completa final: 595 = 581 + 14 nuevas, cero fallos/errores/omitidas,
  03:31 min. Nuevas: 11 PlatformTenantTest (listado con paginacion, orden, busqueda y
  filtro; detalle con conteos y solicitud de origen; usuarios paginados; version, motivo y
  transiciones invalidas con auditoria; suspension bloquea token emitido y login, no revive
  al reactivar; revocar usuario inmediata e idempotente, token sin claim incluido, usuario
  de otro tenant 404; revocar todo el tenant; claim authVersion mal formado; metricas;
  JWT tenant y anonimo sin acceso a las 8 rutas; ninguna respuesta con hash, token,
  version ni secreto), 2 PlatformTenantConcurrencyTest (2 suspensiones simultaneas, 5
  rondas, una gana; 10 hilos mezclando suspender y revocar todo, una suspension gana,
  revocaciones 200, version y auditoria exactas), 1 V11PlatformGovernanceMigrationTest
  (V10 con 2 tenants, uno inactivo, 2 usuarios y un administrador; V11 conserva filas,
  version 0, sin suspension, auth_version 0; CHECK y FK rechazan datos incoherentes).
* Primera suite completa: 595 con 1 fallo en RegistrationRequestTest: afirmaba que GET
  `/api/platform/tenants` estaba cerrado (403); 5A la abre. Se cambio a DELETE
  `/api/platform/tenants/1` (borrado de 5B, sigue 403).
* Ajustadas sin retirar aserciones: SchemaConformanceTest y FlywayMigrationTest esperan 19
  tablas; PlatformFailureTest pasa un TenantRepository simulado al filtro (constructor
  nuevo); RegistrationRequestTest como se indica arriba.
* PG16 (PostgreSQL 16.15 en contenedor desechable propio, puerto solo 127.0.0.1, clave
  efimera fuera del repo; cada corrida en una base NUEVA): corrida en run2 89/89
  (PlatformTenantTest 11, PlatformTenantConcurrencyTest 2, PlatformSecurityTest 36,
  AuthLoginIntegrationTest 9, RegistrationRequestTest 16, TenantProfileTest 15), Flyway
  11 de 11 exitosas y las 5 restricciones de V11 en pg_constraint. Una primera corrida en
  run1 no llego a ejecutar pruebas (fallo del script de PowerShell, no del producto) y se
  descarto.
* PG16 repeticiones: concurrencia en conc1 2/2 y conc2 2/2. Migracion V10 con datos a V11
  en mig1 1/1 y mig2 1/1 (11 migraciones registradas, 2 tenants, 2 usuarios con
  auth_version 0). Contenedor detenido y eliminado.
* Controles HTTP (arranque dev real en 18090, H2): V11 aplicada; el login demo emite
  authVersion 0; GET /api/tenant 200; con JWT tenant, las rutas de plataforma (tenants,
  metrics, suspend, revoke) 401; sin token 401; DELETE /api/platform/tenants/1 401. El
  flujo con JWT de plataforma no se probo por HTTP real: el bootstrap corre en otro
  proceso y el H2 en memoria no se comparte; queda cubierto por MockMvc en H2 y PG16.
  Proceso detenido.
* Sin navegador. No hay lint backend configurado.

## Limitaciones

* Cada peticion tenant agrega una lectura de TENANTS por PK (antes solo USUARIOS).
* Tokens tenant emitidos antes de V11 (sin claim) siguen validos hasta vencer (24 h) o
  hasta la primera revocacion o suspension.
* Repetir suspender con la misma version es 409, no un reintento idempotente como en 4A.
* La auditoria no tiene endpoint de lectura; se consulta por SQL.
* No se agrego estado intermedio (por ejemplo pendiente de borrado) para 5B.
* H2 2.4: un CHECK con lista IN (ck_auditoria_accion, y los de V9/V10) responde "Check
  constraint invalid" si la sesion que lo creo se cerro. Visto en la prueba de migracion
  con conexiones sueltas; se corrigio usando una sola conexion. En la app Flyway migra
  con el pool. No se verifico si en dev aparece cuando el pool recicla esa conexion
  (vida maxima de Hikari); PostgreSQL no tiene este problema.

## Contradicciones con documentacion anterior

* El prompt suponia auditoria de plataforma de la tarea 3; no existia. Resuelto con V11.
* 01-ARCHITECTURE y 03-DECISIONS (tarea 3) dejaban la version tenant pendiente para la
  tarea 5; queda resuelta y marcada asi.
