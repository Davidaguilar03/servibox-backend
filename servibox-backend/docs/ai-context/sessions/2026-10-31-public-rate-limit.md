# Limite de tasa por IP en rutas publicas de solicitudes (tarea 49, bloque plataforma 7 de 8), 2026-10-31

Solo backend. Rama feat/49-public-rate-limit creada desde main en bfe982e (merge PR 48),
igual a origin/main tras fetch; numero 49 libre local, remoto y en PR. Los dos .idea ajenos
quedan fuera. Sin frontend, migracion, commit, push ni PR. Antes de ejecutar:
SPRING_DATASOURCE_URL, SPRING_PROFILES_ACTIVE, DEV_DEMO_DATA y REGISTRATION_REQUESTS_ENABLED
no estaban definidas en el proceso. El backend de revision en 8080 y su H2 de archivo no se
tocaron (sigue escuchando con el mismo PID).

## Hallazgos del paso 0 (antes de implementar)

* Rutas publicas: POST `/api/registration-requests` (Idempotency-Key, 202) y POST
  `/api/registration-requests/activate`, ambas `permitAll` en la cadena tenant (orden 2);
  `/api/platform/**` va por su propia cadena (orden 1). El filtro JWT tenant corre pero sin
  token no autentica. La compuerta se evalua en el controlador: `if (!enabled)` lanza
  `ResourceNotFoundException` (404 `Recurso no encontrado`) antes del servicio. Un JSON mal
  formado responde 400 antes del controlador, aun con la compuerta apagada.
* Reenvio (4B): `RegistrationActivationService.resend` lanza `ResendLimitException` con
  `Espera un momento antes de reenviar la activacion` o `Se alcanzo el limite diario de
  reenvios de esta solicitud`; `GlobalExceptionHandler` la traduce a 429 `{"error","status"}`
  sin cabecera Retry-After. Limite por solicitud en base, no por IP.
* IP del cliente: ningun codigo lee la IP (`getRemoteAddr`, X-Forwarded-For, X-Real-IP sin
  usos). No hay `server.forward-headers-strategy` en ningun perfil; Railway no es una
  plataforma que Spring detecte, asi que la estrategia efectiva es NONE y la IP es la del
  socket, que en Railway es la del proxy.
* Railway (docs oficiales, specs and limits): agrega `X-Real-IP` "for identifying client's
  remote IP", X-Forwarded-Proto, X-Forwarded-Host, X-Railway-Edge y otros. No documenta
  X-Forwarded-For, ni cuantos saltos hay, ni si reemplaza un X-Real-IP enviado por el
  cliente.
* Librerias: no hay Caffeine, Bucket4j ni otra cache o limitador en el pom.
* Fuerza bruta en logins (solo reporte): NINGUNA proteccion. POST `/api/auth/login` (tenant)
  y POST `/api/platform/auth/login` no tienen limite por IP, por usuario ni bloqueo tras
  fallos; solo responden 401 generico y pagan BCrypt por intento. Queda pendiente
  (tarea 8 u otra).
* Plazos frente a lo confirmado por David el 2026-10-31: sin diferencias. Propiedades y
  codigo: reserva PENDING `7d`, APPROVED `30d`, idempotencia `48h`, token `24h`, reenvio
  `60s`, `5` reenvios en 24 h moviles (la generacion de aprobar no cuenta). Solo cambiaba
  la etiqueta "propuesta, no confirmada" en docs y comentarios, ya corregida.

## Implementado

Decision en [03-DECISIONS](../03-DECISIONS.md); variables en [DEPLOYMENT.md](../../DEPLOYMENT.md).

* `registration.FixedWindowRateLimiter`: ventana fija por clave en `ConcurrentHashMap`,
  `compute` atomico por clave, contador acotado en limite+1, purga perezosa una vez por
  ventana y al llegar al tope, tope de entradas que falla cerrado, `Clock` inyectado.
* `registration.PublicRateLimiter` (@Component): un limitador por ruta, interruptor y
  lectura de IP (socket, o ultima entrada de la cabecera configurada si parece IP).
* `registration.RateLimitExceededException` y su manejo en `GlobalExceptionHandler`: 429,
  `Retry-After` y el formato de siempre.
* `RegistrationRequestController`: compuerta, luego limite, luego servicio.
* `application.properties`: `servibox.rate-limit.*`; comentarios de plazos confirmados.
  `application-test.properties`: limite apagado (MockMvc manda todo desde 127.0.0.1).

Defaults: crear 5 por 1 h, activar 10 por 15 min, 10000 IP por ruta, sin cabecera de proxy.

## Contrato para el frontend

Mensajes nuevos para src/api/errorMessages.ts, 429 `{"error","status"}` con cabecera
`Retry-After` (segundos):

* `Demasiadas solicitudes desde esta conexion, intenta de nuevo mas tarde` (crear solicitud)
* `Demasiados intentos de activacion, espera unos minutos e intenta de nuevo` (activar)
* Interno de arranque: `Configuracion del limite de tasa invalida`

Los 429 de reenvio de 4B no cambian. Ningun otro endpoint responde 429 por IP.

## Pruebas

Conteos separados; no se suman entre motores.

* JUnit H2 base: 605 (nota de la tarea 48).
* JUnit H2 suite completa con `mvnw clean package`: 627 = 605 + 22 nuevas, cero fallos,
  errores u omitidas, 04:09 min, BUILD SUCCESS y JAR generado.
* Corrida previa de la misma suite: 627 con 1 fallo en una prueba existente,
  `RegistrationRequestTest.listadoPaginadoConFiltroYDetalleSinClaveNiHash` (esperaba
  taller-l3 primero y vino taller-l2). Dos solicitudes creadas en el mismo tic del reloj
  empatan en `fecha_creacion` y el desempate es por UUID aleatorio: intermitencia previa,
  ajena a esta tarea (esa clase corre con el limite apagado). La clase sola paso 3 de 3 y la
  suite repetida paso completa. No se modifico la prueba.
* Nuevas: FixedWindowRateLimiterTest (10: limite y Retry-After decreciente con redondeo,
  vencimiento justo en el borde, cuota por clave, limpieza de vencidas, tope lleno falla
  cerrado y se recupera, configuracion invalida, 64 hilos sobre una clave admiten exactamente
  10, 32 hilos con claves distintas no pasan el tope mas el numero de hilos, IP del socket
  ignorando cabeceras sin confianza, ultima entrada con confianza, cabecera vacia o basura);
  PublicRateLimitTest (9, MockMvc con reloj controlado: 429 con Retry-After 3600 y 900 y
  mensajes exactos, el 429 no crea filas, rutas independientes, IP distinta e IPv6, cabeceras
  falsas sin confianza no cambian la cuota, reintento con la misma clave gasta una unidad y
  devuelve el mismo requestId, recuperacion al pasar la ventana con Retry-After 300 a mitad,
  login tenant, login plataforma, salud, rutas de plataforma, /api/tenant y registro inmediato
  intactos desde una IP agotada, 40 peticiones simultaneas admiten exactamente 4);
  PublicRateLimitTrustedProxyTest (2: con X-Forwarded-For configurada cada cliente tiene su
  cuota y lo antepuesto por el cliente no da cuota nueva); PublicRateLimitGateOffTest (1:
  compuerta apagada, 5 y 5 peticiones 404 y la IP conserva su unica unidad en cada ruta).
* Ajustada: ninguna prueba. Solo `application-test.properties` apaga el limite.
* Primera corrida focalizada: 1 fallo de aritmetica en la prueba nueva (avance de 13 min
  0,5 s en vez de 13 min 30,5 s); el producto respondio bien y se corrigio la prueba.
* Control HTTP real (JAR empaquetado, puerto 18092, perfil dev, H2 en memoria
  `jdbc:h2:mem:rl18092`, REGISTRATION_REQUESTS_ENABLED=true y DEV_DEMO_DATA=false solo en ese
  proceso, Flyway 11 migraciones): crear 5 x 202 y la sexta 429 `Retry-After: 3600` con el
  mensaje exacto; activar 10 x 400 y la undecima 429 `Retry-After: 900` con el mensaje exacto;
  con X-Forwarded-For y X-Real-IP falsos sigue 429; desde la misma IP login tenant 401, login
  plataforma 401 y salud 200. Proceso detenido; script y log (fuera del repo) borrados al
  terminar.
* PostgreSQL no se ejecuto: no cambia SQL ni migracion.
* `git diff --check` sin hallazgos. No hay lint backend configurado. Sin navegador.

## Limitaciones y riesgos

* AVISO para abrir la compuerta: en Railway, sin PUBLIC_RATE_LIMIT_CLIENT_IP_HEADER=X-Real-IP
  todos los visitantes comparten la IP del proxy y una sola cuota (5 solicitudes por hora
  en total). Con la cabecera configurada, su resistencia a falsificacion depende de que
  Railway reemplace el valor del cliente: no documentado, comprobar en el despliegue.
* Por instancia y en memoria: varias replicas multiplican el limite; reiniciar lo borra.
* Ventana fija: hasta el doble del limite en el borde de dos ventanas.
* Tope lleno: IP nuevas reciben 429 hasta que venzan ventanas (falla cerrado). El tope puede
  superarse en a lo sumo tantas entradas como hilos concurrentes.
* Una red con NAT (oficina, operador movil con CGNAT) comparte cuota.
* Logins sin proteccion de fuerza bruta (ver hallazgos).
