# Errores, logs y cabeceras de produccion (tarea 52, extra), 2026-11-01

Solo backend. Rama feat/52-backend-error-and-log-hardening creada desde main en cc67648 (merge
PR 51), igual a origin/main tras fetch; 52 libre local, remoto y en PR. Los dos .idea ajenos
quedan fuera. Sin frontend, migraciones, commit, push ni PR. SPRING_DATASOURCE_URL,
SPRING_PROFILES_ACTIVE, DEV_DEMO_DATA y REGISTRATION_REQUESTS_ENABLED no estaban definidas en
el proceso. En 8080 no escuchaba ningun proceso (el backend de revision no estaba corriendo);
no se arranco ni se toco su base. El mapa de mensajes del frontend se leyo sin modificarlo.

## Hallazgos del paso 0 (antes de implementar)

* Contrasena generada: la crea `org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration`
  (modulo spring-boot-security 4.1.1), que registra un `InMemoryUserDetailsManager` con el
  usuario `user` y una clave aleatoria escrita en el log ("Using generated security password")
  cuando no hay otro AuthenticationManager, AuthenticationProvider ni UserDetailsService. La app
  no usa nada de eso: sus dos cadenas son stateless con filtros JWT propios, sin httpBasic ni
  formLogin, y ningun codigo propio pide un AuthenticationManager. Excluir la autoconfiguracion
  la apaga sin tocar las cadenas. El bootstrap de plataforma arranca la misma clase
  `ServiboxApplication`, asi que la exclusion le aplica tambien.
* GlobalExceptionHandler: los manejadores de excepciones propias (Duplicate*, Invalid*,
  Insufficient*, ResourceNotFound, Activation, ResendLimit, RateLimitExceeded,
  RegistrationDisabled, AdminRequired, Credenciales) devuelven su mensaje; cuerpo ilegible y
  parametro mal tipado devuelven un texto propio (con el valor del cliente); bloqueos un texto
  fijo; el 500 un texto fijo. Los dos problematicos: `IllegalStateException` (400 con su
  mensaje y WARN con su mensaje) e `IllegalArgumentException` (400 con su mensaje).
* IllegalArgumentException en com.servibox: 57 lanzamientos. 52 son validaciones de dominio
  en rutas HTTP (inventario 16, tesoreria 10, compras 5, ventas 5, reportes 8, terceros 3,
  plataforma 5) y sus textos estan en el mapa del frontend (por ejemplo `Categoria no
  encontrada: N`, `El color debe tener el formato #RRGGBB`, `Paginacion invalida`). Los otros
  5 son del bootstrap por consola (`PlatformBootstrap`, `PlatformBootstrapService`) y de la
  configuracion JWT de plataforma: nunca responden HTTP. Ademas, cualquier IAE de libreria
  llegaba con su mensaje: por ejemplo un saldo inicial `1e400` (Jackson lo lee como infinito)
  hace que `BigDecimal.valueOf` lance NumberFormatException y el cliente recibia
  "Infinite or NaN"; un `UUID.fromString` o un enum invalido revelaria clases y paquetes.
* IllegalStateException en com.servibox: 28 lanzamientos; ninguno es un mensaje de dominio
  para el cliente: configuracion de arranque (ProductionConfiguration, OutboxCipher,
  OutboxWorker, OutboxPurger, SmtpMailTransport, limitadores, activacion), bootstrap,
  siembra dev, `No hay un tenant activo` (TenantService, ReportExportService) y el
  `@PrePersist` sin tenant (TenantAwareEntity), y envolturas de NoSuchAlgorithmException.
  Ninguno esta en el mapa del frontend.
* Logs sospechosos: `GlobalExceptionHandler` lineas 59 (mensaje de ISE), 293 (mensaje de la
  excepcion de bloqueo, con SQL) y 303 (`log.error` con la excepcion completa: mensajes de la
  base con valores); `OutboxWorker` 122 y 127 (hoy solo la clase simple: seguro pero poco util
  para operar); `DevDataInitializer` 402 (mensaje de la excepcion, solo dev); y el log
  automatico de Hibernate 7, categoria `org.hibernate.orm.jdbc.error` (la de
  SqlExceptionHelper), que escribe el error SQL completo con el valor duplicado (lo
  confirmo la prueba nueva en H2: `... VALUES ( /* 3 */ 'correo' )`; en PostgreSQL el detalle
  `Key (col)=(valor)`). `SmtpMailTransport`, filtros JWT, controladores de login y
  LoginThrottle no registran nada. El bootstrap imprime solo textos fijos.
* Login: tenant con usuario activo y clave de mas de 72 bytes llamaba `matches`, que en
  Spring Security 7.1.1 devuelve false sin calcular BCrypt (401 mas rapido). Plataforma ya
  pagaba el relleno en ese caso desde la tarea 51. `LoginRequest` tenant no tiene tope de
  longitud.
* Cabeceras en prod (arranque real de la tarea 51): X-Content-Type-Options, X-Frame-Options
  DENY, Cache-Control no-store, Pragma, Expires y X-XSS-Protection 0; sin HSTS, porque Spring
  Security solo la escribe si la peticion es segura y detras de Railway la app ve HTTP (no hay
  forward-headers-strategy, a proposito desde la tarea 49).

## Implementado

Decisiones en [03-DECISIONS](../03-DECISIONS.md); operacion en [DEPLOYMENT.md](../../DEPLOYMENT.md).

1. `ServiboxApplication`: `@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)`.
2. `shared.DomainValidationException extends IllegalArgumentException`; las 52 validaciones
   de dominio la lanzan con el mismo texto (reemplazo mecanico en 14 archivos, mas el import).
   GlobalExceptionHandler: su manejador devuelve el mensaje; otra IAE y toda ISE devuelven 400
   `Peticion invalida` y registran el detalle con SafeLog.
3. `shared.SafeLog`: `describe` (cadena de clases, SQLState, primer marco propio; sin
   mensajes) y `mask` (correos j***@dominio, secuencias de 32 o mas caracteres tipo token como
   [oculto], una linea, 300 caracteres). Usado en los 400 genericos, bloqueos, 500, siembra dev
   y el worker del outbox (id, intento, permanente, clase y respuesta SMTP enmascarada de la
   causa mas profunda). `logging.level.org.hibernate.orm.jdbc.error=OFF`.
4. `AuthController`: usuario activo con clave de mas de 72 bytes paga el relleno y da el 401.
5. `SecurityConfig`: con perfil prod, HSTS `max-age=15552000` sin includeSubDomains ni
   preload en ambas cadenas para cualquier peticion.

Texto generico elegido: `Peticion invalida` (400), que ya existia (cuerpo con @Valid
invalido e InventoryController) y que el frontend ya trata con su texto de 400; el 500 sigue
`Error interno del servidor`. Sin mensajes nuevos para el frontend ni cambios de contrato.

## Pruebas

Conteos por motor, sin sumar.

* JUnit H2 base: 677 (tarea 51). Primera corrida de la suite existente con todos los cambios
  de produccion y sin pruebas nuevas: 677 de 677, ninguna ajustada. Es la regresion principal
  de que los mensajes de dominio no cambiaron (las pruebas existentes comparan textos exactos).
* JUnit H2 suite completa con `mvnw clean package`: 689 = 677 + 12 nuevas, cero fallos,
  errores u omitidas, 04:54 min, BUILD SUCCESS y JAR generado.
* Nuevas: StartupLogAndHstsTest (2: arranca la app completa con servidor real en puerto
  aleatorio sobre H2 con perfil dev y con perfil prod; log de arranque sin "Using generated
  security password" ni "security password", sin bean UserDetailsService; HSTS
  `max-age=15552000` exacto en prod en salud, 401 de la cadena tenant, 401 de la cadena
  plataforma y ruta inexistente; ausente en dev); ErrorExposureAndLogPrivacyTest (5, MockMvc
  perfil test: ISE e IAE ajenas con un correo en el mensaje y la IAE real de BigDecimal con
  saldo 1e400 dan 400 `Peticion invalida`, el log tiene la clase y el lugar pero no el correo
  ni "Infinite or NaN"; un duplicado real en la base da 500 generico y el log no trae el valor;
  sin HSTS en el perfil test; mensajes de dominio exactos (color, categoria inexistente, rango
  de fechas); logins fallidos sin clave, correo ni token en el log); DomainMessagesSourceTest
  (2: los 30 inicios de mensaje del mapa del frontend que nacen de validaciones de argumento
  siguen lanzandose como DomainValidationException; no queda `new IllegalArgumentException(`
  fuera del bootstrap y la configuracion JWT); OutboxLogPrivacyTest (2: un transporte que falla
  con el destinatario y el cuerpo en el mensaje deja en el log id, intento, permanente y
  `550 5.1.1 <j***@cliente-secreto.co> User unknown`, sin el correo ni el token; unidad de
  `mask`); LoginTimingEqualizerTest (+1: clave de exactamente 72 bytes 200 con una sola
  comparacion real; 73 bytes con los 72 primeros correctos y 80 bytes en 40 caracteres dan 401
  con exactamente un relleno y una llamada a `matches`).
* Ajustadas: ninguna prueba existente.
* Primera corrida focalizada: 4 fallos, ninguno del producto salvo uno real: la prueba de
  duplicado encontro que Hibernate 7 registra el error SQL por la categoria
  `org.hibernate.orm.jdbc.error` y no por el nombre de clase SqlExceptionHelper que se habia
  apagado primero; se corrigio la propiedad. Los otros tres eran de las pruebas: el nombre del
  log "Started" con SpringApplicationBuilder, un espacio doble esperado en `mask`, y el texto de
  prueba de 400 caracteres iguales que ahora cae en la regla de secuencias tipo token.
* PG16 (PostgreSQL 16.15 en contenedor desechable propio, solo 127.0.0.1, clave en memoria, base
  NUEVA por clase, Flyway en 12 en cada una): ErrorExposureAndLogPrivacyTest 5/5 (el duplicado
  real de PostgreSQL, cuyo detalle trae el valor, no aparece en el log), OutboxLogPrivacyTest
  2/2, LoginTimingEqualizerTest 6/6: 13/13. Las clases del perfil prod
  (ProductionRegistration*, StartupLogAndHstsTest) fijan H2 en sus propias propiedades, que
  mandan sobre las de sistema: no corrieron sobre PG16; el perfil prod sobre PG16 lo cubre el
  control HTTP. Contenedor y volumen eliminados.
* Control HTTP real: JAR empaquetado, puerto 18095, perfil prod, PostgreSQL 16.15 desechable
  nuevo, claves JWT, plataforma, outbox, base, administrador y usuario generadas al vuelo y
  nunca impresas; compuerta encendida, X-Real-IP configurada, SMTP apuntando a un servidor
  falso local sin STARTTLS. Bootstrap por stdin salida 0, Flyway 12. Arranque: Started, perfil
  prod, 0 lineas "generated security password" (arranque y bootstrap), sin WARN de IP. HSTS
  `max-age=15552000` en salud y en los 401 de ambas cadenas. Login tenant 200 (tenant por SQL).
  Saldo 1e400: 400 `{"error":"Peticion invalida","status":400}`; dominio exacto: color,
  rango de fechas y `Categoria no encontrada: 987654`. Logins fallidos tenant (usuario con forma
  de correo), tenant con 73 bytes extra y plataforma: 401 identicos. Solicitud 202 con un
  correo de contacto, aprobar 200, el worker intento el envio: correo 1 PENDING
  TRANSPORTE_TEMPORAL intento 1 y log `Outbox: correo 1 no aceptado por el transporte, intento
  1, permanente false (MessagingException: STARTTLS is required but host does not support
  STARTTLS)`. Barrido del log completo: 0 apariciones del correo de contacto ni de su parte
  local, de las claves (fallida, usuario, administrador), de los JWT tenant y plataforma, de
  `token=`, de "Infinite or NaN" y del correo del administrador. Solo 2 WARN: la IAE de
  BigDecimal (clase y lugar) y el del worker. Procesos detenidos, puertos 18095 y del SMTP
  libres, contenedor eliminado. No se uso 8080.
* `git diff --check` sin hallazgos. No hay lint backend configurado. Sin navegador.

## Hallazgos de seguridad restantes (solo reporte)

| Severidad | Hallazgo | Recomendacion |
|-|-|-|
| Media | HSTS del frontend: el backend solo cubre sus respuestas; la pagina la sirve otro servicio | Configurar HSTS en ese servicio o CDN al publicar (DEPLOYMENT.md) |
| Baja | El JAR sigue con `h2` y `spring-boot-h2console`; la consola no se habilita y `/h2-console` responde 401 | Mover a un perfil o scope de dev y pruebas al tocar el pom |
| Baja | Rutas inexistentes, `/error`, `/actuator` y Swagger responden 401 `No autenticado` en vez de 404 (denyAll por defecto). No revela datos | Ninguna necesaria |
| Baja | Los 400 de cuerpo ilegible y de parametro mal tipado repiten el valor enviado por el cliente (por ejemplo `Valor invalido 'X' en el parametro ...`). Es su propio dato, no se registra en el log | Mantener; contrato mapeado por el frontend |
| Baja | El 500 ya no deja el stacktrace completo en el log (solo clases, SQLState y lugar). Se gana privacidad y se pierde detalle para diagnosticar | Aceptado; si hace falta, reproducir en un entorno sin datos reales |
| Info | Sin CSP (API JSON) y sin CORS abierto; fuera de alcance | Mantener |

## Limitaciones y riesgos

* El SMTP falso del control HTTP no ofrece STARTTLS, asi que el error real ocurre antes de
  RCPT y no lleva el correo; el caso de una respuesta 550 con el destinatario se probo con un
  transporte de prueba en JUnit (H2 y PG16), no contra un servidor SMTP real.
* La regla de `mask` (32 o mas caracteres alfanumericos seguidos) puede ocultar texto util
  largo de un proveedor SMTP, y no reconoce secretos mas cortos.
* La clasificacion depende de que las nuevas validaciones usen DomainValidationException;
  DomainMessagesSourceTest frena una IllegalArgumentException nueva, pero no una
  IllegalStateException con un mensaje pensado para el cliente (saldria generico).
* Apagar `org.hibernate.orm.jdbc.error` oculta tambien sus avisos de SQL utiles para
  diagnostico; el SQLState queda en el log del manejador.
* HSTS de 180 dias: un navegador que la reciba recordara HTTPS para el dominio del backend
  durante ese plazo; revertir exige servir max-age=0 por HTTPS.
