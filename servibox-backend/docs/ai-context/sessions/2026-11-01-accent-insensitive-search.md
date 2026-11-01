# Busqueda insensible a tildes y mayusculas en plataforma (tarea 54), 2026-11-01

Tarea extra de backend. Rama feat/54-accent-insensitive-search creada desde main en 98424da
(merge PR 53, incluido), igual a origin/main tras fetch. Numero 54 libre segun git branch -a.
Los dos .idea ajenos quedan fuera. Sin frontend, commit, push ni PR. Docker estaba apagado: se
encendio para la prueba y se apago al terminar. SPRING_DATASOURCE_URL, SPRING_PROFILES_ACTIVE,
DEV_DEMO_DATA y REGISTRATION_REQUESTS_ENABLED no estaban definidas en el proceso.

## Paso 0 (investigacion, antes de implementar)

Lectura: AGENTS.md, 00-RULES, notas 4B, 5A, filtros de solicitudes y despliegue completas; de
01, 02, 03 y 04 solo las secciones de plataforma, Flyway y tablas implicadas, no el total.

Inventario de busquedas por texto del backend (todos los `@RequestParam` de texto y todo
`like`, `lower` e `IgnoreCase` de src/main/java):

| Busqueda | Implementacion antes de esta tarea | Columnas | Indice |
|----------|-----------------------------------|----------|--------|
| GET /api/platform/tenants, `q` | JPQL `lower(t.name) like :p escape '\'` o `lower(t.slug) like :p escape '\'`; contiene | nombre_tenant, slug_tenant | Solo UNIQUE de slug; no sirve con comodin inicial |
| GET /api/platform/registration-requests, `tenantName` | Specification, `lower(col) like ? escape '\'`; contiene | nombre_comercial | Ninguno |
| Mismo listado, `contactName` | Igual | nombre_responsable | Ninguno |

* Contradiccion con el prompt: "usuarios de una serviteca" NO tiene busqueda por texto.
  `GET /api/platform/tenants/{id}/users` solo pagina (`findByTenantId`). No se agrego: nuevos
  filtros estan fuera de alcance.
* No son busquedas de usuario, quedan igual: `existsBySlugIgnoreCase`, `findByCodeIgnoreCase`
  de productos, numero de factura `IgnoreCase` y el prefijo de numeracion de ventas (igualdad o
  prefijo interno; cambiarlos alteraria unicidad, no busqueda).
* Slug: `validarSlug` y el registro solo admiten minusculas, numeros y guion, asi que no necesita
  columna propia: basta comparar `lower(slug)` con el patron normalizado.
* Frontend (solo lectura, `src/platform/platformApi.ts`): `q`, `tenantName` y `contactName` se
  envian con `trim()` y sin mas cambios; tildes, mayusculas y espacios interiores llegan tal cual.
  No necesita cambios.
* Flyway: maximo aplicado V12. La tarea usa V13; la proxima libre es V14 (consentimiento). No hay
  `out-of-order`: Flyway ignora o rechaza versiones menores que la ya aplicada y dos archivos con
  el mismo numero impiden arrancar. El choque se evita por proceso: una tarea a la vez, rama
  desde main con el PR anterior mergeado. Si la rama de consentimiento sale antes de mergear esta,
  debe usar V14 igual.

### Opciones evaluadas con evidencia

(a) Extension unaccent, probada en PostgreSQL 16.15:

* `CREATE EXTENSION unaccent` funciona con el POSTGRES_USER del compose (superusuario): version
  1.1, `Niño Camión` da `Nino Camion` y tambien trata la forma descompuesta. Es confiable
  (trusted): un rol sin superusuario la crea si tiene CREATE sobre la base y si no, responde
  `permission denied to create extension "unaccent"`.
* `unaccent` es STABLE: `create index on t (lower(unaccent(x)))` falla con `functions in index
  expression must be marked IMMUTABLE`. Exigiria una funcion envoltura.
* H2 2.4.240 en modo PostgreSQL: `Function "UNACCENT" not found`. Las pruebas (H2) y el perfil dev
  necesitarian un alias Java solo para H2, con riesgo de que los dos motores den resultados
  distintos, y cada consulta llevaria la funcion en su SQL.

(b) Columnas normalizadas mantenidas por la aplicacion. Elegida:

* Una sola regla en Java para lo guardado, lo buscado y el backfill: mismo resultado en H2 y PG16.
* Sin extension ni permiso adicional, y las consultas se simplifican a `col like ?`.
* Costo: tres columnas mas y disciplina de escritura. Se cubre con el setter de la entidad como
  unico camino y NOT NULL: un INSERT manual sin la columna falla en vez de quedar invisible.
* Decision: coincide con la preferencia inicial, ahora con evidencia. Detalle en
  [03-DECISIONS](../03-DECISIONS.md).

## Implementado

* `shared.SearchText.normalize`: NFD, quita `\p{M}`, minusculas `Locale.ROOT`, colapsa espacios
  Unicode (incluye tabulador y no separable) y recorta.
* `db.migration.V13__search_normalized_columns` (migracion Java en `src/main/java/db/migration`,
  tipo JDBC en el historial): agrega la columna, rellena con SearchText, y pone NOT NULL, en
  `tenants.nombre_busqueda_tenant`, `solicitudes_registro.nombre_comercial_busqueda` y
  `solicitudes_registro.nombre_responsable_busqueda`, las tres VARCHAR(450).
* Java y no SQL porque ningun SQL comun a H2 y PG hace NFD. `TRANSLATE` con lista fija se habria
  separado de la aplicacion en cualquier caracter fuera de la lista.
* 450: un nombre de 150 caracteres hangul se expande a 450 con NFD (probado en ambos motores).
* `Tenant.setName`, `RegistrationRequest.setBusinessName` y `setContactName` mantienen la columna;
  esas columnas no tienen setter publico. Cubre alta inmediata, activacion (`provisionar`), envio
  publico, `PUT /api/tenant` y DevDataInitializer. Suspender y reactivar no tocan el nombre.
* `TenantRepository.search` y `RegistrationRequestService.contiene` comparan contra la columna
  normalizada. Validacion de 100 caracteres sobre el texto recibido recortado, antes de normalizar;
  escape de `\`, `%` y `_` sin cambios.
* Sin mensajes de error nuevos, sin cambios de contrato: las respuestas no exponen las columnas.
  Nada que agregar a `errorMessages.ts`.

### Normalizacion

* `Niño`, `NIÑO`, `nino` y `Niño` (forma descompuesta) dan `nino`: la ñ se busca como n, en
  ambos sentidos. `ano` encuentra tambien `año`; es lo pedido.
* Letras sin descomposicion NFD se conservan: `ß`, `ø`, `æ`, `ł`, `đ`. `Straße` no se encuentra con
  `strasse`, ni `Øre` con `ore`. Sin compatibilidad NFKD: `ﬁ` no equivale a `fi`.
* Puntuacion, guiones y comodines se conservan: `mi-taller` no se encuentra con `mi taller`.
* Texto solo de marcas diacriticas (`́`) queda vacio y equivale a no filtrar.

### Rendimiento

La busqueda es "contiene", asi que no se agrego indice. EXPLAIN en PG16 con 10010 servitecas
(transaccion deshecha): `Seq Scan`, 5.5 ms en total. Con un B-tree `varchar_pattern_ops` de
prueba: `LIKE '%camion%'` sigue en `Seq Scan`; `LIKE 'camion%'` si usa `Index Scan`. Para el MVP
(decenas o cientos de servitecas) no hace falta mas. Si un dia hiciera falta, la via seria un
indice GIN con `pg_trgm`, que requiere extension; no se implementa.

## Pruebas

Conteos separados por motor; no se suman.

### H2

* Suite completa con `mvnw clean package`: 713 = 695 base + 18 nuevas, cero fallos, errores u
  omitidas, 05:24 min, BUILD SUCCESS y JAR generado.
* Nuevas: SearchTextTest 5; AccentInsensitiveSearchTest 10 (servitecas y solicitudes con tildes,
  mayusculas, mezcla, espacios dobles, ñ, forma descompuesta, comodines literales, codigo con
  tilde, estado combinado, suspender y reactivar, edicion de perfil, envio publico, contrato y
  limites); V13SearchColumnsMigrationTest 2 (V12 con filas a V13; H2 de archivo reabierta);
  RegistrationActivationTest +1 (el tenant activado por el flujo real se encuentra sin tildes).
* Pruebas existentes ajustadas: OutboxKeyRotationWorkerTest, OutboxLogPrivacyTest, OutboxPurgerTest
  y RegistrationRequestTest (`laBaseImpideDosReservas...`): sus INSERT directos a
  solicitudes_registro agregan las dos columnas NOT NULL. Sin retirar aserciones. El diccionario
  04-DATA-MODEL se actualizo y SchemaConformanceTest pasa sin cambios.

### PostgreSQL 16

PostgreSQL 16.15 (Debian 16.15-1.pgdg13+2), contenedor desechable `postgres:16`, UTF8, puerto solo
en 127.0.0.1, clave efimera fuera del repo, una base nueva por corrida.

* Migracion, base mig1: V13SearchColumnsMigrationTest con URL externa, 2/2 (uno sobre PG16 y el de
  archivo es H2 aunque se pase la URL). Flyway 13 de 13; hangul de longitud 450.
* JUnit, base run2: 86/86 (AccentInsensitiveSearchTest 10, RegistrationActivationTest 16,
  RegistrationRequestFilterTest 10, RegistrationRequestTest 16, PlatformTenantTest 11,
  TenantProfileTest 15, OutboxKeyRotationWorkerTest 1, OutboxLogPrivacyTest 2, OutboxPurgerTest 5).
* HTTP real, base http2, JAR empaquetado en perfil prod (puerto 18094):
  * Flyway target 12 aplico 12 migraciones y Hibernate rechazo el arranque (`missing column
    [nombre_comercial_busqueda]`): la validacion detecta una base sin V13. Se insertaron 10
    servitecas, 4 solicitudes, 1 administrador y 1 usuario por SQL.
  * Arranque normal: `Migrating schema "public" to version "13 - search normalized columns`,
    historial `13|JDBC|...|t`, la migracion Java se encuentra dentro del JAR. Backfill exacto:
    `Camión   Rápido` da `camion rapido`, `Llantas 100% Ñandú` da `llantas 100% nandu`, dato en forma
    descompuesta da `baterias nina`. Las tres columnas NOT NULL, longitud 450.
  * Esperado frente a obtenido: 36/36 sin diferencias (18 servitecas, 11 solicitudes, 7 tras
    escrituras). Incluye `nino`, `NIÑO`, `CAMIÓN RÁPIDO`, espacios dobles, `SERVITECA` y `serviteca`,
    `ñandú`, `Baterías` contra dato descompuesto, `100%`, `aceite_`, `%`, `\`, `cámion-rapido` contra
    codigo, `maria gomez`, `ñáñez`, `luis_`.
  * Escrituras: POST de solicitud 202, encontrada por `penon automotriz` y `OSCAR muñoz`. PUT
    /api/tenant 200, encontrada por `penon`, ya no por el nombre viejo, y su respuesta sin columna
    de busqueda. Suspender 200 y filtro de estado correcto.
  * Contrato: 101 caracteres dan 400 con los dos mensajes de siempre; sin token 401; nombres
    originales en la respuesta. Logs: 0 lineas ERROR, 0 apariciones de los secretos.
* Corridas descartadas por fallos del arnes, no del producto: run1 (86 errores: el perfil test fija
  H2Dialect; se sobrescribio con PostgreSQLDialect en run2), http1 (Git Bash reescribio la ruta
  /tmp y el seed no se aplico).
* Contenedor y su volumen eliminados, servidor detenido, Docker apagado, scratchpad vacio.

`git diff --check` sin hallazgos. No hay lint backend configurado. Sin navegador.

## Limitaciones y riesgos

* La activacion por HTTP real no se probo en PG16 (requiere el token del correo y no hay SMTP);
  queda cubierta por la prueba JUnit del flujo real, en H2 y en PG16.
* Volver atras: tras V13 un JAR anterior no puede insertar servitecas ni solicitudes (NOT NULL sin
  default). Volver a una version previa exige restaurar el respaldo, como dice VPS.md.
* INSERT manual por SQL a tenants o solicitudes_registro debe llenar la columna de busqueda en
  forma normalizada; si escribe otro valor, la fila se busca por ese valor. Un UPDATE directo del
  nombre deja la columna vieja.
* Cambiar la regla de SearchText exige una migracion nueva que recalcule las columnas; V13 ya
  aplicada no se vuelve a ejecutar.
* V13 lee y actualiza fila por fila en una transaccion. Con el volumen del MVP es inmediato; el
  ALTER bloquea la tabla durante esa transaccion. Sin carga medida en produccion.
* El limite de 100 cuenta unidades UTF-16 del texto recibido: un texto en forma descompuesta cuenta
  cada marca (igual que antes).
* Sigma griega final y otras reglas contextuales de minusculas no se tratan aparte.
* `usuarios de una serviteca` sigue sin busqueda por texto. Si se quiere, es una tarea nueva.
* Quedan 2 volumenes de Docker que no son de esta tarea; no se tocaron.
