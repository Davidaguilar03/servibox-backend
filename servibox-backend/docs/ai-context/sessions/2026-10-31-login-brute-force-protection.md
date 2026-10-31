# Proteccion de logins contra fuerza bruta (tarea 50, extra), 2026-10-31

Solo backend. Rama feat/50-login-brute-force-protection creada desde main en 1b19ec3 (merge
PR 49), igual a origin/main tras fetch; 50 libre local, remoto y en PR. Al primer intento
el PR 49 seguia abierto y se detuvo la tarea hasta que David lo mergeo. Los dos .idea ajenos
quedan fuera. Sin frontend, migracion, commit, push ni PR. SPRING_DATASOURCE_URL,
SPRING_PROFILES_ACTIVE, DEV_DEMO_DATA y REGISTRATION_REQUESTS_ENABLED no estaban definidas
en el proceso. El backend de revision (8080, PID 222268) no se toco.

## Hallazgos del paso 0 (antes de implementar)

* Login tenant: POST `/api/auth/login`, cuerpo `{tenantSlug, username, password}` (los tres
  `@NotBlank`, sin tope de longitud), 200 `{token, ...}`. `AuthController.login` resuelve el
  401: tenant inexistente o inactivo (suspendido), usuario inexistente o inactivo y contrasena
  incorrecta lanzan `InvalidCredentialsException`; `GlobalExceptionHandler` responde 401
  `{"error":"Credenciales invalidas","status":401}`, identico en los cuatro casos. Busqueda
  exacta por slug y usuario (sin normalizar).
* Login plataforma: POST `/api/platform/auth/login`, cuerpo `{email, password}` (`@NotBlank`,
  150 y 72), 200 `{token, admin:{id,email}}`. `PlatformAuthController.login` normaliza el
  correo (strip y minusculas); inexistente, inactivo, clave de mas de 72 bytes o incorrecta
  dan el mismo 401.
* Cadena: ambos `permitAll` (tenant en la cadena orden 2, plataforma en la orden 1); ningun
  filtro los toca, el 401 sale del advice. Un cuerpo invalido es 400 antes del controlador.
* Distinguible por tiempo (previo, no se cambia): con tenant o usuario inexistente o inactivo
  no se paga BCrypt, asi que la respuesta es mas rapida que con contrasena incorrecta. Mismo
  cuerpo y codigo; el limite reduce el ritmo de esa enumeracion pero no la elimina.
* `FixedWindowRateLimiter` (tarea 49): package-private, `acquire` cuenta toda peticion
  (incluso las rechazadas, acotado en limite+1), purga perezosa, tope que falla cerrado,
  `Clock` inyectado. `PublicRateLimiter.clientIp`: socket, o ultima entrada de
  `servibox.rate-limit.client-ip-header` (PUBLIC_RATE_LIMIT_CLIENT_IP_HEADER) si parece IP.
  `acquire` no sirve tal cual: contaria exitos y no permite devolver ni limpiar.
* `ProductionConfiguration`: un `BeanFactoryPostProcessor` del perfil prod que valida los
  secretos y ACTIVATION_BASE_URL con IllegalStateException; no registraba avisos.
* `RegistrationRequestTest.listadoPaginadoConFiltroYDetalleSinClaveNiHash`: el listado se
  ordena por `createdAt desc, id desc`; taller-l2 y taller-l3 creadas en el mismo tic del
  reloj empatan y el desempate por UUID aleatorio decide al azar cual sale primero.

## Implementado

Decision en [03-DECISIONS](../03-DECISIONS.md); variables y comprobaciones en
[DEPLOYMENT.md](../../DEPLOYMENT.md).

* `FixedWindowRateLimiter`: publica, con `reserve` (toma una unidad sin pasar del limite; en
  el limite devuelve Retry-After sin contar), `release` y `reset`. `acquire` sin cambios.
* `PublicRateLimiter.clientIp` publico, sin cambios de logica.
* `auth.LoginThrottle` (@Component): tres limitadores (par IP + cuenta, cuenta, IP total),
  cuentas normalizadas (minusculas, sin espacios, 150 caracteres por parte), reserva antes de
  evaluar, exito limpia el par y devuelve cuenta e IP, otro error devuelve las tres.
  Reutiliza `RateLimitExceededException` (429 con Retry-After en el advice).
* `AuthController` y `PlatformAuthController`: el login va envuelto en `LoginThrottle.attempt`;
  la logica de autenticacion no cambia.
* `ProductionConfiguration`: WARN al arrancar si algun limite esta encendido y la cabecera
  esta vacia (no falla).
* `application.properties`: `servibox.login-throttle.*`. `application-test.properties`: apagado.
* `RegistrationRequestTest`: fija `fecha_creacion` de taller-l3 un segundo despues de
  taller-l2 por SQL (solo la prueba; orden de produccion intacto).

Defaults: ventana 15m, 5 por IP + cuenta, 20 por cuenta, 30 por IP, 20000 claves por tipo.

## Contrato para el frontend

Mensaje nuevo para src/api/errorMessages.ts, 429 `{"error","status"}` con cabecera
`Retry-After` (segundos), en ambos logins:

* `Demasiados intentos de inicio de sesion, espera unos minutos e intenta de nuevo`

Mensaje interno de log (no es respuesta HTTP): el WARN de `ProductionConfiguration`, que
empieza por `PUBLIC_RATE_LIMIT_CLIENT_IP_HEADER esta vacia`. Los 401 no cambian.

## Pruebas

Conteos separados; no se suman entre motores.

* JUnit H2 base: 627 (nota de la tarea 49).
* JUnit H2 suite completa con `mvnw clean package`: 653 = 627 + 26 nuevas, cero fallos,
  errores u omitidas, 04:20 min, BUILD SUCCESS y JAR generado.
* Nuevas: FixedWindowReservationTest (5: reserva hasta el limite y el rechazo no cuenta,
  vencimiento, release y reset, tope lleno falla cerrado y se recupera, 64 hilos admiten
  exactamente 5); LoginThrottleUnitTest (7: normalizacion y tope de longitud, bloqueo con
  Retry-After 900 y 30 y vencimiento, exito limpia el par pero no la IP y no cuenta, cuenta
  desde cualquier IP, otro error no cuenta y apagado no limita, tope lleno, 40 hilos evaluan
  exactamente 3 contrasenas); LoginThrottleTest (11, MockMvc: ambos logins 429 con
  Retry-After 900 y mensaje exacto aunque la contrasena sea correcta, exito antes del limite
  limpia el par, IP distinta e IPv6 no comparten par, cuenta desde 5 IP, cuentas inexistentes
  iguales, usuario inactivo y tenant suspendido siguen en 401 y cuentan, IP total con cuentas
  distintas, X-Forwarded-For y X-Real-IP falsos sin confianza, salud, me, ruta comercial y
  solicitudes intactos desde una IP bloqueada, 400 no cuenta, 30 peticiones simultaneas dan
  exactamente 3 x 401 y 27 x 429); LoginThrottleTrustedProxyTest (1: con X-Real-IP
  configurada cada cliente tiene su cuota y lo antepuesto no da cuota nueva);
  ProductionConfigurationTest (+2: WARN sin cabecera sin fallar el arranque, sin WARN con
  cabecera).
* Ajustada: `RegistrationRequestTest.listadoPaginadoConFiltroYDetalleSinClaveNiHash` (fechas
  deterministas, motivo arriba). Ninguna asercion retirada.
* Estabilidad: la clase RegistrationRequestTest sola, 10 corridas seguidas, 10 de 10 sin
  fallos (16 pruebas cada una), mas la suite completa.
* Control HTTP real (JAR empaquetado, puerto 18093, perfil dev, H2 de archivo en una carpeta
  temporal nueva, variables solo en los procesos hijos, DEV_DEMO_DATA=false): bootstrap de
  plataforma por `--platform-bootstrap-stdin` en el mismo archivo, salida 0; registro tenant
  201; login tenant 200; 5 fallos 401 y la correcta 429 `Retry-After: 898` con el mensaje
  exacto; con X-Real-IP y X-Forwarded-For falsos sigue 429 (897); login plataforma 200; 5
  fallos 401 y la correcta (correo con mayusculas y espacio) 429 `Retry-After: 898`; misma
  cuenta desde ::1 (otra IP) 200; cuenta inexistente 5 x 401 y la sexta 429; salud 200 y me
  sin token 401 desde la misma IP; sin WARN en dev. Proceso detenido, puerto libre, carpeta,
  base, script y logs borrados.
* El WARN de prod se probo con ApplicationContextRunner, no con un arranque prod real.
* PostgreSQL no se ejecuto: no cambia SQL ni migracion.
* `git diff --check` sin hallazgos. No hay lint backend configurado. Sin navegador.

## Limitaciones y riesgos

* Sin PUBLIC_RATE_LIMIT_CLIENT_IP_HEADER en Railway todos comparten la IP del proxy: 30
  fallos de cualquiera bloquean el login de todos 15 minutos. Ahora hay WARN al arrancar,
  pero no impide arrancar. Comprobaciones obligatorias en DEPLOYMENT.md.
* Bloqueo dirigido: 20 fallos desde cualquier lugar bloquean una cuenta (tambien a su dueno)
  durante la ventana. Es el costo de frenar el ataque distribuido; no hay bloqueo permanente.
* Tope lleno (20000 claves por tipo, por ejemplo con cientos de IP rociando cuentas): las
  claves nuevas reciben 429 hasta que venzan ventanas, incluidos logins legitimos nuevos.
* Por instancia y en memoria: varias replicas multiplican el limite; reiniciar lo borra.
* Ventana fija desde el primer fallo; un `release` que llega despues de vencer la ventana
  puede descontar una unidad de la ventana siguiente (inexactitud de a lo sumo una unidad).
* Diferencia de tiempo previa entre cuentas inexistentes e incorrectas (ver hallazgos).
* NAT compartido (oficina, CGNAT): comparten la cuota de 30 fallos por IP.
