# Filtros del listado de solicitudes de registro (tarea 48), 2026-10-31

Tarea extra de backend, fuera del bloque plataforma. Rama feat/48-registration-request-filters
creada desde main en e38b594 (merge PR 47, tarea 5A), igual a origin/main tras fetch; PR 47
mergeado. Los dos .idea ajenos quedan fuera. Sin frontend, migracion, commit, push ni PR.
Antes de ejecutar: SPRING_DATASOURCE_URL, SPRING_PROFILES_ACTIVE, DEV_DEMO_DATA y
REGISTRATION_REQUESTS_ENABLED no estaban definidas en el proceso. El backend de revision en
8080 y su H2 de archivo no se tocaron.

## Hallazgos del paso 0 (antes de implementar)

* Entidad `RegistrationRequest` (SOLICITUDES_REGISTRO, V9): nombre de serviteca
  `businessName` = `nombre_comercial` VARCHAR(150); responsable `contactName` =
  `nombre_responsable` VARCHAR(150); recepcion `createdAt` = `fecha_creacion` TIMESTAMP sin
  zona, NOT NULL, no actualizable.
* `fecha_creacion` se guarda en UTC: `LocalDateTime.now(ZoneOffset.UTC)` truncado a
  microsegundos. La API la devuelve UTC sin zona. Un dia de Bogota (UTC-5, sin horario de
  verano) va de 05:00 UTC de ese dia a 05:00 UTC del siguiente.
* Listado actual: `RegistrationRequestService.list(status, page, size)`; status opcional por
  `findByStatus`, sin status `findAll`. Orden `createdAt desc, id desc` (desempate por UUID).
  page >= 0 y size 1 a 100, si no 400 `Paginacion invalida`. Respuesta
  `RegistrationRequestPage {items,page,size,totalElements,totalPages}`; items con
  `matches` null (solo el detalle lo llena).
* Seguridad: GET listado con PLATFORM_IDENTITY en la cadena de plataforma; JWT tenant y sin
  token 401.
* Errores 400 por `InvalidRegistrationException` con `{"error","status"}`. Un `LocalDate` en
  `@RequestParam` daria el 400 generico de conversion, no el mensaje pedido.
* Indice existente `idx_solicitud_estado_fecha (estado, fecha_creacion)`. 5A ya usa
  `like ... escape '\'` en JPQL para la busqueda de tenants.

## Implementado

Decision en [03-DECISIONS](../03-DECISIONS.md).

* `RegistrationRequestRepository` extiende `JpaSpecificationExecutor`; se retira
  `findByStatus` (sin otros usos).
* `RegistrationRequestService.list` arma una `Specification` con un predicado por filtro
  presente (status, tenantName, contactName, receivedFrom, receivedTo); los ausentes no llegan
  al SQL. Texto recortado, minusculas `Locale.ROOT`, `\`, `%` y `_` escapados, `lower(col)
  like ? escape '\'`. Fechas con `LocalDate.parse` (ISO estricto) convertidas a UTC.
* `PlatformRegistrationRequestController` recibe los cuatro parametros como texto opcional.
* Sin migracion ni indices nuevos.

## Contrato para el frontend

GET `/api/platform/registration-requests`, `Authorization: Bearer <JWT plataforma>`.

| Parametro | Regla |
|-----------|-------|
| status | Igual que antes (PENDING, APPROVED, REJECTED, CANCELLED, ACTIVATED) |
| tenantName | Contiene en nombre de serviteca, sin mayusculas, recortado, hasta 100 |
| contactName | Contiene en nombre del responsable, mismas reglas |
| receivedFrom | AAAA-MM-DD, dia de Bogota incluido desde 00:00 |
| receivedTo | AAAA-MM-DD, dia de Bogota incluido hasta 23:59:59.999999 |
| page, size | Igual que antes (page >= 0, size 1 a 100, default 0 y 25) |

* Todos opcionales y combinados con AND. Vacio o solo espacios equivale a no enviarlo.
* `%`, `_` y `\` se buscan literalmente. Mas de 100 caracteres tras recortar: 400.
* Fechas abiertas: solo Desde o solo Hasta. Desde igual a Hasta es un solo dia.
* El total y las paginas cuentan solo lo filtrado. Orden y respuesta sin cambios.
* Los acentos cuentan: `maria` no encuentra `María`.

Ejemplo: `GET /api/platform/registration-requests?tenantName=100%25&receivedFrom=2026-10-15&receivedTo=2026-10-15`

```json
{"items":[{"id":"00000000-0000-0000-0000-00000000000a","status":"PENDING",
"businessName":"Serviteca 100% Llantas","nit":"900555666-1","phone":"3001234567",
"slug":"http-a","contactName":"Ana Rojas","contactEmail":"a@example.invalid",
"createdAt":"2026-10-15T05:00:00","reservationExpiresAt":"2026-12-30T00:00:00",
"reservationExpired":false,"reviewedAt":null,"reviewerId":null,"rejectionReason":null,
"version":0,"matches":null}],"page":0,"size":25,"totalElements":1,"totalPages":1}
```

`createdAt` 2026-10-15T05:00:00 UTC es el 15/10 a las 00:00 en Bogota.

Mensajes nuevos para src/api/errorMessages.ts (400, formato `{"error","status"}`):

* `El filtro de texto no puede superar 100 caracteres`
* `La fecha debe tener formato AAAA-MM-DD` (tambien fechas imposibles como 2026-02-30)
* `La fecha inicial no puede ser posterior a la final`

Reutilizados: `Paginacion invalida` y el 400 de enum para `status`. Sin token o con JWT
tenant: 401.

## Pruebas

Conteos separados por motor; no se suman.

* JUnit H2: base 595 (nota 5A). Suite completa con `mvnw clean package`: 605 = 595 + 10
  nuevas, cero fallos, errores u omitidas, 03:59 min, BUILD SUCCESS y JAR generado. No se
  ajusto ninguna prueba existente.
* Nuevas, RegistrationRequestFilterTest (10): orden y desempate sin filtros y con
  parametros vacios; texto parcial con mayusculas, espacios y acentos; `%`, `_` y `\`
  literales; limites de dia en Bogota (00:00, 23:59:59.999999, 00:00 del dia siguiente y
  23:59:59 del dia anterior); fechas abiertas; filtros combinados con status; paginacion
  despues de filtrar con total filtrado; 100 caracteres valido y 101 con 400 (tambien con
  espacios alrededor); fechas invalidas (15/10/2026, 2026-13-01, 2026-02-30, 2026-1-5, con
  hora) y rango invertido; sin token y JWT tenant 401.
* PG16 (PostgreSQL 16.15, contenedor desechable `postgres:16`, puerto solo 127.0.0.1,
  clave efimera fuera del repo, base NUEVA por corrida):
  * run1: 55/55 (RegistrationRequestFilterTest 10, RegistrationRequestTest 16,
    RegistrationRequestConcurrencyTest 5, RegistrationActivationTest 13, PlatformTenantTest
    11). Flyway 11 de 11 migraciones exitosas, 20 tablas en public (19 mas el historial).
  * run2: RegistrationRequestFilterTest 10/10 con SQL visible. Sin filtros, el SQL no tiene
    WHERE. Con texto: `lower(rr1_0.nombre_comercial) like ? escape '\'` y patrones enlazados
    `%100\%%` y `%luis\_%`. Con fechas: `fecha_creacion>=? and fecha_creacion<?`, con
    2026-10-15T05:00 y 2026-10-16T05:00 para el 15/10. Orden
    `fecha_creacion desc, id_solicitud desc`.
  * Sonda SQL directa en run2: con ESCAPE `100%` coincide con 1 fila, sin ESCAPE con 2; el
    rango del 15/10 incluye 05:00:00 y 04:59:59.999999 del 16 y excluye 04:59:59 del 15;
    `lower` de PG16 coincide con la minuscula de Java para `MARÍA ÑANDÚ`; PG confirma que
    00:00 en America/Bogota equivale a 05:00 UTC.
* Controles HTTP reales (JAR empaquetado en 18091, perfil dev, DEV_DEMO_DATA=false, compuerta
  abierta, base PG16 nueva http5; administrador por `--platform-bootstrap-stdin`; 4 solicitudes
  insertadas por SQL con fecha exacta):
  * Sin filtros 200, total 4, orden c,b,a,d.
  * `tenantName=  LLANTAS ` 200, b,a. `tenantName=100%` 200, solo a. `contactName=luis_`
    200, solo b. `contactName=maría` 200, solo d.
  * Fechas: 15/10 a 15/10 da b,a; desde 16/10 da c; hasta 14/10 da d.
  * `status=PENDING&tenantName=llantas&receivedTo=2026-10-15` da a.
  * `tenantName=l&size=2&page=1`: total 3, 2 paginas, item a.
  * Errores 400: 101 caracteres, `15/10/2026`, `2026-02-30` y rango invertido, cada uno con
    su mensaje exacto.
  * Sin token 401 y JWT tenant (login demo) 401.
  * Corridas http1 a http4 descartadas por fallos del script, no del producto: PowerShell
    5.1 antepuso BOM al email del bootstrap (http1, http2), luego doble codificacion de
    `María` en el INSERT por tuberia (http3), y un corte de salida dejo colgado el script
    (http4, servidor detenido a mano).
* `git diff --check` sin hallazgos. No hay lint backend configurado. Sin navegador.
* Contenedor eliminado y servidor de prueba detenido. Queda un volumen anonimo del contenedor
  (creado 2026-10-31T16:15:12Z): la herramienta no autorizo borrarlo.

## Limitaciones y riesgos

* Busqueda sensible a acentos (`maria` no encuentra `María`); quitar acentos exigiria
  `unaccent` o una columna normalizada.
* `lower` depende del locale de la base; en PG16 con locale por defecto del contenedor coincide
  con Java. No se verifico con otros locales de produccion.
* `contiene` con comodin inicial no usa indice B-tree: recorre la tabla. Con el volumen
  esperado de solicitudes no se justifica un indice (sin indices especulativos).
* La validacion de texto se hace tras recortar: 100 letras con espacios alrededor son validas.
* El listado ya devolvia `"matches":null` en cada item (comportamiento previo, no cambiado).
* Orden de validacion: paginacion, texto, formato de cada fecha y por ultimo el rango; se
  responde el primer error.
