# Arquitectura

## Stack tecnologico

* Java 21, Maven, Lombok.
* Spring Boot 4.1.1: `webmvc`, `data-jpa`, `security`, `validation`.
* Hibernate ORM 7.4.5 (el que trae Spring Boot).
* JWT con `jjwt` 0.12.6 (`api`, `impl`, `jackson`).
* Flyway (`spring-boot-starter-flyway` y `flyway-database-postgresql`) para el esquema.
* H2 en modo PostgreSQL para local y tests; PostgreSQL 16 en el perfil `prod`. Ver Base de
  datos al final de este archivo.

## Integracion Spring Boot

Se usa la autoconfiguracion de Spring Boot salvo en tres puntos, cada uno descrito en su
seccion: el `PlatformTransactionManager` se reemplaza por
`TenantAwareJpaTransactionManager` (Estrategia multi-tenant), la cadena de seguridad la
define `SecurityConfig` (Capa de seguridad JWT) y la auditoria JPA se activa con
`JpaAuditingConfiguration`.

## Estrategia multi-tenant (id_tenant + filtro Hibernate)

Un solo esquema de base de datos para todos los tenants. Cada fila de negocio lleva una
columna discriminadora `id_tenant`. El aislamiento no se escribe en cada query: lo aplica
Hibernate. Motivo de la eleccion en [03-DECISIONS.md](03-DECISIONS.md).

Piezas:

**`tenant.Tenant` y `tenant.TenantRepository`**
Entidad maestra del inquilino (tabla `TENANTS`): `id` (Long, IDENTITY, columna `id_tenant`),
`name`, `slug` unico, `active`, `createdAt` (asignado en `@PrePersist`). Sus columnas son
las de TENANTS en el diccionario: sin `created_at` ni `updated_at`, la fecha es
`fecha_creacion_tenant`. Esta entidad NO extiende
`TenantAwareEntity`: es el catalogo global de tenants, no pertenece a ninguno.

**`shared.TenantAwareEntity`**
`@MappedSuperclass` del que extienden todas las entidades de negocio. Aporta:

* `id`: `Long` con `@GeneratedValue(IDENTITY)`. La columna es la PK del diccionario,
  `id_<entidad>`: cada entidad la fija con `@AttributeOverride(name = "id")`
  (`id_usuario`, `id_cuenta`, `id_movimiento`, `id_tercero`, `id_venta`...). El campo Java
  sigue siendo `id`.
* `tenantId`: `Long`, columna `id_tenant`, `nullable = false`, `updatable = false`, del
  mismo tipo que `Tenant.id`.
* `tenant`: `ManyToOne` LAZY a `Tenant` sobre la misma columna, de solo lectura
  (`insertable = false, updatable = false`) y sin getter ni setter. Solo existe para que
  la base tenga la FK `id_tenant -> TENANTS` del diccionario; el tenant se escribe y se
  lee por `tenantId`.
* `createdAt` y `updatedAt`: `LocalDateTime` con `@CreatedDate` y `@LastModifiedDate`,
  poblados por `@EntityListeners(AuditingEntityListener.class)`. La auditoria se activa
  globalmente con `@EnableJpaAuditing` en `config.JpaAuditingConfiguration`.

Un metodo `@PrePersist` en la clase base asigna `tenantId` automaticamente desde
`TenantContext` antes de cada insercion. **Ninguna entidad de negocio debe fijar
`tenantId` a mano**: si el codigo lo asigna, el valor se sobreescribe con el del contexto.

Si en ese momento `TenantContext.getTenantId()` es null, el metodo lanza
`IllegalStateException` con el mensaje "No se puede persistir una entidad sin un tenant
activo en el contexto". Es deliberado que reviente ahi: una fila con `id_tenant` nulo
queda invisible para el filtro de Hibernate de todos los tenants y aparece como dato
perdido mucho despues, cuando ya no hay forma de saber a quien pertenecia. Mejor que la
insercion falle de una vez y visible.

Esto cierra el ciclo con el filtro de lectura: el `@Filter` garantiza que nadie lea filas
de otro tenant, y el `@PrePersist` garantiza que nadie escriba una fila sin tenant.
Verificado con test de integracion sobre una entidad de prueba descartable, en los dos
casos: con contexto activo el `tenantId` queda asignado sin que el codigo lo toque, y sin
contexto el `save` lanza la excepcion. La entidad de prueba y su test se borraron despues
de confirmarlo.

Todos los identificadores del modelo son `Long`. Autollantas usa `Integer` en sus
entidades y no tiene auditoria; ambas divergencias son deliberadas, ver
[03-DECISIONS.md](03-DECISIONS.md).

Declara ademas el filtro de Hibernate:

```java
@FilterDef(name = "tenantFilter", parameters = @ParamDef(name = "tenantId", type = Long.class))
@Filter(name = "tenantFilter", condition = "id_tenant = :tenantId")
```

En Hibernate 7 el atributo `type` de `@ParamDef` es `Class<?>`, no `String`.
Los nombres viven en las constantes `TenantAwareEntity.TENANT_FILTER` y
`TenantAwareEntity.TENANT_PARAM`; usarlas en vez de literales.

**Propagacion del `@Filter`: verificada, propaga solo.**
Se comprobo con una entidad de prueba descartable (`TenantProbe`) que extendia
`TenantAwareEntity` sin declarar ningun `@Filter` propio, mas un test de integracion con
dos filas de tenants distintos. Con Hibernate 7.4.5 el filtro declarado en el
`@MappedSuperclass` se aplica a la entidad hija: cada tenant vio unicamente su fila.
La entidad de prueba y su test se borraron despues de confirmarlo.

Conclusion operativa: **no hay que repetir `@Filter` en cada entidad concreta.** Basta con
extender `TenantAwareEntity`. Si alguna vez se sube de version mayor de Hibernate, vale la
pena rehacer esta verificacion antes de confiar en el comportamiento.

**`tenant.TenantContext`**
Holder con `ThreadLocal<Long>` y metodos estaticos `setTenantId` / `getTenantId` /
`clear`. Constructor privado, clase final. Es el unico punto que responde "que tenant
esta atendiendo este hilo".

**Origen del tenant: el JWT.**
`TenantContext` lo puebla `auth.JwtAuthenticationFilter` a partir del claim `tenantId` del
token. La cabecera `X-Tenant-Id` y la clase `TenantFilter` que la leia fueron eliminadas,
ver la seccion Capa de seguridad JWT de este mismo archivo y [03-DECISIONS.md](03-DECISIONS.md).

**`tenant.TenantAwareJpaTransactionManager`**
Extiende `JpaTransactionManager` y sobreescribe `doBegin`: apenas arranca la transaccion,
toma el tenant de `TenantContext` y habilita el filtro sobre la sesion ligada al hilo.

```java
session.enableFilter(TENANT_FILTER).setParameter(TENANT_PARAM, tenantId);
```

Desde ahi ninguna query necesita escribir el `where id_tenant` a mano. Se registra como
bean `PlatformTransactionManager` en `TenantFilterConfiguration`, reemplazando al que
autoconfigura Spring Boot.

Engancha en la transaccion y no en un interceptor de Spring MVC a proposito: asi el
aislamiento aplica tambien fuera de un request HTTP (tests, tareas programadas, codigo
asincrono) y no depende de que `spring.jpa.open-in-view` siga activo. Todo acceso via
repositorios de Spring Data pasa por aqui, porque `SimpleJpaRepository` es transaccional.

**Callejones sin salida ya recorridos, no repetir:**

* *Interceptor de Spring MVC* (`HandlerInterceptor` que habilita el filtro en `preHandle`).
  Funciona solo dentro de un request y depende de `open-in-view`. En un `@SpringBootTest`
  sin web el filtro nunca se habilita y no hay aislamiento ninguno.
* *Proxy del `EntityManagerFactory`* (`BeanPostProcessor` que envuelve la EMF para
  habilitar el filtro al crear cada `EntityManager`). Rompe la gestion de transacciones de
  Spring: los `save` quedan sin flush, devuelven `id` null y no se escribe ninguna fila.

**Limitacion conocida:** el tenant se resuelve al iniciar la transaccion, asi que
`TenantContext` debe estar poblado antes. En un request lo garantiza
`JwtAuthenticationFilter`, que corre dentro de la cadena de Spring Security, antes de que
el controlador abra ninguna transaccion. Codigo que abra transacciones fuera de un request tiene
que poblar `TenantContext` explicitamente.

**`tenant.TenantFilterConfiguration`**
`@Configuration` que registra el bean `PlatformTransactionManager` con
`TenantAwareJpaTransactionManager`.

## Capa de seguridad JWT

### Identidad de plataforma (2026-10-30)

Paquete `platform`: `PlatformAdmin` y repositorio global propio, sin TenantAwareEntity,
sin tenant ficticio y sin cambios a Role. V8 agrega ADMINISTRADORES_PLATAFORMA. Bootstrap
privado explicito en el main, sin runner automatico, reserva id 1 mediante persist/INSERT;
una cuenta existente impide repetirlo y la PK impide reemplazo concurrente. Contrato de
entrada oculta/pipe y operacion local/contenedor en [DEPLOYMENT.md](../DEPLOYMENT.md).

Dos cadenas ordenadas y stateless. Primera: `/api/platform/**`, solo POST
`/api/platform/auth/login` publico; GET `/api/platform/auth/me`, GET
`/api/platform/registration-requests` y `/{id}`, POST `/{id}/approve`, `/{id}/reject` y
`/{id}/resend-activation` (4B); GET `/api/platform/tenants`, `/{id}`, `/{id}/users` y
`/api/platform/metrics`, POST `/{id}/suspend`, `/{id}/reactivate`, `/{id}/revoke-sessions` y
`/{id}/users/{userId}/revoke` (5A), todo con PLATFORM_IDENTITY; resto denegado. Segunda: login/registro
tenant, POST `/api/registration-requests` y POST `/api/registration-requests/activate` (4B),
y GET salud publicos, familias comerciales
actuales con TENANT_IDENTITY y resto denegado. El filtro tenant tiene registro servlet
automatico deshabilitado: solo corre dentro de su cadena, nunca en plataforma.
Ambos filtros limpian TenantContext al entrar y en finally, y no conservan SecurityContext.

JWT plataforma: clave PLATFORM_JWT_SECRET exclusiva, sub=id estable, iss=servibox-platform,
aud=[platform], identityType=PLATFORM, iat, exp, authVersion entero no negativo y sin
tenantId. Cada peticion consulta cuenta activa y version en base. Fallo de consulta no
autentica. Duracion inicial 900000 ms, configurable entre 1 y 86400000 ms, sin refresh.
Login generico `Credenciales invalidas`; respuesta {token,admin:{id,email}} y me {id,email}.
Nunca se devuelve la entidad, hash, clave ni dato comercial. No existe alta HTTP,
recuperacion publica, impersonacion ni gestion de administradores adicionales.

JWT tenant nuevo conserva firma, sub, tenantId y role y agrega iss=servibox-tenant,
aud=[tenant], identityType=TENANT. Heredado se admite hasta expirar solo si faltan juntos
iss, aud e identityType, con firma tenant, sub no blanco, tenantId entero positivo, rol
comercial conocido, iat y exp validos. Un contrato parcialmente marcado se rechaza.
Roles efectivos y usuario activo siguen viniendo de BD. Ninguna de las dos formas entra
en plataforma. Desde 5A (V11) el JWT tenant nuevo lleva `authVersion` (entero no negativo;
ausente cuenta como 0, asi siguen sirviendo heredados y emitidos antes de V11) y el filtro
exige que coincida con `usuarios.auth_version` y que el tenant siga activo (lectura de
TENANTS por PK). Revocar o suspender sube la version: los tokens ya emitidos fallan en la
siguiente peticion, sin esperar a que expiren.

### Panel de plataforma sobre tenants (5A)

`platform.PlatformTenantController` y `PlatformTenantService`, sin TenantContext: USUARIOS
se consulta siempre con id_tenant explicito. Estado del tenant: enum `tenant.TenantStatus`
(ACTIVE, SUSPENDED) derivado de `activo_tenant`, con las transiciones validas; las demas son
409. Suspender exige expectedVersion y motivo, bloquea la fila (PESSIMISTIC_WRITE como 4A),
sube `version_tenant` y la version de sesion de todos los usuarios del tenant (al reactivar
no reviven los tokens viejos). Reactivar exige expectedVersion y limpia fecha y motivo.
Revocar (un usuario o todo el tenant) es un UPDATE directo de auth_version, idempotente en
efecto y sin tocar updated_at. Toda escritura inserta una fila en AUDITORIA_PLATAFORMA.
Desde la tarea 51 (V12) tambien el reenvio de activacion de una solicitud: accion
RESEND_ACTIVATION con el administrador y `id_solicitud_objetivo`, sin tenant, en la misma
transaccion del reenvio. Sin endpoint de lectura.
Contrato y ejemplos en la sesion 2026-10-30-platform-tenants-users.

Busqueda por texto del panel (tarea 54): `q` de servitecas y `tenantName` y `contactName` de
solicitudes comparan con LIKE contiene contra columnas normalizadas por `shared.SearchText` (sin
tildes ni mayusculas, espacios colapsados, la ñ como n), que mantienen los setters de `Tenant` y
`RegistrationRequest`. El codigo (slug) se compara con `lower(slug)`. No hay indice: un B-tree no
sirve con comodin inicial. Usuarios de una serviteca no tiene busqueda por texto. Detalle en la
sesion 2026-11-01-accent-insensitive-search.

Autenticacion por token, sin sesion de servidor. El tenant no lo elige el cliente: viaja
firmado dentro del token.

### Flujo completo

1. **`POST /api/auth/login`** publico por metodo/ruta exactos, igual que registro tenant
   y salud; login plataforma pertenece a su cadena propia. Body: `tenantSlug`, `username`, `password`.
2. **`auth.AuthController`** busca el tenant por `slug`, luego el usuario con
   `findByUsernameAndTenantId`, y compara la contrasena con
   `passwordEncoder.matches(...)` contra el `passwordHash` BCrypt.
3. Si algo no cuadra responde **401 con el mismo mensaje generico** en todos los casos
   (tenant inexistente, usuario inexistente, usuario inactivo, contrasena incorrecta).
   Distinguirlos le confirmaria a un atacante que usuarios existen en que tenant. Desde la
   tarea 51 los caminos sin contrasena real (tenant inexistente o suspendido, usuario
   inexistente o inactivo; en plataforma tambien clave de mas de 72 bytes) pagan una
   comparacion BCrypt de relleno (`auth.LoginTimingEqualizer`, hash fijo generado al
   arrancar con el mismo encoder y costo) para que el tiempo tampoco lo distinga.
4. Si cuadra, **`auth.JwtService.generateToken(user)`** emite un JWT firmado con HMAC SHA
   con los claims `sub` (username), `tenantId`, `role` y marcadores tenant descritos arriba,
   y expiracion segun configuracion.
   La respuesta es `LoginResponse`: `{"token", "user": {"username", "role", "fullName"},
   "tenant": {"name", "slug"}}`, armada con el usuario y el tenant que el login ya verifico.
   `fullName` es null en los usuarios creados antes del registro publico.
5. En cada request posterior el cliente manda `Authorization: Bearer <token>`.
6. **`auth.JwtAuthenticationFilter`** (registrado antes de
   `UsernamePasswordAuthenticationFilter`) valida firma y expiracion. Con el token bueno:
   pone `TenantContext.setTenantId(tenantId del token)`, **consulta el usuario en base con
   `findByUsernameAndTenantId` y confirma que siga existiendo y activo**, y recien
   entonces puebla el `SecurityContextHolder` con el username y la authority
   `ROLE_<rol>`. Si el token es invalido o vencido, o el usuario ya no existe o esta
   inactivo, no autentica y limpia todo; la cadena de seguridad responde 401.

   El tenant se pone **antes** de la consulta a proposito: asi el filtro de Hibernate ya
   esta activo y la busqueda del usuario no puede cruzar de tenant. Y la authority sale
   del rol **en base**, no del claim del token, para que un cambio de rol tampoco tenga
   que esperar a que el token expire.
7. Al abrir la transaccion, `TenantAwareJpaTransactionManager` toma ese `TenantContext` y
   habilita el filtro de Hibernate, ver
   la seccion Estrategia multi-tenant de este mismo archivo.

En resumen: `login -> JWT con tenantId -> JwtAuthenticationFilter -> TenantContext ->
filtro de Hibernate`. El tenant nunca lo aporta el cliente en claro.

**`TenantContext.clear()` va en un `finally`** dentro del filtro. El pool de hilos de
Tomcat se reutiliza entre requests: un tenant que quede pegado se filtraria al siguiente
request atendido por ese hilo.

### Registro publico de una serviteca

**`POST /api/auth/register`**, publico. Crea un tenant nuevo con su administrador y
responde **201 con la misma forma que el login** (token, `user`, `tenant`), asi que el
frontend entra directo sin un segundo paso.

Body: `businessName`, `nit`, `phone`, `slug`, `adminFullName`, `adminEmail`,
`adminUsername`, `password`, `acceptTerms`. Sin `@Valid`: `auth.TenantRegistrationService`
valida cada campo y responde 400 con un mensaje por regla (reglas en
[03-DECISIONS.md](03-DECISIONS.md)). Un slug que ya existe, comparado en minusculas, es
409 "Ya existe una serviteca con el identificador X".

Flujo, todo en **una transaccion** (`@Transactional` del servicio):

1. Si `servibox.registration.enabled` es false: 403 y nada mas.
2. Validacion de los campos; slug repetido (`existsBySlugIgnoreCase`): 409.
3. Inserta el tenant (activo, nombre, NIT, telefono, slug). Si otro registro simultaneo
   gano la carrera, la violacion de `uk_tenant_slug` se traduce al mismo 409.
4. `TenantContext.setTenantId(nuevo)` y el filtro de Hibernate habilitado a mano sobre la
   sesion (la transaccion empezo sin tenant, asi que `TenantAwareJpaTransactionManager` no
   lo hizo). Es el mismo mecanismo que usa `DevDataInitializer` al arrancar.
5. Por los services de siempre: el usuario ADMIN (contrasena con el `PasswordEncoder` del
   login), el IVA 0.19 (`InventoryService.crearIvaPorDefecto`, el mismo que la siembra dev),
   las categorias LLANTAS, LUBRICANTES y BATERIAS (amarillo 10, rojo 3, margen 0, con el
   IVA), la cuenta Caja General (EFECTIVO, saldo 0) y el tercero Consumidor final.
6. `JwtService.generateToken(admin)` y `LoginResponse.from(...)`. `TenantContext.clear()` en
   un `finally`.

Cualquier excepcion en el camino deshace todo: no quedan tenants, usuarios ni catalogos a
medias. No se crea configuracion financiera: `GET /api/inventory/financial-settings` da
ceros hasta que el taller la guarde.

### Perfil del tenant

`tenant.TenantController`, con JWT. Siempre el tenant del token (`TenantContext`), por id:
TENANTS no es una entidad por tenant y el filtro de Hibernate no aplica.

* **`GET /api/tenant`**: cualquier rol. `{"name", "slug", "nit", "phone"}`; `nit` y `phone`
  son null solo en los tenants anteriores a que fueran obligatorios, hasta que editen su
  perfil.
* **`PUT /api/tenant`**: body `name`, `nit`, `phone`; devuelve el perfil guardado. Solo
  ADMIN: el controlador mira la authority `ROLE_ADMIN` que pone `JwtAuthenticationFilter`
  (rol en base) y si no esta lanza `AdminRequiredException`, 403 "Solo un administrador
  puede editar los datos de la serviteca", antes de validar nada. Las reglas son las del
  registro (`TenantRegistrationService.validarNombreComercial`, `validarNit`,
  `validarTelefono`, 400 con el mismo mensaje, un error por peticion): `name`, `nit` y
  `phone` obligatorios, en ese orden ("El NIT es obligatorio", "El telefono es
  obligatorio"), y se guardan recortados. Un tenant antiguo con `nit` o `phone` null tiene
  que mandar los dos para editar. En la base las dos columnas siguen admitiendo null (sin
  migracion, ver 03-DECISIONS.md). El `slug` no esta en el cuerpo; si llega, se ignora.

Sin token, los dos son 401 como cualquier ruta protegida. El nombre editado sale en el
siguiente login y en el encabezado de los reportes.

### Verificacion de usuario activo en cada request

La firma de un JWT solo prueba que el token se emitio en algun momento; no dice nada del
estado actual del usuario. Sin la consulta del paso 6, desactivar a un empleado no surtiria
efecto hasta que su token venciera, hasta 24 horas despues. Por eso cada request protegido
paga una consulta a `USUARIOS` y, desde 5A, otra a `TENANTS` por PK. Motivo completo en
[03-DECISIONS.md](03-DECISIONS.md).

**Costo conocido:** es una consulta extra por request autenticado. Hoy es irrelevante, es
una lectura por clave unica `(id_tenant, username)` con indice. Si algun dia el volumen lo
justifica, la salida tipica es una cache corta en memoria del estado del usuario, con TTL
de segundos, o una lista de tokens revocados. No esta implementado ni hace falta todavia.

### Manejo centralizado de errores

`shared.GlobalExceptionHandler` es un `@RestControllerAdvice` y es el unico lugar que
construye respuestas de error. Todas usan el mismo formato, el record
`shared.ErrorResponse`:

```json
{"error": "mensaje", "status": 401}
```

| Excepcion | Codigo | Cuerpo |
|-|-|-|
| `DomainValidationException` (id del cuerpo que no resuelve, rango de fechas invalido; tarea 52) | 400 | mensaje de la excepcion |
| `IllegalStateException` (el `@PrePersist` sin tenant activo, configuracion, librerias) | 400 | "Peticion invalida" (detalle solo al log, tarea 52) |
| Otra `IllegalArgumentException` (librerias o programacion) | 400 | "Peticion invalida" (detalle solo al log, tarea 52) |
| `InvalidTransferException`, `InvalidSaleOperationException`, `InvalidPurchaseOperationException` | 400 | mensaje de la excepcion |
| `DuplicateProductCodeException`, `DuplicateAccountNameException`, `DuplicateInvoiceNumberException`, `DuplicatePurchaseInvoiceNumberException`, `DuplicateCounterpartyDocumentException` | 409 | mensaje de la excepcion |
| `InvalidRegistrationException` (dato del registro publico) | 400 | mensaje de la regla |
| `DuplicateTenantSlugException` (slug ya registrado) | 409 | "Ya existe una serviteca con el identificador X" |
| `RegistrationDisabledException` (`servibox.registration.enabled=false`, o solicitudes de registro abiertas) | 403 | "El registro de nuevas servitecas esta deshabilitado" |
| `RegistrationRequestConflictException` (solicitudes de registro) | 409 | "El codigo solicitado no esta disponible", "La clave de reintento ya se uso con otros datos", "La solicitud ya no permite esta operacion" o "El recurso cambio; actualiza e intenta de nuevo" |
| `InvalidRegistrationException` (consentimiento de la solicitud de registro, tarea 55) | 400 | "La version de la politica es obligatoria", "La version de la politica no es valida" o "La politica fue actualizada; recarga la pagina para continuar" |
| `PlatformTenantConflictException` (suspender o reactivar, 5A) | 409 | "El recurso cambio; actualiza e intenta de nuevo", "El tenant ya esta suspendido" o "El tenant ya esta activo" |
| `ActivationException` (enlace de activacion, 4B) | 400, 410 o 409 | "El enlace de activacion no es valido" (400), "El enlace de activacion vencio o fue reemplazado" (410), "El enlace de activacion ya fue utilizado; inicia sesion" (409) |
| `ResendLimitException` (reenvio de activacion, 4B) | 429 | "Espera un momento antes de reenviar la activacion" o "Se alcanzo el limite diario de reenvios de esta solicitud" |
| `RateLimitExceededException` (limite por IP de las rutas publicas de solicitudes, tarea 49, y bloqueo de logins, tarea 50) | 429 con `Retry-After` | "Demasiadas solicitudes desde esta conexion, intenta de nuevo mas tarde" (crear), "Demasiados intentos de activacion, espera unos minutos e intenta de nuevo" (activar) o "Demasiados intentos de inicio de sesion, espera unos minutos e intenta de nuevo" (ambos logins) |
| `AdminRequiredException` (sin rol ADMIN: perfil del tenant, configuracion financiera, alta o edicion de categorias) | 403 | "Solo un administrador puede editar los datos de la serviteca", "...editar la configuracion financiera" o "...crear o editar categorias" |
| `SystemCounterpartyException` (editar el Consumidor final o usar su documento) | 409 | mensaje de la excepcion |
| `InsufficientStockException`, `InsufficientBalanceException` | 409 | mensaje de la excepcion; los montos de saldo con `Moneda.formatear` ("hay $ 3.250.000 y se requieren $ 4.212.600") |
| `PessimisticLockingFailureException` de Spring, `PessimisticLockException` y `LockTimeoutException` de JPA y `PessimisticLockException` de Hibernate (bloqueo de fila no obtenido: espera agotada o interbloqueo) | 409 | "El recurso esta siendo modificado por otra operacion, intenta de nuevo" |
| `InvalidCredentialsException` y `AuthenticationException` | 401 | "Credenciales invalidas" |
| Denegacion de autorizacion en la cadena de seguridad | 403 | "Acceso denegado", JSON directo sin despacho a /error |
| `MethodArgumentNotValidException` (fallo de `@Valid`) | 400 | "Peticion invalida" |
| `HttpMessageNotReadableException` (JSON mal formado o valor del tipo equivocado) | 400 | Si el valor es de un enum: "Valor invalido 'X' en el campo f. Valores permitidos: ..."; si no, un mensaje generico |
| `MethodArgumentTypeMismatchException` (parametro de query o de ruta del tipo equivocado, por ejemplo `?category=X`) | 400 | "Valor invalido 'X' en el parametro p", mas "Valores permitidos: ..." si es un enum |
| `ResourceNotFoundException` | 404 | mensaje de la excepcion |
| `NoResourceFoundException` | 404 | "Recurso no encontrado" |
| `Exception` (ultimo recurso) | 500 | "Error interno del servidor" |

El fallback de 500 registra el detalle en el log del servidor y **nunca lo devuelve al
cliente**: un stacktrace en la respuesta le regala al atacante versiones de librerias y
rutas internas.

`AuthController` no arma respuestas de error: lanza `InvalidCredentialsException` en los
cuatro casos de login fallido y el advice la traduce. Asi el mensaje generico se define en
un solo sitio.

Los rechazos de la cadena de seguridad ocurren antes de llegar a un controlador, asi que el
`@RestControllerAdvice` no los ve. `config.JsonAuthenticationEntryPoint` cubre ese caso
escribiendo el mismo formato, para que el cliente no tenga que parsear dos formas distintas
de error.

### Configuracion

`SecurityConfig` (paquete `config`): sesion `STATELESS`, CSRF deshabilitado (es una API
pura, sin formularios ni cookies de sesion), `BCryptPasswordEncoder` como bean, sin
`UserDetailsServiceAutoConfiguration` (excluida en `ServiboxApplication`, tarea 52), HSTS de
180 dias en todas las respuestas solo con el perfil prod, CORS solo para los origenes
exactos de CORS_ALLOWED_ORIGINS (vacia: sin CORS; prod exige https; despliegue VPS),
`permitAll` en `POST /api/auth/login`, `POST /api/auth/register` y `GET /api/health`, y
TENANT_IDENTITY en las familias comerciales enumeradas; resto denegado. Cadena de
plataforma separada descrita arriba, con login publico propio.

Propiedades en `application.properties`:

```properties
servibox.jwt.secret=${JWT_SECRET:dev-secret-cambiar-en-produccion-minimo-32-caracteres}
servibox.jwt.expiration-ms=${JWT_EXPIRATION_MS:86400000}
servibox.business.time-zone=${BUSINESS_TIME_ZONE:America/Bogota}
servibox.registration.enabled=${REGISTRATION_ENABLED:true}
```

`servibox.registration.enabled` apaga el registro publico (403) sin desplegar codigo.
En el perfil prod el registro inmediato no existe: `TenantRegistrationService.registrar`
responde 404 `Recurso no encontrado` antes de mirar cualquier compuerta (tarea 51); dev y
test conservan el comportamiento de abajo.
`servibox.registration-requests.enabled` (REGISTRATION_REQUESTS_ENABLED, default false)
abre POST `/api/registration-requests` y, mientras esta abierta, el registro inmediato
responde 403: nunca hay dos vias publicas activas. Plazos configurables en
[DEPLOYMENT.md](../DEPLOYMENT.md); contrato en la sesion 2026-10-30-registration-requests.
La misma compuerta abre POST `/api/registration-requests/activate` (4B); apagada, 404.
`servibox.registration-requests.policy-version` (REGISTRATION_POLICY_VERSION, default
2026-11-01, tarea 55) es la version vigente de la politica y terminos, AAAA-MM-DD; prod no
arranca si no es una fecha valida. La solicitud publica debe traerla exacta en `policyVersion`;
se guarda con la hora del servidor en UTC (V14), sin IP ni agente de usuario, y el detalle de
plataforma la expone. Un reintento con la misma clave y el mismo cuerpo se resuelve antes de
comparar con la vigente. Ver [03-DECISIONS](03-DECISIONS.md).

Activacion y correo (4B): aprobar y reenviar emiten un token opaco, guardan su SHA-256 en
ACTIVACIONES_REGISTRO y encolan el correo en CORREOS_SALIENTES en la misma transaccion;
nunca envian. `mail.OutboxWorker` (tarea programada solo en el servidor HTTP) reclama con
`FOR UPDATE SKIP LOCKED` mas lease condicional y envia por `mail.MailTransport` fuera de
transaccion; sin SMTP_HOST no hay transporte y lo pendiente espera. `SMTP_SECURITY` elige
STARTTLS (por defecto) o TLS implicito (ssl, 465), con verificacion de certificado y de nombre.
`mail.OutboxPurger` (misma programacion) borra por lotes SENT y FAILED viejos con SKIP LOCKED;
`OutboxCipher` acepta una clave anterior solo para descifrar (rotacion). Activar provisiona con
`TenantRegistrationService.provisionar`, el mismo codigo del registro inmediato. Contrato en
la sesion 2026-10-30-activation-outbox-email.

Limite de tasa (tarea 49): `registration.PublicRateLimiter` lo aplica en el controlador,
despues de la compuerta y antes del servicio, con una `FixedWindowRateLimiter` por ruta
(ventana fija por IP en un `ConcurrentHashMap`, purga perezosa, tope de entradas que falla
cerrado, reloj inyectable). IP del socket salvo cabecera de proxy configurada. Solo esas dos
rutas; ni login ni plataforma. Propiedades `servibox.rate-limit.*` en
[DEPLOYMENT.md](../DEPLOYMENT.md); el perfil test lo apaga y sus pruebas lo encienden.

Fuerza bruta en logins (tarea 50): `auth.LoginThrottle` envuelve POST `/api/auth/login` y POST
`/api/platform/auth/login` en sus controladores. Tres `FixedWindowRateLimiter` (par IP +
cuenta, cuenta, IP total) con `reserve`/`release`/`reset`: cada intento reserva una unidad en
las tres antes de evaluar; `InvalidCredentialsException` la deja como fallo, el exito limpia el
par y devuelve las otras dos, otro error devuelve las tres. Cuota llena: 429 sin evaluar la
contrasena. IP con `PublicRateLimiter.clientIp`. Propiedades `servibox.login-throttle.*` en
[DEPLOYMENT.md](../DEPLOYMENT.md); el perfil test lo apaga. En prod, `ProductionConfiguration`
registra un WARN si `servibox.rate-limit.client-ip-header` esta vacia con algun limite encendido.

`servibox.business.time-zone` es la zona de negocio: `config.BusinessClockConfiguration`
expone con ella el bean `Clock` (`businessClock`) del que sale todo "hoy" de negocio. Ver
[03-DECISIONS.md](03-DECISIONS.md), "Zona horaria de negocio y fecha de abonos y pagos".

El valor por defecto del secret es solo para desarrollo local. **En produccion `JWT_SECRET`
tiene que venir de una variable de entorno real**, nunca el valor de respaldo.
ProductionConfiguration valida antes de crear los beans de aplicacion que el secreto
efectivo en prod no sea blanco, corto (menos de 32 bytes UTF-8) ni el fallback dev.
El perfil prod sobreescribe el fallback con JWT_SECRET vacio si falta, para rechazarlo.
GET /api/health solo confirma disponibilidad HTTP; no consulta la base. Contenedor,
puerto PORT (SERVER_PORT tiene precedencia), variables y operacion en
[DEPLOYMENT.md](../DEPLOYMENT.md).
Despliegue en VPS (Docker Compose, PostgreSQL 16, Caddy, respaldos) en
[deploy/VPS.md](../deploy/VPS.md).

### Modelo de usuario

`auth.entity.User` extiende `TenantAwareEntity`: `username`, `passwordHash`, `email`,
`fullName` (desde V2, null en los anteriores), `active`, y `role` como enum `auth.entity.Role` (`ADMIN`, `MECANICO`, `CAJERO`, los del
diccionario) con `@Enumerated(STRING)`. Motivo del enum en vez de tabla en
[03-DECISIONS.md](03-DECISIONS.md). El rol viaja en el JWT y como authority `ROLE_<rol>`,
con permisos ADMIN existentes para editar perfil, configuracion financiera y categorias.
La autoridad de identidad comercial es independiente de esos roles.

La restriccion unica es **compuesta**, `(id_tenant, username)`, no `unique` en la columna
sola: dos negocios distintos pueden tener cada uno su usuario `admin`.

Consecuencia de eso: el login necesita saber a que tenant entrar antes de poder resolver
el username, por eso `LoginRequest` lleva `tenantSlug` ademas de usuario y contrasena.

### Datos de prueba local

`config.DevDataInitializer` es un `CommandLineRunner` con `@Profile("dev")`: si la tabla
de tenants esta vacia crea el tenant `demo` y el usuario `admin` (`admin123`), y para ese
tenant el `TaxType` IVA (0.19, `isVat` true), las 3 categorias base del registro publico
(LLANTAS, LUBRICANTES, BATERIAS) con IVA y las 2 cuentas por defecto (ver Modulo Treasury).
Solo para probar en local. Datos de demostracion en
[02-CONVENTIONS.md](02-CONVENTIONS.md).

## Estructura de paquetes

Base: `com.servibox.backend`. Un paquete por modulo de negocio, mas tres transversales.

| Paquete | Contenido |
|-|-|
| `config` | `SecurityConfig`, `JpaAuditingConfiguration`, `JsonAuthenticationEntryPoint`, `DevDataInitializer` |
| `tenant` | `Tenant`, `TenantStatus`, `TenantRepository`, `TenantContext`, `TenantAwareJpaTransactionManager`, `TenantFilterConfiguration` |
| `platform` | identidad de plataforma (tarea 3) y panel de tenants (5A): `PlatformTenantController`, `PlatformTenantService`, `PlatformAuditEntry`; global, sin tenant |
| `shared` | `TenantAwareEntity`, `ErrorResponse`, `GlobalExceptionHandler`, `ResourceNotFoundException` |
| `auth` | `JwtService`, `JwtAuthenticationFilter`, `AuthController`, `TenantRegistrationService`, `InvalidCredentialsException` y las tres excepciones del registro, mas `entity` / `repository` / `dto` |
| `registration` | solicitudes de registro (4A): `RegistrationRequest`, su servicio, el controlador publico y el de revision de plataforma; activacion (4B): `RegistrationActivation` y `RegistrationActivationService`; global, sin tenant |
| `mail` | outbox de correo (4B): `OutboxMessage`, `MailOutbox` (encolar), `OutboxWorker` (enviar), `OutboxPurger` (purga, tarea 51), `OutboxCipher` (AES-GCM, clave anterior opcional), `MailTransport` y `SmtpMailTransport`; global, sin tenant |
| `inventory` | primer modulo de negocio migrado, ver abajo |
| `treasury` | segundo modulo de negocio migrado, ver abajo |
| `counterparties` | terceros (`Counterparty`): clientes y proveedores en una sola entidad, compartida por `sales` y `purchases`, ver abajo |
| `sales` | tercer modulo de negocio migrado, ver abajo |
| `purchases` | cuarto modulo de negocio migrado, ver abajo |
| `reporting` | quinto paquete de negocio y ultimo modulo del roadmap, solo lectura, ver abajo |

Los modulos de negocio usan siempre las mismas cinco capas:
`controller`, `service`, `repository`, `entity`, `dto`. Las excepciones de dominio de cada
modulo viven en su paquete `service`.

### Modulo Inventory

Migrado desde el modulo `inventory` de Autollantas. Todas sus entidades extienden
`TenantAwareEntity`, asi que heredan `id`, `tenantId`, auditoria, y el filtro por tenant.

**Entidades**

* `ProductCategory` (`CATEGORIA_PRODUCTOS`): `name`, `color`, `yellowStockMin`,
  `redStockMin`, `targetMargin` (fraccion, 0.30 es 30 por ciento), todos obligatorios, y
  `taxTypes`, un `ManyToMany` EAGER contra `TaxType` por la tabla puente
  `CATEGORIA_IMPUESTOS`. Es un `Set` para que la tabla tenga la PK compuesta
  `(id_categoria_producto, id_impuesto)` del diccionario. `CategoryRequest` exige todos
  los campos salvo los impuestos.
* `TaxType` (`TIPOS_IMPUESTO`): `name`, `rate`, `isVat`, `appliesToTransaction`,
  `description`.
* `Product` (`PRODUCTOS`): `code`, `description`, `purchaseCost`, `quantity`, `taxAmount`,
  `suggestedPrice`, y `ManyToOne` a `ProductCategory`. Restriccion unica **compuesta**
  `uk_producto_tenant_code` sobre `(id_tenant, codigo_producto)`, mismo criterio que
  `(id_tenant, username)` en `User`: dos talleres distintos pueden usar cada uno el codigo
  `LLA-001` para productos que no tienen nada que ver.

  La unicidad se defiende en dos capas. `InventoryService.saveProduct` consulta
  `findByCodeAndTenantId` antes del INSERT y lanza `DuplicateProductCodeException`, que
  `GlobalExceptionHandler` traduce a **409 Conflict** con un mensaje legible. La
  restriccion de base queda igual como red de seguridad para cualquier escritura que no
  pase por el service. Al editar, la validacion excluye el propio registro: volver a
  guardar un producto sin cambiarle el codigo no es un duplicado.
* `FinancialSettings` (`CONFIGURACION_FINANCIERA`): `expensesRate`, `dianRate`, `icaRate`,
  `cardCommissionRate`, todas fracciones (`icaRate` por mil). En Autollantas es un
  singleton con id fijo 1; aqui es **una fila por tenant**, porque cada negocio tiene sus
  propios porcentajes: restriccion unica `uk_configuracion_tenant` sobre `id_tenant`, y
  `InventoryService.saveFinancialSettings` rechaza una segunda con
  `DuplicateFinancialSettingsException` (**409**). `cardCommissionRate` es obligatoria
  (NOT NULL) y vale 0.0 por defecto; todavia no entra en ningun calculo.

**Calculo de precios** (`InventoryService.recalculatePrices`, portado de Autollantas)

```java
taxAmount     = purchaseCost * suma(rates de la categoria con isVat true)
k             = (1 - expensesRate) * (1 - dianRate)
divisor       = k - icaRate - targetMargin
suggestedPrice= divisor > 0 ? purchaseCost * k / divisor : purchaseCost
```

Se calcula en `BigDecimal` y solo el resultado final de `taxAmount` y de `suggestedPrice`
se redondea al peso entero, la mitad hacia arriba, con `Moneda.aPesosEnteros` (desde
fix/38; V5 redondeo los ya guardados). Todos los puntos que rehacen precios (crear y editar
producto, compras y su anulacion o recuperacion, categoria, margen y configuracion
financiera) pasan por `aplicarPrecios`, asi que no hay otro redondeo.

`suggestedPrice` esta portado sin cambios: el margen es sobre el **precio de venta**, no
sobre el costo, y por eso aparece despejado en el divisor. Cuando el margen mas el ICA
superan lo que dejan Gastos y DIAN no existe precio finito que cumpla, y se usa el costo
como piso.

`taxAmount` es la **unica divergencia deliberada** respecto de Autollantas: alli suma
todas las rates de la categoria, aqui solo las marcadas `isVat`. Motivo en
[03-DECISIONS.md](03-DECISIONS.md).

`updateCategoryMargin` es el equivalente del tab Margenes de Utilidad: cambia el margen de
la categoria y rehace el precio sugerido de todos sus productos.

**Endpoints** (todos requieren JWT)

* `GET` y `POST` `/api/inventory/categories`. El POST exige rol ADMIN (**403**) y aplica
  las mismas reglas que el PUT: nombre repetido **409**, `taxTypeId` ajeno **400** y rangos.
* `PUT /api/inventory/categories/{id}`: mismo cuerpo que el POST (`CategoryRequest`). Solo
  ADMIN (**403**, antes de validar el cuerpo). Reemplaza todos los campos y el conjunto de
  impuestos, y rehace los precios de los productos de la categoria. Nombre repetido en el
  tenant (sin contar la propia) **409**; `taxTypeId` que no es del tenant **400**;
  categoria de otro tenant **404**. Rangos en POST y PUT (**400**): umbrales no negativos,
  rojo no mayor que amarillo, margen en [0, 1), nombre hasta 100.
* `GET` y `POST` `/api/inventory/products`. Codigo hasta 50, descripcion hasta 255, costo y
  stock no negativos (**400**, tambien en el PUT salvo el stock). POST y PUT devuelven
  `taxAmount` y `suggestedPrice` en pesos enteros, como el GET.
* `GET /api/inventory/products/{id}`
* `PUT /api/inventory/products/{id}`: `code`, `description`, `purchaseCost`, `categoryId`
  (`ProductUpdateRequest`). `quantity` no se edita: si llega se ignora. Codigo repetido
  **409**; cambiar el codigo de un producto con lineas de venta o de compra **409**
  (`ProductCodeLockedException`); categoria de otro tenant **400**; producto de otro tenant
  **404**. Responde con `ProductResponse` y los precios recalculados.
* `GET /api/inventory/tax-types`: impuestos del tenant por nombre (`TaxTypeResponse`). Solo
  lectura.
* `GET` y `PUT` `/api/inventory/financial-settings` (`FinancialSettingsResponse`): sin fila
  guardada el GET devuelve todo en cero con 200. El PUT crea o actualiza la unica fila del
  tenant por `saveFinancialSettings` (nunca 409); solo ADMIN (**403**, antes de validar el
  cuerpo), con las tasas entre 0 y 1 (los CHECK
  `ck_configuracion_*`; fuera de rango **400**), y rehace los precios de todos los
  productos del tenant.

Los recalculos masivos (`InventoryService.recalcularPrecios`) leen la configuracion una
vez y los productos en una consulta; Hibernate escribe un UPDATE por producto cambiado.

Las respuestas van siempre por DTO (`CategoryResponse`, `ProductResponse`,
`TaxTypeResponse`), nunca la entidad JPA: exponerla arrastraria `tenantId` y las
relaciones EAGER completas al cliente.

### Modulo Treasury

Migrado desde el paquete `treasury` de Autollantas (`model` / `repository` / `service`).
Todas sus entidades extienden `TenantAwareEntity`.

**Entidades**

* `Account` (`CUENTAS`): `name`, `initialBalance`, `currentBalance`, `type` (enum
  `AccountType`: `EFECTIVO`, `BANCO` o `DIGITAL`, los del diccionario; antes `CASH` y
  `BANK`). Restriccion unica **compuesta** `uk_cuenta_tenant_name`
  sobre `(id_tenant, nombre_cuenta)`, mismo criterio que `(id_tenant, username)` en `User`
  y `(id_tenant, codigo_producto)` en `Product`: cada taller tiene su propia "Caja
  General".

  Igual que con el codigo de producto, la unicidad se defiende en dos capas.
  `TreasuryService.saveAccount` consulta `findByNameAndTenantId` antes del INSERT y lanza
  `DuplicateAccountNameException`, que `GlobalExceptionHandler` traduce a **409 Conflict**
  con un mensaje legible. La restriccion de base queda como red de seguridad para
  cualquier escritura que no pase por el service. Al editar, la validacion excluye el
  propio registro.

  Una cuenta nueva arranca con `currentBalance` igual a su `initialBalance`: mientras no
  haya movimientos, el saldo vivo es el saldo de apertura.
* `Movement` (`MOVIMIENTOS`): **el libro de movimientos**, y la unica entidad que mueve
  dinero. Gastos operativos, ingresos ocasionales y transferencias (paso 2 del refactor
  de normalizacion) y abonos y pagos de facturas (paso 3) **no tienen tabla propia**: son
  movimientos con su categoria. Ver [04-DATA-MODEL.md](04-DATA-MODEL.md), MOVIMIENTOS.

  | Campo | Columna | Notas |
  |-|-|-|
  | `account` | `id_cuenta` | `ManyToOne` a `Account` |
  | `type` | `tipo_movimiento` | Enum `MovementType`: solo `INGRESO` o `EGRESO`. Es lo que da el signo sobre el saldo |
  | `category` | `categoria_movimiento` | Enum `MovementCategory`, obligatorio (NOT NULL en la base) |
  | `concept` | `concepto` | Obligatorio, 255. Columna real, divergencia deliberada respecto de Autollantas |
  | `amount` | `monto` | Obligatorio, siempre positivo (`ck_movimiento_monto`) |
  | `date` | `fecha_movimiento` | Fecha de negocio (`LocalDate`), no de auditoria |
  | `notes` | `notas` | Opcional, 255 |
  | `relatedMovement` | `id_movimiento_relacionado` | `ManyToOne` a `Movement`, clave foranea real. Solo en la pata INGRESO de una transferencia, apuntando a la pata EGRESO. Excluido de `toString` y `equals` |
  | `sale` | `id_venta` | `ManyToOne` a `Sale`, FK real `fk_movimiento_venta`, sin cascada. Solo en `RECAUDO_VENTA` |
  | `purchase` | `id_compra` | `ManyToOne` a `Purchase`, FK real `fk_movimiento_compra`, sin cascada. Solo en `PAGO_COMPRA` |

  Restriccion `CHECK` `ck_movimiento_factura`: `RECAUDO_VENTA` lleva venta y no compra,
  `PAGO_COMPRA` compra y no venta, y las demas categorias ninguna de las dos.
  `TreasuryService` valida lo mismo antes de escribir; la base es la red de seguridad.
  `CHECK` `ck_movimiento_monto`: `monto > 0`. `id_cuenta`, `tipo_movimiento`, `concepto`,
  `monto` y `fecha_movimiento` son NOT NULL.

  **Categorias** (`MovementCategory`) y su tipo, validado en `TreasuryService`:

  | Categoria | Tipo | Quien la crea | Manual |
  |-|-|-|-|
  | `GASTO_OPERATIVO` | `EGRESO` | El usuario: arriendo, servicios, papeleria | Si |
  | `INGRESO_OCASIONAL` | `INGRESO` | El usuario: chatarra, reintegros, aporte del socio | Si |
  | `TRANSFERENCIA` | `EGRESO` en el origen, `INGRESO` en el destino | `registrarTransferencia` | No |
  | `PAGO_COMPRA` | `EGRESO` | La compra de contado y cada pago, con `purchase` | No |
  | `RECAUDO_VENTA` | `INGRESO` | La venta de contado y cada abono, con `sale` | No |

  Solo las dos manuales se crean, editan y eliminan directamente. Las demas se gestionan
  desde su operacion; editarlas o eliminarlas a mano es **400** y no toca nada.

  **Movimiento activo.** Un movimiento enlazado a una venta o compra `ANULADA` es
  **inactivo**: sigue en la tabla, pero no sale en ningun listado ni cuenta en ninguna
  suma. Todos los demas son activos. No se guarda en una columna: se deriva del estado de
  la factura con `MovementSpecifications.activo()`, el unico predicado que lo define, y
  por el que pasan todas las consultas que listan o suman movimientos. Invariante:
  `currentBalance` = `initialBalance` + movimientos activos (ingresos suman, egresos
  restan); los tests lo comprueban con `SalesFixture.verificarInvariante()`.

**`TreasuryService`**

* `aplicarMovimiento(movimiento)` (privado): **el unico sitio donde se guarda un
  `Movement`**, nuevo o editado, junto con su efecto sobre el `currentBalance` de su
  cuenta. Antes valida la coherencia entre tipo, categoria y factura. El saldo solo lo
  escriben `editarCuenta` (diferencia del saldo inicial) y dos metodos privados:
  `aplicarEfecto` (suma un `INGRESO`, resta un `EGRESO`),
  que usan `aplicarMovimiento` y restaurar una factura, y `deshacerEfecto` (al reves), que
  usan editar o eliminar un movimiento manual y anular una factura.
* `registrarIngresoOcasional(cuenta, concepto, monto, fecha, notas)` y
  `registrarGastoOperativo(cuenta, concepto, monto, fecha, notas)`: crean un `INGRESO`
  `INGRESO_OCASIONAL` o un `EGRESO` `GASTO_OPERATIVO`. Delegan en
  `registrarMovimientoManual(cuenta, categoria, ...)`, que es lo que usa el endpoint y
  rechaza cualquier otra categoria (`InvalidMovementOperationException`, **400**). Monto no
  positivo o cuenta ausente, 400. **No exigen saldo suficiente**, igual que Autollantas:
  un gasto puede dejar la cuenta en negativo. Ver [03-DECISIONS.md](03-DECISIONS.md).
* `editarMovimientoManual(id, cuenta, concepto, monto, fecha, notas)`: solo
  `GASTO_OPERATIVO` e `INGRESO_OCASIONAL`. Revierte el efecto anterior **sobre la cuenta
  anterior**, actualiza **la misma fila** y aplica el efecto nuevo sobre la cuenta nueva,
  asi que cambiar de cuenta devuelve el dinero de donde salio. Queda un solo movimiento,
  sin contra-movimiento. La categoria no se cambia.
* `eliminarMovimientoManual(id)`: mismas dos categorias. Revierte el saldo y **borra la
  fila**: no tienen factura ni estado, no queda nada `ANULADA`. Es "Eliminar" en
  Autollantas.
* Las dos anteriores buscan con `findByIdAndTenantId`: si el movimiento no es del tenant,
  `ResourceNotFoundException` (**404**); si es de otra categoria,
  `InvalidMovementOperationException` (**400**, "se gestiona desde su operacion").
* `registrarTransferencia(origen, destino, concepto, monto, fecha)`: **dos movimientos de
  categoria `TRANSFERENCIA`**, un `EGRESO` en el origen y un `INGRESO` en el destino cuyo
  `relatedMovement` es el `EGRESO`. El `concept` de las dos patas es el texto del usuario
  sin espacios sobrantes; si no manda ninguno, el autogenerado ("Transferencia a
  {destino}" y "Transferencia desde {origen}"). `notes` queda vacio. Devuelve el
  record `Transferencia(egreso, ingreso)`. Todo en una transaccion; **el dinero se
  conserva**. Rechaza con `InvalidTransferException` (**400**) cuentas iguales o monto no
  positivo, y con `InsufficientBalanceException` (**409**) un origen sin saldo para el
  monto, sin mover ningun saldo ni dejar movimientos.
* `registrarIngresoDeVenta` y `registrarEgresoDeCompra`: crean los movimientos
  `RECAUDO_VENTA` (con `sale`) y `PAGO_COMPRA` (con `purchase`) del contado, de cada abono
  y de cada pago. El de compra exige saldo suficiente (`exigirSaldoSuficiente`).
* `movimientosDeVenta(venta)` / `movimientosDeCompra(compra)`: **todos** los movimientos
  de una factura, activos o no, en orden de id. Los usan el saldo pendiente, anular,
  restaurar y la cuenta de los renglones de Reporting; nunca para listar en tesoreria.
* Anular y restaurar: `revertirSaldosDeVenta` / `revertirSaldosDeCompra` deshacen el
  efecto de cada movimiento de la factura **sin borrar filas** ni validar nada.
  `reaplicarSaldosDeVenta` lo vuelve a aplicar sin validar (son ingresos);
  `reaplicarSaldosDeCompra` primero simula todos los egresos acumulando sobre el saldo
  corriente de cada cuenta y, si alguno no alcanza, lanza `InsufficientBalanceException`
  (**409**) sin haber escrito nada.
* Consultas, todas sobre movimientos **activos**: `findMovementsByAccountId` (el
  extracto), `buscarMovimientos(accountId, categoria, desde, hasta, saleId, purchaseId)`
  con todos los filtros opcionales, y `sumarPorCategoria(categoria)` /
  `sumarPorCategoria(categoria, desde, hasta)`, que usa Reporting. Las sumas las hace la
  base: `MovementRepository.sumarMonto(Specification)` (fragmento
  `MovementRepositoryCustomImpl`, un `SUM` por Criteria) recibe el mismo filtro que el
  listado (`filtroActivos`), asi que la regla de movimiento activo sigue viviendo solo en
  `MovementSpecifications`. `findMovementById` busca por id en
  el tenant, activo o no (lo usan editar y eliminar, que rechazan las categorias no
  manuales).
* `balanceGlobal()`: suma de los `currentBalance` de todas las cuentas del tenant activo.
  Es el "Total Global" (`lblTotalGlobal`) de `Accounts.fxml`.

**Cuidado: el `@Filter` de Hibernate no cubre `findById`.** Aqui se detecto el hueco;
la regla vigente esta en [02-CONVENTIONS.md](02-CONVENTIONS.md) (Busqueda por id) y la
historia en [03-DECISIONS.md](03-DECISIONS.md).

**Endpoints** (todos requieren JWT)

* `GET` y `POST` `/api/treasury/accounts`
* `PUT /api/treasury/accounts/{id}`: `AccountUpdateRequest`, `name` no blanco hasta 100,
  `initialBalance` obligatorio no negativo y `type` opcional e inmutable. Responde 200
  con `AccountResponse`; 400 por validacion o tipo distinto, 404 inexistente o ajena,
  409 nombre duplicado o saldo resultante negativo al cambiar el inicial. Cualquier rol
  autenticado, igual que crear. Ignora `currentBalance` y propiedades desconocidas.
  `editarCuenta` bloquea y refresca la cuenta con tenant explicito, aplica la diferencia
  del inicial sin movimientos ni KPIs. Solo nombre no revalida saldo; no revisa historico.
  No hay historial del saldo inicial, por lo que `saldoAl` y las aperturas de PDF/XLSX
  usan el valor vigente tambien para fechas anteriores. Detalle y limite con gastos
  negativos en la decision "Edicion de nombre y saldo inicial de cuentas".
* `GET /api/treasury/accounts/{id}/movements`: el extracto (movimientos activos). 404 si
  la cuenta no es del tenant
* `GET /api/treasury/movements?accountId=&category=&desde=&hasta=&saleId=&purchaseId=`:
  el libro de movimientos activos del tenant, todos los filtros opcionales, del mas
  reciente al mas antiguo. Los de una factura `ANULADA` no salen, ni filtrando por su
  `saleId` o `purchaseId`
* `POST /api/treasury/movements`: movimiento manual. Cuerpo `MovementRequest`:
  `accountId`, `category` (solo `GASTO_OPERATIVO` o `INGRESO_OCASIONAL`, otra es 400),
  `concept`, `amount`, `date` (opcional, hoy por defecto) y `notes` (opcional). `concept`
  y `notes` hasta 255 caracteres (**400**, tambien en el PUT); no se valida saldo
* `PUT /api/treasury/movements/{id}`: mismo cuerpo; la `category` tiene que ser la que ya
  tenia (si no, 400). 404 si no es del tenant. `PUT` y no `PATCH` porque el cuerpo trae
  el movimiento completo, como el formulario de Autollantas
* `DELETE /api/treasury/movements/{id}`: **204**; 404 si no es del tenant. `DELETE` y no
  un `/annul` porque el registro desaparece
* `POST /api/treasury/transfers`: mismo cuerpo que antes (`originAccountId`,
  `destinationAccountId`, `concept`, `amount`); la respuesta lleva `outgoingMovementId` e
  `incomingMovementId`. Origen sin saldo suficiente: **409**
* `GET /api/treasury/balance` (balance global)

Desaparecieron `/api/treasury/occasional-incomes` y `/api/treasury/operational-expenses`.

Las respuestas van siempre por DTO (`AccountResponse`, `MovementResponse`,
`TransferResponse`, `BalanceResponse`), nunca la entidad JPA. `MovementResponse` expone
`id`, `accountId`, `accountName`, `type`, `category`, `concept`, `amount`, `date`,
`notes`, `saleId`, `purchaseId` y `relatedMovementId`. Tambien es la respuesta de un
abono (`POST /api/sales/{id}/collections`) y de un pago (`POST
/api/purchases/{id}/payments`).

**Limitacion conocida.** El historial de movimientos de una factura `ANULADA` queda en la
base pero **ningun endpoint lo expone**: todo listado pasa por el predicado de movimiento
activo. Si hace falta verlo, es un endpoint nuevo, no un filtro.

**Semilla de desarrollo.** `DevDataInitializer` crea ademas las 2 cuentas por defecto de
Autollantas para el tenant `demo`: `Caja General` (`EFECTIVO`) y `Bancolombia` (`BANCO`), las
dos con saldo inicial 0.

### Modulo Counterparties

Terceros: la persona o empresa con la que el taller comercia. Unifica el cliente de una
venta y el proveedor de una compra (paso 1 del refactor de normalizacion, ver
[04-DATA-MODEL.md](04-DATA-MODEL.md), TERCEROS). Expone solo alta y listado
(`CounterpartiesController`, ver Endpoints mas abajo).

**Entidad** `Counterparty` (`TERCEROS`):

| Campo | Columna | Notas |
|-|-|-|
| `id` | `id_tercero` | `@AttributeOverride` sobre el `id` de `TenantAwareEntity` |
| `tenantId` | `id_tenant` | Heredado, como en todas las entidades |
| `role` | `tipo_tercero` | Enum `CounterpartyRole`: `CLIENTE`, `PROVEEDOR`, `AMBOS`. Obligatorio |
| `name` | `nombre_razon_social` | 150. Obligatorio |
| `documentType` | `tipo_documento` | Enum `DocumentType`: `CC`, `NIT`, `CE`, `RUT`, `PASAPORTE`. Obligatorio |
| `documentNumber` | `numero_documento` | 25. Obligatorio. Unico por tenant: `uk_tercero_tenant_document` |
| `email` | `correo` | 150, opcional |
| `phone` | `celular` | 25, opcional |

**`CounterpartyService`**

* `crear(tercero)`: valida rol, nombre, tipo y numero de documento
  (`InvalidCounterpartyException`, **400**) y que el documento no exista en el tenant
  (`DuplicateCounterpartyDocumentException`, **409**).
* `obtenerOCrear(rolRequerido, tipoDocumento, numeroDocumento, nombre, correo, celular)`:
  busca por `(tenant, numero_documento)`. Si no existe lo crea con el rol pedido; si existe
  con el rol contrario lo pasa a `AMBOS`; si ya tiene ese rol o `AMBOS` lo devuelve. Correo
  y celular solo completan los vacios; nombre y tipo de documento de uno existente no se
  tocan.
* `exigirRol(tercero, rolRequerido)`: una venta exige `CLIENTE` o `AMBOS`; una compra,
  `PROVEEDOR` o `AMBOS`. Si no, `InvalidCounterpartyException` (**400**). Lo llaman
  `crearFactura` de ventas y de compras cuando reciben un tercero.
* `findById` usa `findByIdAndTenantId`; `findByDocumentNumber`, `findByDocumentNumberAndTenantId`.
* `listar(rol)`: por nombre ascendente, con el `tenantId` explicito en la consulta
  (`findByTenantIdOrderByNameAsc`, `findByTenantIdAndRoleInOrderByNameAsc`). Con un rol
  devuelve ese rol mas `AMBOS`; `AMBOS` como filtro es 400. Si el listado incluye clientes
  (sin filtro o `CLIENTE`) llama antes a `consumidorFinal()`, asi que el Consumidor final
  siempre sale en la lista de clientes, aunque el tenant no haya facturado.

**Endpoints**

* `GET /api/counterparties?role=CLIENTE|PROVEEDOR`: lista de `CounterpartyResponse`. Un
  `role` que no es del enum es 400 (`MethodArgumentTypeMismatchException`).
* `POST /api/counterparties` con `CounterpartyRequest` (`role`, `name`, `documentType` y
  `documentNumber` obligatorios; `email` y `phone` opcionales). Sin `@Valid`: las reglas y
  mensajes del PUT (`CounterpartyService.validarDatos`: recorte, longitudes de la entidad,
  blancos a null, formato del correo) con **400**; el documento 222222222222 es **409**
  "El documento 222222222222 es del Consumidor final". `role` es `CLIENTE` o `PROVEEDOR`;
  `AMBOS` es 400. Documento nuevo: **201**.
  Documento que ya existe con el mismo rol o con `AMBOS`: **409** "Ya existe un tercero con
  el documento N como ROL". Documento que existe con el otro rol: pasa a `AMBOS` por
  `obtenerOCrear` y responde **200** con el tercero existente (nombre y tipo de documento no
  cambian; correo y celular solo completan los vacios).
* `PUT /api/counterparties/{id}` con `CounterpartyUpdateRequest` (`name`, `documentType`,
  `documentNumber`, `email`, `phone`; sin `role`, si llega se ignora): **200** con el
  `CounterpartyResponse`. Validacion en `CounterpartyService.validarDatos`, la misma del
  POST, con mensajes especificos (**400**); documento de otro tercero del tenant o el
  222222222222 **409**; Consumidor final **409**; id inexistente o de otro tenant **404**; id no numerico **400**.
* No hay `DELETE`. Ver [03-DECISIONS.md](03-DECISIONS.md), entradas "Endpoints de
  terceros: alta y listado" y "Edicion de terceros".

### Modulo Sales

Migrado desde el paquete `sales` de Autollantas. Es el primer modulo que **cruza los otros
dos**: una factura descuenta inventario y mueve tesoreria.

**Entidades**

* El cliente es un `Counterparty` (ver Modulo Counterparties); `sales` ya no tiene entidad
  propia de cliente.
* `Sale` (`VENTAS`): `invoiceNumber`, `ManyToOne` **nullable** `customer` a `Counterparty`
  (columna `id_tercero`), `invoiceDate`, `dueDate`,
  `paymentType` (enum `CONTADO` / `CREDITO`), `paymentMethod`, `status`
  (enum `PAGADA` / `PENDIENTE` / `ANULADA`), `subtotal`, `ivaPorPagar`, `total`.
  Restriccion unica `uk_venta_tenant_invoice_number` sobre
  `(id_tenant, numero_factura_venta)`, **divergencia deliberada**: Autollantas valida el
  numero solo en la aplicacion y no tiene indice unico. Ver
  [03-DECISIONS.md](03-DECISIONS.md). **No guarda cuenta** (desde el paso 3 no hay
  `id_cuenta` en `VENTAS`): la cuenta de cada cobro vive en su movimiento.
* `SaleDetail` (`DETALLE_VENTAS`): `ManyToOne` a `Sale` y a `Product`, `quantity`, `price`
  (unitario sin IVA) e `ivaAmount`, **congelado al facturar**. Cambiarle el IVA a un
  producto no puede mover el IVA de una factura ya emitida, porque esa factura ya se
  entrego y se declaro. Es el mismo campo `iva_generado_linea` de Autollantas.
  Lleva ademas `ivaDifference` (`diferencia_iva_linea`), el IVA **neto** de la linea
  (`ivaAmount` menos `producto.taxAmount * quantity`), congelado igual. Lo agrego la
  migracion de Reporting: el Reporte de IVA reparte el descontable por categoria y
  necesita el de cada linea, no solo el agregado de la factura.
* Los abonos **no tienen entidad propia**: un abono es un `Movement` `RECAUDO_VENTA` con
  `sale` apuntando a la factura (ver Modulo Treasury). Ya no existen `Collection` ni la
  tabla `RECAUDOS`.

**Flujo factura -> inventario -> tesoreria**

`SalesService.crearFactura` hace todo esto en **una sola transaccion**:

1. Si `invoiceNumber` falta o viene en blanco, asigna `siguienteNumeroFactura()`: el
   mayor `FV-<digitos>` del tenant, anuladas incluidas, mas uno, con al menos 4 digitos
   (`FV-0001`). Valida que el numero no exista ya en el tenant y lanza
   `DuplicateInvoiceNumberException` (**409**) si existe. Si dos ventas simultaneas pasan
   esa validacion, la segunda choca con `uk_venta_tenant_invoice_number` al insertar y
   tambien se traduce a la misma 409, sin reintento.
2. Por cada linea: resuelve el producto, comprueba que haya stock suficiente
   (`InsufficientStockException`, **409**, si no alcanza) y **descuenta**
   `Product.quantity`.
3. Calcula el IVA de la linea como `price * quantity * InventoryService.getIvaRateForProduct(producto)`
   y lo **congela** en `SaleDetail.ivaAmount`.
4. Totaliza la factura, con las mismas formulas de Autollantas:

   ```
   subtotal    = suma(price * quantity)                 // sin IVA
   ivaGenerado = suma(ivaAmount de cada linea)
   ivaFavor    = suma(producto.taxAmount * quantity)    // IVA ya pagado al comprar
   ivaPorPagar = ivaGenerado - ivaFavor
   total       = subtotal + ivaGenerado
   ```

5. Si es `CONTADO`: estado `PAGADA` y `TreasuryService.registrarIngresoDeVenta` mete un
   `RECAUDO_VENTA` por el total en la cuenta, enlazado a la venta, con concepto
   `Venta <numero>` y **fecha de la factura** (no la de hoy). La cuenta solo se usa para
   ese movimiento; es obligatoria en el contado. Si es `CREDITO`: estado `PENDIENTE` y
   tesoreria no se toca, porque todavia no entro dinero.

**Abonos.** `registrarAbono(saleId, accountId, monto)` registra un `RECAUDO_VENTA`
enlazado a la venta (concepto `Abono factura <numero>`), lo devuelve y, si con eso el
saldo pendiente queda cubierto, pasa la factura a `PAGADA`. Rechaza
(`InvalidSaleOperationException`, **400**) abonar a una factura que no este `PENDIENTE`
(`ANULADA` incluida) y abonos que excedan el saldo pendiente.

El saldo pendiente **no es un campo**: se calcula como `total` menos la suma de los
movimientos `RECAUDO_VENTA` de la factura (el contado y los abonos), **activos o no**: una
factura anulada conserva lo que tenia pagado, y al restaurarla el estado sale de ahi. Una
venta de contado tiene pendiente 0.
Autollantas lo guarda denormalizado en `saldo_pendiente` y lo recalcula a mano en cinco
sitios distintos. La tolerancia de un peso de Autollantas si se porto: un pendiente por
debajo de 1 peso cuenta como saldado, porque los centavos del IVA no pueden dejar una
factura eternamente `PENDIENTE` por 0,4 pesos.

**Anulacion.** `anularFactura(saleId)`:

* Deshace el efecto de **cada** movimiento de la venta (contado y abonos) sobre el saldo
  de su cuenta, sin validar nada (`TreasuryService.revertirSaldosDeVenta`). **No borra
  ningun movimiento.**
* Devuelve el stock de cada linea.
* Deja la factura en `ANULADA`, y con eso sus movimientos quedan **inactivos**: siguen en
  la base, pero no salen en listados ni cuentan en sumas (ver Modulo Treasury).

Una factura ya `ANULADA` no se puede volver a anular: devolveria el stock por segunda vez.

**Restauracion.** `restaurarFactura(saleId)` es el inverso exacto de la anulacion. Motivo
y comparacion con Autollantas en [03-DECISIONS.md](03-DECISIONS.md).

* Solo una `ANULADA`; en cualquier otro estado rechaza con `InvalidSaleOperationException`
  (**400**).
* Vuelve a descontar el stock de cada linea con el mismo `descontarStock` de
  `crearFactura`: si no alcanza, `InsufficientStockException` (**409**) y la transaccion
  deshace todo.
* Vuelve a aplicar el saldo de cada movimiento de la factura, que nunca se borraron
  (`reaplicarSaldosDeVenta`, sin validar: son ingresos). Las filas, con sus ids, son las
  mismas de antes de anular.
* El estado se deriva, no se guarda: `PAGADA` si el saldo pendiente cae dentro de la
  tolerancia de un peso (la misma comparacion de `registrarAbono`), `PENDIENTE` si no.

**Endpoints** (todos requieren JWT)

* `POST /api/sales` (emitir factura). `invoiceNumber` es opcional, ver el paso 1 del flujo.
  Precio de cada linea mayor que cero; en `CREDITO`, `dueDate` obligatorio y no anterior a
  la fecha de la factura (**400**). En `CONTADO` `dueDate` no se valida
* `GET /api/sales/next-invoice-number`: `{"invoiceNumber": "FV-0232"}`, el numero que
  asignaria ahora un POST sin numero. No lo reserva ni escribe nada
* `GET /api/sales`
* `GET /api/sales/{id}`: 404 si no existe para el tenant
* `POST /api/sales/{id}/collections` (abono): cuerpo `CollectionRequest` (`accountId`,
  `amount`, `date` opcional en `yyyy-MM-dd`: si falta es hoy en la zona de negocio, si es
  posterior a hoy o anterior a la fecha de la factura es **400**); responde **201** con el `MovementResponse` del `RECAUDO_VENTA` creado
* `POST /api/sales/{id}/annul`
* `POST /api/sales/{id}/restore`

Las respuestas van por DTO (`SaleResponse`, `SaleDetailResponse`, `MovementResponse`),
nunca la entidad JPA. `SaleResponse` incluye `pendingBalance` calculado (**0 si la
factura esta `ANULADA`**: no se debe; el `saldoPendiente` interno no cambia) y ya no lleva
`accountId` ni `accountName`. Los cobros de una factura se consultan con `GET
/api/treasury/movements?saleId=`.

El cliente se identifica **por id** (`customerId` en `SaleRequest`, opcional). Si no
viene, la venta usa el **Consumidor final** del tenant
(`CounterpartyService.consumidorFinal()`: CLIENTE, NIT `222222222222`, el de la DIAN), que
se crea la primera vez y despues se reutiliza; cada tenant tiene el suyo. Toda venta
tiene tercero. Un id que no resuelve en el tenant es 400 `Cliente no encontrado`; uno que
resuelve a un tercero `PROVEEDOR` es 400 por rol. En la respuesta sale como `customer`,
un `CounterpartyResponse` (`id`, `role`, `name`, `documentType`, `documentNumber`,
`email`, `phone`). En compras `supplierId` es **obligatorio**: si falta, 400 `Una compra
necesita un proveedor (supplierId)`; si no resuelve, 400 `Proveedor no encontrado`. No hay
proveedor por defecto.

**Tasa de IVA:** sale de `InventoryService.getIvaRateForProduct(Product)`, la misma que
usan Inventory y Purchases. Regla en [02-CONVENTIONS.md](02-CONVENTIONS.md) (Calculos que
no se copian).

### Modulo Purchases

Migrado desde el paquete `purchases` de Autollantas. Es el espejo de Sales con el signo
invertido: una compra **suma** stock y **saca** dinero.

**Entidades**

* El proveedor es un `Counterparty` (ver Modulo Counterparties); `purchases` ya no tiene
  entidad propia de proveedor.
* `Purchase` (`COMPRAS`): `invoiceNumber`, `ManyToOne` **nullable** `supplier` a
  `Counterparty` (columna `id_tercero`), `invoiceDate`,
  `dueDate`, `paymentType` (enum `CONTADO` / `CREDITO`), `paymentMethod`, `status` (enum
  `PAGADA` / `PENDIENTE` / `ANULADA`), `subtotal`, `ivaTotal`, `total`. Restriccion unica
  `uk_compra_tenant_supplier_invoice_number` (tenant, proveedor y numero; desde V3), misma
  divergencia deliberada que en `Sale`:
  Autollantas valida el numero solo en la aplicacion. **No guarda cuenta**, igual que
  `Sale`: la de cada pago vive en su movimiento.
* `PurchaseDetail` (`DETALLE_COMPRAS`): `ManyToOne` a `Purchase` y a `Product`,
  `quantity`, `price` (costo de compra unitario sin IVA) e `ivaAmount` de la linea.
* Los pagos **no tienen entidad propia**: un pago es un `Movement` `PAGO_COMPRA` con
  `purchase` apuntando a la compra. Ya no existen `Payment` ni la tabla `PAGOS`.

**Flujo factura -> inventario (suma) -> tesoreria (egreso)**

`PurchasesService.crearFactura`, todo en **una transaccion**:

1. Valida que el `invoiceNumber` no exista para ese proveedor en el tenant, sin distinguir
   mayusculas y con las anuladas incluidas (`DuplicatePurchaseInvoiceNumberException`,
   **409** "Ya existe una factura de compra N del proveedor X"). Si dos peticiones
   simultaneas pasan esa validacion, la restriccion de base frena la segunda al insertar y
   se traduce al mismo 409.
2. Calcula las lineas y los totales **antes de tocar nada**:

   ```
   subtotal = suma(price * quantity)
   ivaTotal = suma(price * quantity * InventoryService.getIvaRateForProduct(producto))
   total    = subtotal + ivaTotal
   ```

3. Si es `CONTADO`, exige que la cuenta tenga saldo suficiente para el total
   (`InsufficientBalanceException`, **409**). **No aplica a `CREDITO`**: una compra a
   credito no saca dinero hoy, asi que no hay nada que validar. Es exactamente lo que hace
   Autollantas, solo que alli la comprobacion vive en el formulario y aqui en el service.
4. **Suma** `Product.quantity` por cada linea, al reves de una venta.
5. **Sincroniza el costo del producto** por cada linea: `purchaseCost = price` de la linea
   y `InventoryService.recalculatePrices(producto)`, el mismo metodo que usa
   `saveProduct`. Comprar a un costo nuevo reescribe el costo del producto y rehace su
   `taxAmount` y su `suggestedPrice`. Ver mas abajo.
6. Si es `CONTADO`: estado `PAGADA` y un `PAGO_COMPRA` por el total, enlazado a la
   compra (`Compra <numero>`). Si es `CREDITO`: estado `PENDIENTE`, sin movimiento.

**Sincronizacion del costo al comprar.** Portado de `savePurchaseWithDetails` de
Autollantas, que por cada linea hace `realProduct.setPurchaseCost(unitPrice)` seguido de
`inventoryService.recalculatePrices(realProduct)`. Detalles del comportamiento, todos
verificados contra el codigo real de Autollantas:

* Aplica a **todas** las compras, `CONTADO` y `CREDITO`. Alli el bloque que recorre los
  detalles corre antes y fuera del `if ("Contado".equals(...))` que mueve la caja, asi que
  el tipo de pago no lo condiciona.
* Es el `price` de la linea (costo unitario sin IVA), no el total de la linea.
* `recalculatePrices` es el **metodo completo**, no un subconjunto: recalcula `taxAmount` y
  `suggestedPrice` y persiste. Es un no-op si el costo queda en cero.
* Si dos lineas de la misma factura traen el **mismo producto** a precios distintos, gana
  el de la **ultima linea procesada**: cada iteracion pisa el costo de la anterior. El
  stock si acumula las dos. Cubierto por `conDosLineasDelMismoProductoGanaElPrecioDeLaUltima`.
* **Anular no lo revierte**, ver mas abajo.

Las lineas y el stock se tocan **despues** de la validacion de saldo a proposito: asi el
saldo se compara contra el total definitivo y el rechazo ocurre sin haber escrito nada,
aunque la transaccion lo desharia igual.

**Pagos.** `registrarPago(purchaseId, accountId, monto)` registra un `PAGO_COMPRA`
enlazado a la compra (`Pago compra <numero>`, que a su vez exige saldo suficiente), lo
devuelve y, si con eso queda cubierto el pendiente, pasa la compra a `PAGADA`. Rechaza
pagar una compra que no este `PENDIENTE` (`ANULADA` incluida) y pagos que excedan el
saldo pendiente. El pendiente es `total` menos la suma de sus movimientos `PAGO_COMPRA`,
activos o no, igual que en Sales.

**Anulacion.** `anularFactura(purchaseId)`:

* **Comprueba primero todo el stock**: si a algun producto le quedan menos unidades de las
  que habria que quitar, rechaza con `InvalidPurchaseOperationException` (**400**)
  nombrando el producto. Pasa cuando parte de lo comprado ya se vendio.
* Deshace el efecto de **todos** los movimientos de la compra, pagos incluidos, sobre el
  saldo de su cuenta, sin validar (`revertirSaldosDeCompra`). **No borra filas.**
* Quita el stock que la compra habia sumado y deja la compra en `ANULADA`: sus
  movimientos quedan inactivos.
* **Costo del producto** (`revertirCostos`): por cada producto de sus lineas, si su
  `purchaseCost` todavia es el precio de su ultima linea en esta compra (tolerancia de
  medio centavo), pasa al precio de la ultima linea de la compra no anulada mas reciente
  (mayor id) de ese producto, sin contar esta, y se rehacen `taxAmount` y `suggestedPrice`
  con `recalculatePrices`. Si el costo es otro (editado a mano o fijado por una compra
  posterior) no se toca; sin otras compras no anuladas se queda como esta. Una sola
  consulta para todos los productos (`PurchaseDetailRepository.findLineasDeLaUltimaCompra`),
  con los productos ya bloqueados. Ver [03-DECISIONS.md](03-DECISIONS.md), "Costo del
  producto al anular y recuperar una compra". Cubierto por `PurchasesCostRevertTest`.

Una compra ya `ANULADA` no se puede volver a anular.

**Restauracion.** `restaurarCompra(purchaseId)`: espejo de `restaurarFactura`.

* Solo una `ANULADA` (`InvalidPurchaseOperationException`, **400**).
* Vuelve a aplicar cada egreso de la compra con la regla de saldo suficiente de crear y
  pagar (`reaplicarSaldosDeCompra`). Lo comprueba **entero antes de escribir nada**,
  acumulando sobre el saldo corriente cuando varios egresos salen de la misma cuenta: si
  uno no alcanza, `InsufficientBalanceException` (**409**) y no cambia ni el estado, ni el
  stock, ni los saldos, ni las filas.
* Vuelve a sumar el stock (sin validacion: sumar siempre se puede).
* Estado derivado igual que en ventas: `PAGADA` si el pendiente cae en la tolerancia de un
  peso, `PENDIENTE` si no.
* **Costo del producto** (`reaplicarCostos`): por cada producto de sus lineas, si ninguna
  otra compra no anulada del producto tiene mayor id, el costo vuelve al precio de su ultima
  linea en esta compra y se rehacen `taxAmount` y `suggestedPrice`; si hay una posterior, no
  se toca. Cubierto por `PurchasesCostRevertTest`.

El egreso del contado al crear la compra tambien lleva la fecha de la factura.

**Endpoints** (todos requieren JWT)

* `POST /api/purchases`: mismas reglas de precio y vencimiento que la venta
* `GET /api/purchases`
* `GET /api/purchases/{id}`: 404 si no existe para el tenant
* `POST /api/purchases/{id}/payments`: cuerpo `PaymentRequest` (`accountId`, `amount`,
  `date` opcional con las mismas reglas que el abono); responde **201** con el `MovementResponse` del `PAGO_COMPRA` creado. `PurchaseResponse`
  ya no lleva `accountId` ni `accountName`, y su `pendingBalance` es 0 si la compra esta
  `ANULADA`, igual que en ventas
* `POST /api/purchases/{id}/annul`
* `POST /api/purchases/{id}/restore`

### Modulo Reporting

Ultimo de los 7 bloques del roadmap original (infraestructura multi-tenant, auth,
Inventory, Treasury, Sales, Purchases, Reporting); ingresos ocasionales y gastos
operativos entraron como parte de Treasury. Migrado desde
`reporting/service/DashboardService` y `ReportService.buildReporteIva` de Autollantas.

**No tiene entidades propias ni escribe nada.** Agrega los datos de todos los modulos
anteriores: `Sale` y `SaleDetail` (Sales), `Purchase` (Purchases), los `Movement` de
categoria `GASTO_OPERATIVO` e `INGRESO_OCASIONAL` (Treasury) y la categoria de `Product`
(Inventory). Las consultas viven en el modulo dueno de cada entidad
(`sumTotalByStatusNot...`, `findBy...DateBetween...`; en Treasury, `TreasuryService.
sumarPorCategoria` y `buscarMovimientos`), no en `reporting/repository`, que sigue vacio:
la consulta pertenece a la entidad que lee.
El aislamiento por tenant lo da el filtro de Hibernate, porque todas son consultas JPQL o
derivadas; ninguna pasa por `findById`.

**Regla de exclusion.** Las facturas de venta y de compra en `ANULADA` no cuentan en
nada: ni KPIs, ni movimientos, ni Reporte de IVA. El filtro es por **estado**
(`status <> ANULADA`), no por historia, asi que una factura que vuelva a `PENDIENTE`
vuelve a contar sin mas. Gastos operativos e ingresos ocasionales no tienen estado: se
eliminan, y al eliminarse desaparecen solos.

**`ReportingService`**

* `getGlobalKpis()`: los KPIs sobre todo el historico del tenant.
* `getPeriodKpis(desde, hasta)`: los mismos, por `invoiceDate` de la factura o `date`
  del movimiento de gasto o de ingreso ocasional dentro de `[desde, hasta]`, ambos inclusive. Dos metodos y dos consultas por
  cifra, no uno con fechas opcionales: un `(:desde IS NULL OR ...)` en JPQL da problemas de
  tipado en PostgreSQL.

  ```
  totalSales        = suma(Sale.total)            sin ANULADA
  totalPurchases    = suma(Purchase.total)        sin ANULADA
  netSales          = suma(Sale.subtotal)         sin ANULADA
  costOfSales       = suma(SaleDetail.unitCost * quantity)  lineas de ventas sin ANULADA
  grossProfit       = netSales - costOfSales
  operatingExpenses = suma(Movement.amount)       categoria GASTO_OPERATIVO, activos
  otherIncome       = suma(Movement.amount)       categoria INGRESO_OCASIONAL, activos
  netProfit         = grossProfit + otherIncome - operatingExpenses
  ```

  `totalSales` y `totalPurchases` son **con IVA**, que es lo que guarda `total`; `netSales`
  es sin IVA. `unitCost` es el costo del producto congelado en la linea al facturar (V4), asi
  que una compra posterior no mueve la utilidad de un periodo ya facturado. `costOfSales` es
  una sola consulta agregada. `netSales`, `costOfSales`, `grossProfit` y `netProfit` salen
  redondeados a 2 decimales. Las compras no entran en la utilidad bruta. Los ingresos
  ocasionales no son ventas: no entran en `totalSales` ni en `grossProfit`, si en `netProfit`.
* `getMovements(desde, hasta)`: ventas, compras, gastos e ingresos ocasionales del rango
  como renglones (`VENTA`, `COMPRA`, `GASTO`, `INGRESO`), del mas reciente al mas antiguo y
  cortado en `MAX_MOVEMENTS` (100), igual que Autollantas. `VENTA` y `COMPRA` salen de las
  facturas, no de sus movimientos: una venta a credito aparece aunque no haya movido caja.
  `GASTO` e `INGRESO` salen de los movimientos `GASTO_OPERATIVO` e `INGRESO_OCASIONAL`, y
  su `sourceId` es el id del movimiento. Las transferencias no aparecen. La cuenta de un
  renglon `VENTA` o `COMPRA` es la del **ultimo movimiento activo** de la factura (el contado, o
  el ultimo abono o pago), o ninguna si no tiene movimientos: es la que antes guardaba la
  factura en `id_cuenta`, que se reescribia con cada abono o pago.
* `buildReporteIva(desde, hasta)`: por cada linea de venta no anulada del rango, agrupa
  por nombre de categoria del producto:

  ```
  ivaGenerado    = suma(SaleDetail.ivaAmount)
  ivaNeto        = suma(SaleDetail.ivaDifference)
  ivaDescontable = ivaGenerado - ivaNeto
  ```

  Ordena por IVA generado descendente y agrega el resumen: facturas con IVA, y los tres
  totales. Las lineas con IVA generado cero se ignoran, como en Autollantas. Los dos campos
  estan congelados al facturar, asi que una compra posterior que cambie el `taxAmount` del
  producto no mueve el reporte de un periodo ya facturado.

Rangos: `desde` y `hasta` son obligatorios en periodo, movimientos e IVA; si falta uno, o
`desde` es posterior a `hasta`, el service lanza `IllegalArgumentException` (**400**).

**`ReportExportService`** y los generadores de archivo (paquete `reporting/export`)

* `ReportExportService` calcula un `ReportModel` (titulos, columnas, filas, totales y la
  fila de resultado) y no genera archivos. `PdfReportGenerator` (OpenPDF) y
  `ExcelReportGenerator` (Apache POI, `XSSFWorkbook`) solo le dan formato al modelo, asi
  que las cifras se prueban sin leer archivos.
* `estadoDeResultados(desde, hasta)`: llama a `getPeriodKpis` del mismo rango; las seis
  filas son `netSales` (Ventas sin IVA), `-costOfSales` (Costo de lo vendido),
  `grossProfit` (Utilidad bruta), `otherIncome` (Otros ingresos), `-operatingExpenses`
  (Gastos operativos) y `netProfit` (Utilidad neta, fila de resultado). No hay formula propia: el archivo cuadra con `/kpis` y con el panel.
* `movimientos(desde, hasta, accountId)`: `TreasuryService.listarParaReporte`, una sola
  consulta Criteria que proyecta fecha, cuenta, tipo, categoria, concepto, monto y los
  numeros de factura de venta y compra (join a `CUENTAS`, `VENTAS` y `COMPRAS`), con el
  mismo filtro de movimiento activo que `GET /api/treasury/movements`, en orden de fecha y
  de id ascendente. No carga entidades, asi que no dispara las relaciones EAGER de
  `Movement`: 3 sentencias SQL por peticion sin cuenta y 8 con cuenta, sin importar cuantos
  movimientos haya (`ReportExportQueriesTest`). Origen: `VENTA <numero>`, `COMPRA <numero>`,
  `GASTO OPERATIVO`, `INGRESO OCASIONAL` o `TRANSFERENCIA`. Con `accountId`, saldo inicial
  y final con `TreasuryService.saldoAl(cuenta, fecha)`: balance inicial mas los movimientos
  activos con fecha hasta ese dia (ingresos suman, egresos restan), la misma invariante de
  `saldo_actual` aplicada a una fecha.
* Limites: rango de a lo sumo 366 dias (contando los dos extremos), ademas de
  `validarRango`; a lo sumo `servibox.reporting.max-export-rows` movimientos (20000 por
  defecto, `REPORTING_MAX_EXPORT_ROWS`). Pasarse es **400**.
* Formato: PDF A4 vertical, margenes de 36 pt, Helvetica estandar; encabezado con el nombre
  del tenant, la linea "NIT <valor>" bajo el nombre si el tenant tiene NIT, el titulo, el periodo y una linea roja de marca; encabezado de tabla que se
  repite en cada pagina; pie "Generado el dd/MM/yyyy HH:mm · tenant · Pag. N de M" con la
  hora del reloj de negocio, estampado en una segunda pasada. Excel: una hoja, encabezado
  en filas combinadas, titulos de la tabla inmovilizados, filtro en movimientos, montos como
  celdas numericas `#,##0`, anchos fijos (sin `autoSizeColumn`, que falla sin fuentes del
  sistema) y sin pie.

**Endpoints** (todos requieren JWT, todos `GET`; JSON salvo `/export`)

* `/api/reporting/kpis`: sin parametros, KPIs globales. Con `desde` y `hasta` (ISO,
  `2026-09-01`), KPIs del periodo. Una sola ruta porque la respuesta es el mismo DTO;
  `desde` / `hasta` vuelven null en el global. Respuesta: `desde`, `hasta`, `totalSales`,
  `totalPurchases`, `netSales`, `costOfSales`, `grossProfit`, `operatingExpenses`,
  `otherIncome`, `netProfit`.
* `/api/reporting/movements?desde=...&hasta=...`
* `/api/reporting/iva?desde=...&hasta=...`
* `/api/reporting/export?type=ESTADO_RESULTADOS|MOVIMIENTOS&format=PDF|XLSX&desde=...&hasta=...[&accountId=...]`:
  el archivo como `ResponseEntity<byte[]>` con `Content-Type` `application/pdf` o
  `application/vnd.openxmlformats-officedocument.spreadsheetml.sheet` y
  `Content-Disposition: attachment` con nombre ASCII
  (`estado-resultados_2026-09-01_2026-09-30.pdf`, `movimientos-cuentas_2026-09-01_2026-09-30.xlsx`).
  Sin `produces`, para que un `Accept: application/json` no de 406. `accountId` solo vale
  con `MOVIMIENTOS` (con `ESTADO_RESULTADOS` es 400); una cuenta inexistente o de otro
  tenant es 400 "Cuenta no encontrada". Un periodo sin movimientos es 200 con un archivo
  que dice "Sin movimientos en el periodo".

DTOs: `KpisResponse`, `MovementSummaryResponse`, `IvaReportResponse` con su lista de
`IvaCategoryResponse`. Archivos: ver [03-DECISIONS.md](03-DECISIONS.md), "Exportacion de
reportes en PDF y Excel".

## Base de datos

Desde fix/41: los catalogos se consultan por nombre (descripcion en productos) e id ASC;
ventas y compras, incluida la papelera, por invoiceDate e id DESC. El libro conserva
date/id DESC y la proyeccion de exportacion date/id ASC. V6 agrega indices para estos
accesos, detalles, recalculos y FK; no modifica filas ni migraciones anteriores.

Los calculos monetarios nuevos usan BigDecimal.valueOf y Moneda para importes definitivos
a dos decimales HALF_UP, conservando Double en las entidades. Base e IVA se totalizan
desde importes finales por linea, no desde impuestos sin redondear. Documentos historicos
conservan importes y movimientos; no hay migracion de redondeo de saldos. PDF/XLSX muestran
centavos cuando existen. El sugerido y el IVA del costo siguen en pesos enteros.

Las lineas de entrada de ventas y compras usan BigDecimal hasta validar su precision
dentro de crearFactura: precio positivo con mas de dos decimales significativos es 400,
sin inserciones ni redondeo silencioso. 0.0100 equivale a 0.01 y se acepta. Los DTO JSON
siguen recibiendo numeros, las entidades siguen Double y no cambian columnas ni historicos.

El perfil prod y las pruebas configuran spring.jpa.open-in-view=false. Las relaciones de
negocio existentes son EAGER; la unica LAZY es tenant, sin getter ni exposicion. Los
reportes resuelven relaciones dentro de servicios transaccionales o usan proyecciones.
No se amplian grafos ni se serializan entidades. Activacion: SPRING_PROFILES_ACTIVE=prod,
con las variables de conexion y JWT ya documentadas.

* Nombres fisicos: los de [04-DATA-MODEL.md](04-DATA-MODEL.md). Tablas en mayusculas
  (`VENTAS`, `DETALLE_VENTAS`, `MOVIMIENTOS`), columnas en `snake_case`
  (`numero_factura_venta`, `fecha_movimiento`), PK `id_<entidad>` y columna de tenant
  `id_tenant` con FK a `TENANTS` en todas las tablas salvo `TENANTS` y
  `CATEGORIA_IMPUESTOS`. Ver Estrategia multi-tenant.
* Nulabilidad y longitudes de VARCHAR: las del diccionario, declaradas en cada `@Column`
  (tambien en las columnas de enum). CHECK: `ck_movimiento_factura`, `ck_movimiento_monto`,
  `ck_detalle_compra_cantidad`, `ck_detalle_venta_cantidad` y los cuatro
  `ck_configuracion_porcentaje_*` (0 a 1); estan en la migracion, y los de las entidades
  tambien en su `@Table(check = ...)` como documentacion.
* Tipos: ids `BIGINT` (`Long`); montos `NUMERIC(14,2)` y tasas como fraccion `NUMERIC(8,6)`,
  los dos `Double` en Java con `@JdbcTypeCode(SqlTypes.NUMERIC)`, `precision` y `scale`;
  fechas de negocio `DATE` (`LocalDate`); auditoria `TIMESTAMP` en UTC (el
  `DateTimeProvider` de `JpaAuditingConfiguration` y `Tenant.onCreate`); enums `VARCHAR`
  con `@JdbcTypeCode(SqlTypes.VARCHAR)`, para que Hibernate no use el `ENUM` nativo de H2.
* Concurrencia: bloqueo pesimista de fila (`SELECT ... FOR UPDATE`) en las operaciones que
  leen y escriben saldos, stock o el estado de una factura, con un orden fijo (documento,
  cuentas, productos; cada grupo por id ascendente) y espera maxima de 5 s
  (`jakarta.persistence.lock.timeout`). Hibernate lo traduce a `for update wait 5` en H2 y a
  `set local lock_timeout = 5000` mas `for no key update` en PostgreSQL (este modo no choca
  con el `FOR KEY SHARE` que toman las claves foraneas al insertar). Regla en Bloqueos de
  [02-CONVENTIONS.md](02-CONVENTIONS.md).

### Gestion del esquema con Flyway

* **Migraciones Java:** V13 vive en `src/main/java/db/migration` porque su backfill reutiliza
  `SearchText`. Flyway la encuentra dentro del JAR empaquetado (probado en perfil prod sobre
  PostgreSQL 16). Las demas son SQL.
* **El esquema lo mandan las migraciones**, en `src/main/resources/db/migration`
  (`V1__init_schema.sql`, escrita a mano desde 04-DATA-MODEL.md, y
  `V2__tenant_nit_phone_user_full_name.sql`, que agrega `nit_tenant` y `telefono_tenant` a
  TENANTS y `nombre_completo` a USUARIOS, las tres NULL, y
  `V3__purchase_invoice_unique_per_supplier.sql`, que cambia la unica de COMPRAS a
  (id_tenant, id_tercero, numero_factura_compra)). Flyway las aplica al
  arrancar, y Hibernate tiene `spring.jpa.hibernate.ddl-auto=validate` en todos los
  perfiles: no crea ni altera tablas, y si una entidad no coincide con el esquema la app no
  arranca. Como agregar un cambio, en [02-CONVENTIONS.md](02-CONVENTIONS.md).
* **Perfiles.**
  * Local, sin perfil o con `dev` (`application.properties`): H2 en memoria en modo
    PostgreSQL (`jdbc:h2:mem:servibox;MODE=PostgreSQL`). Los datos se pierden al apagar,
    como antes; con `dev`, `DevDataInitializer` siembra el tenant `demo`. Arranque:
    `./mvnw spring-boot:run -Dspring-boot.run.profiles=dev`.
  * `prod` (`application-prod.properties`): PostgreSQL. Variables de entorno `DB_URL` (por
    ejemplo `jdbc:postgresql://host:5432/servibox`), `DB_USERNAME` y `DB_PASSWORD`, sin
    valor por defecto: si falta alguna, la app no arranca. Ademas `JWT_SECRET`, ver
    Configuracion de la capa de seguridad.
  * `test` (`src/test/resources/application-test.properties`): H2 en memoria en modo
    PostgreSQL con una base distinta por contexto de Spring (`${random.uuid}` en la URL),
    migrada por Flyway desde vacia, asi que cada contexto arranca con el esquema limpio.
* **Como se prueba.**
  * `schema.SchemaConformanceTest` parsea las 14 tablas de 04-DATA-MODEL.md y las compara
    por `DatabaseMetaData` con el esquema que dejo Flyway (sin `flyway_schema_history`):
    tablas, columnas, tipos por familia, nulabilidad, longitud de VARCHAR, precision y
    escala de NUMERIC, PK, FK con `ON DELETE RESTRICT` y unicas. No acepta desviaciones de
    tipo (la lista esta vacia). Cambiar la migracion sin cambiar el diccionario, o al
    reves, rompe esa prueba.
  * `schema.FlywayMigrationTest`: las migraciones aplican limpias sobre una base vacia, V2
    aplica sobre una base en V1 con datos y deja las columnas nuevas en null, Hibernate
    valida el esquema del contexto, y la relacion a `Tenant` de
    `TenantAwareEntity` no lee TENANTS al cargar una entidad.
  * Reemplazado el pendiente historico de PostgreSQL: la tarea 2 externa verifico
    PostgreSQL 16.15 desde vacio y desde V2 con datos hasta V7, HTTP, persistencia y
    15 casos existentes de concurrencia. No hay Testcontainers ni suite completa sobre
    PostgreSQL. La tarea 3 agrega smoke local del contenedor final prod; ver sesion
    2026-10-29-backend-deployment para evidencia y limites.
