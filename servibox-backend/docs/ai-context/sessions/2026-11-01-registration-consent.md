# Consentimiento de tratamiento de datos en solicitudes de registro (tarea 55), 2026-11-01

Tarea extra de backend (Ley 1581 de 2012). Rama feat/55-registration-consent creada desde main en
c67934c (merge PR 54, incluido), igual a origin/main tras fetch. Numero 55 libre local, remoto y en
PR. Los dos .idea ajenos quedan fuera. Sin frontend, commit, push ni PR. Docker estaba apagado: se
encendio para la prueba y se apago al terminar.

## Paso 0 (investigacion, antes de implementar)

* Flujo: `RegistrationRequestController.submit`: compuerta (404), limite de tasa, `service.submit`.
  `RegistrationRequestBody` es un record sin anotaciones; `validar` lanza
  `InvalidRegistrationException` (400 `{"error","status"}`), sin log.
* Idempotency-Key: se guarda SHA-256 de los campos recibidos sin normalizar (`hash_cuerpo`), con
  retencion de 48 h. Misma clave y hash: mismo `requestId`. Otro hash: 409. Clave vencida: se libera
  y cuenta como nueva. Agregar la version al arreglo del hash basta.
* Datos personales guardados: nombre comercial, NIT, telefono, responsable, correo (y sus formas
  normalizadas). Sin retencion ni purga de solicitudes: `OutboxPurger` solo cubre correos. La evidencia
  de consentimiento hereda eso.
* Logs: el camino normal y los 400 no registran nada; `SafeLog` no escribe mensajes de la base y
  `org.hibernate.orm.jdbc.error` esta en OFF. Un campo nuevo no filtra nada si no se registra.
* Migraciones: maximo V13 (Java). Libre V14; ALTER simple comun a H2 y PG16.
* Panel: el mismo record `PlatformRegistrationRequestResponse` sirve listado y detalle, y `matches`
  sale null en el listado. Agregar campos sin mas los habria metido al listado, que no debe cambiar:
  se omiten con `@JsonIgnoreProperties` en `RegistrationRequestPage.items`.
* Sin conflictos reales con el diseno. Dos puntos que el prompt no fijaba, resueltos abajo: orden de
  la comparacion con la vigente frente a la clave, y como exigir el dato en filas nuevas.

## Implementado

Decisiones en [03-DECISIONS](../03-DECISIONS.md); operacion en [VPS.md](../../deploy/VPS.md).

* `policyVersion` obligatorio. Formato: AAAA-MM-DD exacto y fecha real, sin recortar. Se valida con
  el resto del cuerpo; la igualdad con la vigente se comprueba despues de resolver la clave.
* `servibox.registration-requests.policy-version` = `${REGISTRATION_POLICY_VERSION:2026-11-01}`.
  `ProductionConfiguration` impide arrancar en prod si no cumple el formato (vacia incluida). Dev y
  test no validan al arrancar: un valor invalido solo haria que toda solicitud reciba 400.
* V14 (`V14__registration_consent.sql`): `version_politica` VARCHAR(10) NULL,
  `fecha_aceptacion_politica` TIMESTAMP NULL y CHECK `ck_solicitud_politica` (las dos o ninguna).
* Fecha de aceptacion: el mismo `now` UTC de `fecha_creacion`. Sin IP ni agente de usuario.
* Detalle de plataforma: `policyVersion` y `policyAcceptedAt` aditivos, presentes y nulos en
  historicos. Como aprobar, rechazar y reenviar devuelven el detalle, tambien los traen. Listado igual.
* Activacion y demas flujos sin cambios. `acceptTerms` sigue obligatorio.

### Mensajes de error nuevos (para src/api/errorMessages.ts del frontend)

* 400 `La version de la politica es obligatoria`
* 400 `La version de la politica no es valida`
* 400 `La politica fue actualizada; recarga la pagina para continuar`

Interno de arranque, no llega al cliente: `REGISTRATION_POLICY_VERSION debe ser una fecha valida con
formato AAAA-MM-DD`.

## Pruebas

Conteos separados por motor; no se suman.

### H2

Suite completa con `mvnw clean package`: 732 = 713 base + 19 nuevas, cero fallos, errores u omitidas,
06:28 min, BUILD SUCCESS y JAR generado.

Nuevas: RegistrationConsentTest 13; V14RegistrationConsentMigrationTest 2 (V13 con fila a V14; H2 de
archivo reabierta); RegistrationRequestsDisabledTest +1 (compuerta cerrada 404 sin version, invalida o
vieja); ProductionConfigurationTest +3 (default, otra version, seis valores invalidos).

Pruebas existentes ajustadas, sin retirar aserciones:

* `RegistrationRequestTest.cuerpo` (ayudante, tambien usado por RegistrationActivationTest,
  RegistrationActivationConcurrencyTest, RegistrationRequestConcurrencyTest, PublicRateLimitTest,
  PublicRateLimitGateOffTest, ActivationWithoutTransportTest, ScheduledOutboxTest y
  RegistrationRequestsDisabledTest): agrega `policyVersion`, ahora obligatorio.
* `AccentInsensitiveSearchTest.elEnvioPublicoLlenaLasColumnasNormalizadas`: su cuerpo en linea agrega
  `policyVersion`, por la misma razon.
* `application-test.properties`: fija la version, para no depender del default de produccion.

Correcciones de arnes en RegistrationConsentTest durante la primera corrida, no del producto: el
reemplazo del campo en el cuerpo y `print = MockMvcPrint.NONE`, porque MockMvc imprimia los cuerpos
de las peticiones y la prueba de logs los contaba. Esa prueba usa otra version vigente porque
2026-11-01 coincide con la fecha de hoy en cada linea de log.

### PostgreSQL 16

PostgreSQL 16.15 (Debian 16.15-1.pgdg13+2), contenedor desechable `postgres:16`, UTF8, puerto solo en
127.0.0.1, clave efimera fuera del repo, una base nueva por corrida.

* Migracion, base mig1: V14RegistrationConsentMigrationTest con URL externa, 2/2 (uno sobre PG16; el
  de archivo es H2 aunque se pase la URL). Flyway 14 de 14.
* JUnit, base run1: 69/69 (RegistrationConsentTest 13, RegistrationRequestTest 16,
  RegistrationRequestsDisabledTest 4, RegistrationActivationTest 16, RegistrationRequestFilterTest 10,
  AccentInsensitiveSearchTest 10).
* HTTP real, base http1, JAR empaquetado en perfil prod, puerto 18095, compuerta abierta solo para la
  prueba y `PUBLIC_RATE_LIMIT_REQUESTS_LIMIT=1000`. Esperado frente a obtenido: 39 de 40 a la primera;
  el fallo fue del arnes (ver abajo), revisado en el log.
  * Base en V13 con una solicitud historica y un administrador sembrados por SQL. Arranque normal:
    `14|registration consent|SQL|t`; historica intacta con nulos; columnas y CHECK como se esperaba.
  * `REGISTRATION_POLICY_VERSION` en `2026-02-30` o vacia: no arranca, con el mensaje, y sin migrar.
  * Creacion con version correcta 202, fila con la version, fecha igual a creacion y en UTC. Sin
    version 400, vacia 400, `2026/11/01` 400, version vieja 400, cada una con su mensaje. Misma clave
    y cuerpo: mismo `requestId`. Misma clave con otra version: 409. Solo 2 filas al final.
  * Login real de plataforma 200. Detalle con version y fecha iguales a la base; historica con ambas
    claves en null; listado con exactamente las claves del detalle menos las dos nuevas; sin token 401.
    Aprobar historica 200 y rechazar nueva 200, conservando la evidencia.
  * Rotacion a 2027-01-15 con reinicio: reintento con la clave vieja, mismo `requestId`; solicitud
    nueva con 2026-11-01 da 400; con 2027-01-15 da 202 y se guarda esa version.
  * Compuerta cerrada: 404 sin version.
  * Logs de los tres arranques: 0 apariciones de correo, nombre, NIT, telefono, clave de plataforma,
    claves de reintento y versiones enviadas; 0 lineas ERROR.
  * El fallo: la comprobacion "con target 13 no arranca" buscaba `version_politica` en el log, y
    Hibernate nombra primero `fecha_aceptacion_politica`. El arranque si fue rechazado: `missing column
    [fecha_aceptacion_politica]`, `Application run failed`, 0 lineas `Started`.
* Contenedor eliminado y servidor detenido.

`git diff --check` sin hallazgos. No hay lint backend configurado. Sin navegador.

## Limitaciones y riesgos

* Hasta que salga la rama de frontend, todo POST desde el frontend actual recibe 400 (no envia
  `policyVersion`), tambien en dev. Con la compuerta cerrada, el default en prod, no tiene efecto.
* El backend no publica la version vigente: el frontend la lleva fija y debe coincidir con
  REGISTRATION_POLICY_VERSION. Entre publicar uno y cambiar la otra hay errores; ver VPS.md.
* La base no distingue una fila nueva sin consentimiento de una historica: un INSERT manual sin las
  columnas pasa el CHECK. Lo garantiza el servicio. Como produccion nace vacia, una migracion futura
  podria poner NOT NULL si no quedan historicas.
* Un reintento identico tras rotar la politica devuelve 202 con la version vieja dentro de la ventana
  de 48 h: no crea nada; la solicitud conserva la version que acepto.
* El hash ahora incluye la version: una clave anterior a este cambio, aun vigente, responde 409 aunque
  se reenvie igual. Solo afecta datos de desarrollo.
* Solo se compara la version: el backend no guarda el texto de cada politica. Conservar cada version
  publicada es un proceso aparte, necesario para que la evidencia sirva.
* Sin retencion ni purga de solicitudes, ni de los datos personales ni de la evidencia.
* Volver atras: tras V14 un JAR anterior deberia arrancar (las columnas admiten NULL), pero crearia
  solicitudes sin consentimiento. No se probo; con la compuerta abierta conviene restaurar el respaldo.
* La activacion por HTTP real no se probo en PG16 (requiere correo); la cubre JUnit, en H2 y PG16.
