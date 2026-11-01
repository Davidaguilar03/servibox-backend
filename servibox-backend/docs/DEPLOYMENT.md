# Backend en contenedor Linux

Preparacion local de la tarea 3, sin publicar. Contexto Docker: raiz Git exterior;
Maven: carpeta `servibox-backend/` interior. No usar esa carpeta como contexto Docker.

## Construccion

Desde la raiz Git, con Docker operativo:

```powershell
docker build --progress=plain -t servibox-backend:task3 .
```

Dockerfile multietapa: Temurin Java 21 JDK para construir con Maven Wrapper 3.3.4,
distribucion Maven 3.9.16 fijada en `.mvn/wrapper/maven-wrapper.properties`; runtime
Temurin Java 21 JRE, usuario/grupo 10001, solo JAR de aplicacion. Ambas imagenes base
estan fijadas por digest en Dockerfile. No hay Maven lockfile: las versiones salen del
pom, su parent Spring Boot 4.1.1 y sus BOM. Requiere acceso a Maven Central; reproducible
describe los insumos y el procedimiento, no garantiza JAR identico byte a byte.
El build omite ejecutar tests; la suite se ejecuta aparte con `mvnw.cmd test`.
`.dockerignore` permite solo pom, wrapper y src, excluyendo ademas formatos de secretos.
No poner secretos dentro de src; nunca pasarlos mediante COPY, ARG o ENV de build.

## Variables de ejecucion

| Nombre | Obligatoria en prod | Contrato |
|---|---|---|
| SPRING_PROFILES_ACTIVE | Si, fijada en imagen | `prod`. No activar `dev` ni `test` ni sobreescribir con otros perfiles |
| DB_URL | Si | `jdbc:postgresql://HOST:PUERTO/BASE` y parametros JDBC comprobados; sin credenciales embebidas |
| DB_USERNAME | Si | Usuario PostgreSQL con permisos de migracion y operacion en la base elegida |
| DB_PASSWORD | Si | Clave de ese usuario; gestionarla como secreto de ejecucion |
| JWT_SECRET | Si | Aleatorio, privado, al menos 32 bytes UTF-8; se rechaza ausente, vacio, blanco, corto o el fallback conocido de desarrollo |
| PORT | No en local; lo provee Railway | Entero de puerto HTTP; fallback 8080. Escucha en todas las interfaces del contenedor |
| SERVER_PORT | No | Tiene precedencia sobre PORT por Spring. En Railway dejarlo sin definir para seguir el puerto del proveedor |
| JWT_EXPIRATION_MS | No | Milisegundos positivos, default 86400000 |
| PLATFORM_JWT_SECRET | Si | Clave aleatoria privada exclusiva de plataforma, al menos 32 bytes UTF-8; distinta de JWT_SECRET, sin valores dev/test ni fallback productivo |
| PLATFORM_JWT_EXPIRATION_MS | No | Entre 1 y 86400000 milisegundos, default 900000 (15 minutos); sin refresh |
| REGISTRATION_ENABLED | No | Booleano, default true; false responde 403 al registro inmediato en dev. En prod no tiene efecto: el registro inmediato responde siempre 404 (ver Registro inmediato cerrado en prod) |
| REGISTRATION_REQUESTS_ENABLED | No | Booleano, default false. false: POST /api/registration-requests y POST /api/registration-requests/activate responden 404. true: abre ambas y cierra el registro inmediato (403). No activar en publico sin verificar un envio real de correo ni sin configurar y comprobar PUBLIC_RATE_LIMIT_CLIENT_IP_HEADER en Railway (ver Limite de tasa publico) |
| REGISTRATION_REQUESTS_PENDING_RESERVATION | No | Duracion Spring positiva (7d, 48h, P7D), default 7d: vigencia de la reserva de slug en PENDING. Confirmado por David el 2026-10-31 |
| REGISTRATION_REQUESTS_APPROVED_RESERVATION | No | Duracion positiva, default 30d desde la aprobacion. Confirmado por David el 2026-10-31 |
| REGISTRATION_REQUESTS_IDEMPOTENCY_RETENTION | No | Duracion positiva, default 48h de retencion de Idempotency-Key. Confirmado por David el 2026-10-31. Un plazo no positivo impide arrancar |
| ACTIVATION_BASE_URL | Si | URL publica del frontend para el enlace `/activar#token=...`, sin query ni fragmento, maximo 200 caracteres. prod exige `https://`; nunca se toma del header Host. Dev: http://localhost:5173 |
| REGISTRATION_ACTIVATION_TOKEN_TTL | No | Duracion positiva, default 24h de vigencia del enlace. Confirmado por David el 2026-10-31 |
| REGISTRATION_ACTIVATION_RESEND_COOLDOWN | No | Duracion no negativa, default 60s entre enlaces de una solicitud. Confirmado por David el 2026-10-31 |
| REGISTRATION_ACTIVATION_RESEND_DAILY_LIMIT | No | Entero positivo, default 5 reenvios por solicitud en 24 h moviles. Confirmado por David el 2026-10-31 |
| PUBLIC_RATE_LIMIT_ENABLED | No | Booleano, default true. false apaga el limite de tasa por IP de las dos rutas publicas de solicitudes (solo para pruebas) |
| PUBLIC_RATE_LIMIT_CLIENT_IP_HEADER | Si en Railway | Nombre de la cabecera con la IP del cliente que pone el proxy de confianza; vacia (default) usa la IP del socket e ignora X-Forwarded-For y X-Real-IP. En Railway: `X-Real-IP`. Se toma la ultima entrada; un valor que no parece IP vuelve a la del socket. La usan tambien los logins (tarea 50). En prod, vacia con algun limite encendido registra un WARN al arrancar (no impide arrancar) |
| PUBLIC_RATE_LIMIT_MAX_ENTRIES | No | Entero positivo, default 10000 IP por ruta. Lleno y sin ventanas vencidas, una IP nueva recibe 429 (falla cerrado) |
| PUBLIC_RATE_LIMIT_REQUESTS_LIMIT | No | Entero positivo, default 5 POST /api/registration-requests por IP y ventana |
| PUBLIC_RATE_LIMIT_REQUESTS_WINDOW | No | Duracion positiva, default 1h |
| PUBLIC_RATE_LIMIT_ACTIVATION_LIMIT | No | Entero positivo, default 10 POST /api/registration-requests/activate por IP y ventana |
| PUBLIC_RATE_LIMIT_ACTIVATION_WINDOW | No | Duracion positiva, default 15m |
| LOGIN_THROTTLE_ENABLED | No | Booleano, default true. false apaga la proteccion contra fuerza bruta de los dos logins (solo para pruebas) |
| LOGIN_THROTTLE_WINDOW | No | Duracion positiva, default 15m: ventana fija de las tres cuotas de fallos de login |
| LOGIN_THROTTLE_IP_ACCOUNT_LIMIT | No | Entero positivo, default 5 fallos por IP + cuenta y ventana |
| LOGIN_THROTTLE_ACCOUNT_LIMIT | No | Entero positivo, default 20 fallos por cuenta desde cualquier IP y ventana |
| LOGIN_THROTTLE_IP_LIMIT | No | Entero positivo, default 30 fallos por IP en total (ambos logins) y ventana |
| LOGIN_THROTTLE_MAX_ENTRIES | No | Entero positivo, default 20000 claves por tipo (par, cuenta, IP). Lleno y sin ventanas vencidas, una clave nueva recibe 429 (falla cerrado) |
| MAIL_OUTBOX_ENCRYPTION_KEY | Si | Clave aleatoria privada de al menos 32 bytes UTF-8 que cifra el cuerpo pendiente del outbox; distinta de JWT_SECRET y PLATFORM_JWT_SECRET, sin valores dev/test ni fallback productivo. Rotarla: ver Rotacion de la clave del outbox |
| MAIL_OUTBOX_ENCRYPTION_KEY_PREVIOUS | No | Vacia por defecto. Solo durante una rotacion: la clave anterior, usada SOLO para descifrar lo pendiente cifrado con ella; lo nuevo se cifra siempre con la actual. Si se define en prod: al menos 32 bytes, distinta de la actual, de JWT_SECRET y de PLATFORM_JWT_SECRET, sin prefijo dev-/test-; si no, no arranca |
| MAIL_OUTBOX_POLL_INTERVAL | No | Duracion positiva, default 30s entre lotes del worker |
| MAIL_OUTBOX_MAX_ATTEMPTS | No | Entero positivo, default 6 intentos antes de FAILED |
| MAIL_OUTBOX_RETRY_BACKOFF | No | Duracion positiva, default 1m; se duplica por intento con tope 1 h |
| MAIL_OUTBOX_PURGE_ENABLED | No | Booleano, default true. false apaga la purga programada del outbox |
| MAIL_OUTBOX_PURGE_INTERVAL | No | Duracion positiva, default 1h entre purgas |
| MAIL_OUTBOX_RETENTION_SENT_DAYS | No | Entero positivo, default 30: dias desde el envio tras los que se borra una fila SENT |
| MAIL_OUTBOX_RETENTION_FAILED_DAYS | No | Entero positivo, default 90: dias desde el vencimiento tras los que se borra una fila FAILED. Cero o negativo en cualquiera de los dos impide arrancar |
| SMTP_HOST | No | Servidor SMTP. Sin valor no hay transporte: los correos quedan PENDING y la app arranca igual (aviso en log). Proveedor sin elegir |
| SMTP_PORT | No | Entero, default 587. Con SMTP_SECURITY=ssl fijarlo en 465 (no cambia solo) |
| SMTP_SECURITY | No | `starttls` (default: exige STARTTLS) o `ssl` (TLS implicito, SMTPS). Otro valor impide arrancar con `SMTP_SECURITY debe ser starttls o ssl` (se valida solo con SMTP_HOST). En ambos se verifica el certificado y el nombre del servidor |
| SMTP_USERNAME | No | Usuario SMTP; vacio desactiva la autenticacion SMTP |
| SMTP_PASSWORD | No | Secreto de ejecucion del proveedor; nunca en codigo, logs, evidencia ni chat |
| MAIL_FROM | Si con SMTP_HOST | Remitente; con SMTP_HOST y sin MAIL_FROM la app no arranca |
| BUSINESS_TIME_ZONE | No | Id de java.time.ZoneId, default America/Bogota. Auditoria UTC |
| REPORTING_MAX_EXPORT_ROWS | No | Entero positivo, default 20000 movimientos por exportacion; superarlo responde 400 |

Exportacion: rango maximo existente de 366 dias para PDF/XLSX; acotar fechas y filas.
`DEV_DEMO_DATA` es exclusivo de dev, no se usa en prod. No hay siembra demo en prod.
El JAR conserva bibliotecas H2 de desarrollo por el pom existente, pero prod fija el
driver PostgreSQL y exige DB_URL; no arranca una base H2. Flyway aplica V1 a V12 (V9:
SOLICITUDES_REGISTRO; V10: ACTIVATED, ACTIVACIONES_REGISTRO y CORREOS_SALIENTES; ninguna
toca tablas comerciales; V11: version y suspension en TENANTS, auth_version en USUARIOS y
AUDITORIA_PLATAFORMA, con valores por defecto sobre filas existentes; V12: reenvio de
activacion en AUDITORIA_PLATAFORMA, conserva las filas de V11),
Hibernate valida con `ddl-auto=validate` y `open-in-view=false`. No usar repair, baseline
ni ddl-auto=update para esquivar errores. Conservar el mismo JWT_SECRET al reiniciar;
cambiarlo invalida tokens previos. Ninguna variable secreta lleva un ejemplo real aqui.

### URL del proveedor y JDBC

Railway ofrece PGHOST, PGPORT, PGUSER, PGPASSWORD, PGDATABASE y DATABASE_URL
([documentacion oficial](https://docs.railway.com/databases/postgresql)). No asignar
DATABASE_URL directamente a DB_URL: una URI `postgresql://USUARIO:CLAVE@HOST:PUERTO/BASE`
no es una URL JDBC. Formar `jdbc:postgresql://HOST:PUERTO/BASE`, pasar usuario y clave por
DB_USERNAME y DB_PASSWORD, y revisar codificacion de caracteres reservados y parametros
como sslmode segun la conexion elegida. Preferir construir desde campos separados;
si se parte de una URI, analizarla y decodificar credenciales correctamente, sin
reemplazos de texto ciegos ni imprimirla. Ver [formatos de pgJDBC](https://jdbc.postgresql.org/documentation/use/).
La red privada, TLS, credenciales y version PostgreSQL 16 del servicio real se verifican
en la futura tarea de publicacion; este documento no demuestra esa configuracion.

## Salud y operacion prevista en Railway

### Bootstrap privado de plataforma

No existe cuenta por defecto, alta HTTP ni aprovisionamiento al arrancar el servidor.
Despues de configurar conexion y ambas claves de firma privadas en el entorno, ejecutar
desde Maven con el JAR empaquetado y perfil elegido por SPRING_PROFILES_ACTIVE:

```powershell
java -jar target/servibox-backend-0.0.1-SNAPSHOT.jar --platform-bootstrap
```

Exige consola Java real: solicita email y contrasena con readPassword (entrada oculta).
Si la terminal del IDE no ofrece consola, rechaza; no pasar la contrasena por argumentos.
Clave de cuenta: minimo 12 caracteres, maximo 72 bytes UTF-8, sin recorte. Email normalizado
a minusculas, maximo 150 caracteres. No usar credenciales demo o reales en pruebas.
Una cuenta ya existente rechaza la reejecucion, incluso con otro email; nunca reemplaza
hash/estado/version. La PK fija del primer operador tambien impide INSERT concurrente.

Automatizacion privada: selector exclusivo `--platform-bootstrap-stdin`, dos lineas UTF-8
por stdin (email, contrasena), suministradas por secret manager o pipe privado a un proceso
hijo con entrada redirigida. Nunca teclear en terminal con eco, usar literales en comandos,
transcribir sesiones, guardar el pipe en evidencia ni usar archivos versionados. No se
aceptan argumentos adicionales; configuracion por entorno privado de ejecucion. Los
secretos de entrada no son variables permanentes de todos los contenedores del servicio.

Operacion futura en contenedor, desde la raiz Git y con imagen construida:

```powershell
docker run --rm -it --network RED_PRIVADA --env-file RUTA_PRIVADA_RUNTIME servibox-backend:platform-security --platform-bootstrap
```

RED_PRIVADA y RUTA_PRIVADA_RUNTIME son referencias del operador, no valores existentes.
El archivo privado contiene solo configuracion runtime/DB/firmas, tiene permisos
restringidos y queda fuera de Git y evidencia. Preferir inyeccion del proveedor de secretos.
No publicar puertos. En automatizacion usar stdin redirigido con docker run -i y selector
stdin, sin TTY con eco. El bootstrap fuerza modo no web y rechaza un contexto HTTP si
una propiedad externa intenta sobreescribirlo. Siempre cierra contexto/pool al salir;
codigo 0 solo al crear y 1 ante rechazo/error. Dev excluye siembra demo durante bootstrap.
Para persistencia local de pruebas usar una base desechable explicitamente configurada;
el H2 en memoria por defecto no persiste entre comandos. No crear una cuenta real como QA.

### Correo de activacion

Aprobar o reenviar solo encola; el worker del outbox envia cada MAIL_OUTBOX_POLL_INTERVAL.
Estado SENT significa que el transporte acepto el mensaje, no que llego al buzon ni que se
leyo; el buzon real y los filtros de spam del proveedor no estan verificados. Varias
instancias pueden correr el worker a la vez (SKIP LOCKED mas lease); una caida entre el
envio y el cierre puede duplicar un correo. Consultar `correos_salientes` (estado,
intentos, ultimo_error) por SQL para diagnostico; nunca exportar `payload_cifrado`.

Purga (tarea 51): cada MAIL_OUTBOX_PURGE_INTERVAL se borran por lotes las filas SENT con
mas de MAIL_OUTBOX_RETENTION_SENT_DAYS dias desde el envio y las FAILED con mas de
MAIL_OUTBOX_RETENTION_FAILED_DAYS dias desde su vencimiento. Nunca PENDING ni filas con
lease vigente; no toca activaciones ni solicitudes. Varias instancias pueden purgar a la vez
(SKIP LOCKED). El diagnostico por SQL solo ve lo que no se ha purgado.

SMTP: con SMTP_SECURITY=starttls (default) el proveedor debe ofrecer STARTTLS en 587; con
`ssl`, TLS implicito en 465 (fijar SMTP_PORT=465). En ambos se verifica certificado y
nombre del servidor; un proveedor con certificado invalido o de otro nombre falla (no hay
opcion para confiar en todos).

### Rotacion de la clave del outbox (tarea 51)

La clave solo protege el cuerpo de los correos PENDING (el enlace de activacion). Para
cambiarla sin perder lo pendiente:

1. Generar una clave nueva aleatoria de al menos 32 bytes, distinta de JWT_SECRET y
   PLATFORM_JWT_SECRET, en el gestor de secretos. No imprimirla ni pegarla en chat o logs.
2. En el servicio: MAIL_OUTBOX_ENCRYPTION_KEY_PREVIOUS = valor actual de
   MAIL_OUTBOX_ENCRYPTION_KEY; MAIL_OUTBOX_ENCRYPTION_KEY = la nueva. Desplegar.
3. Revisar el arranque: si alguna no cumple las reglas, la app no arranca con un mensaje
   que nombra la variable. Con varias replicas, todas deben recibir ambas variables a la
   vez; una replica que siga solo con la clave vieja deja en FAILED (PAYLOAD_INVALIDO) lo
   nuevo que intente enviar.
4. Esperar a que no quede nada cifrado con la clave anterior. Todo correo PENDING vence con
   su enlace (REGISTRATION_ACTIVATION_TOKEN_TTL, default 24h), asi que basta esperar ese
   plazo mas un intervalo del worker, o confirmar por SQL que no quedan PENDING creados
   antes del despliegue del paso 2:
   `SELECT COUNT(*) FROM correos_salientes WHERE estado = 'PENDING' AND fecha_creacion < 'FECHA_DESPLIEGUE_UTC';`
5. Cuando de 0: quitar MAIL_OUTBOX_ENCRYPTION_KEY_PREVIOUS del servicio y desplegar. Borrar
   la clave vieja del gestor de secretos.

Sin el paso 2 (cambiar la clave sin definir la anterior), lo pendiente queda FAILED con
PAYLOAD_INVALIDO y hay que reenviar la activacion desde la plataforma. Si la clave se
filtro, rotar igual y reenviar las activaciones pendientes para que su enlace salga cifrado
con la nueva; la anterior solo sirve mientras siga definida.

Cada peticion de plataforma revalida activo/authVersion. La recuperacion privada auditada
y un mecanismo operativo de rotacion/revocacion no tienen comando en esta entrega;
deben definirse antes de operar publicamente. Rotar PLATFORM_JWT_SECRET invalida todos
sus JWT y no cambia JWT_SECRET tenant.

### Limite de tasa publico (tarea 49)

POST /api/registration-requests y POST /api/registration-requests/activate tienen cada una
su cuota por IP (defaults 5 por hora y 10 por 15 minutos). Al superarla: 429, cabecera
Retry-After en segundos y `{"error","status"}`. Con la compuerta apagada siguen en 404 sin
consumir cuota. Los logins tienen su propia proteccion (ver abajo); ninguna otra ruta tiene limite.

* En memoria y por instancia: con varias replicas cada una cuenta por su lado (el limite
  efectivo se multiplica) y un reinicio o despliegue borra los contadores.
* IP del cliente: Spring no tiene `server.forward-headers-strategy` configurado, asi que la
  IP es la del socket. Detras del proxy de Railway esa IP es la del proxy: sin
  PUBLIC_RATE_LIMIT_CLIENT_IP_HEADER todos los visitantes comparten una sola cuota (5
  solicitudes por hora en total). Railway documenta `X-Real-IP` "for identifying client's
  remote IP" ([specs and limits](https://docs.railway.com/networking/public-networking/specs-and-limits));
  no documenta X-Forwarded-For ni si reemplaza un X-Real-IP enviado por el cliente.
* Antes de abrir la compuerta, en el servicio desplegado: enviar peticiones con un
  `X-Real-IP` falso y confirmar que la cuota no cambia (el proxy debe reemplazarlo o
  agregar el suyo al final). Si Railway lo deja pasar tal cual, no configurar la cabecera.
* No activar `server.forward-headers-strategy` (NATIVE o FRAMEWORK): reescribe la IP del
  socket con X-Forwarded-For y anularia esta defensa. Configurar la cabecera solo si el
  puerto de la app no es alcanzable sin pasar por el proxy.

### Proteccion de los logins contra fuerza bruta (tarea 50)

POST /api/auth/login (tenant) y POST /api/platform/auth/login cuentan solo los intentos
fallidos (401), en memoria y por instancia, con tres cuotas en una ventana fija de 15
minutos: 5 por IP + cuenta, 20 por cuenta desde cualquier IP y 30 por IP en total (ambos
logins suman). Cuenta tenant: codigo de serviteca + usuario; cuenta plataforma: correo;
ambos en minusculas y sin espacios. Con una cuota llena: 429, Retry-After en segundos y
`Demasiados intentos de inicio de sesion, espera unos minutos e intenta de nuevo`, sin
evaluar la contrasena. Un exito limpia el par IP + cuenta (no la IP total). Las cuentas que
no existen se cuentan y bloquean igual. Los 401 no cambian. Un 400 (cuerpo invalido) no cuenta.

* La IP sale de la misma regla que el limite publico (PUBLIC_RATE_LIMIT_CLIENT_IP_HEADER).
  Sin esa cabecera en Railway, todos los visitantes comparten la IP del proxy: 30 fallos de
  cualquiera bloquean el login de todos durante 15 minutos. Es obligatorio configurarla.
* La cuota por cuenta permite a un tercero bloquear el login de una cuenta concreta durante
  la ventana con 20 fallos (el usuario legitimo incluido). No hay bloqueo permanente: se
  libera sola al vencer la ventana o al reiniciar la instancia.
* Por instancia: con varias replicas el limite efectivo se multiplica; un reinicio borra
  los contadores.

### Registro inmediato cerrado en prod (tarea 51)

Con SPRING_PROFILES_ACTIVE=prod, POST /api/auth/register responde siempre 404
`{"error":"Recurso no encontrado","status":404}` y no crea nada, sin importar
REGISTRATION_ENABLED ni REGISTRATION_REQUESTS_ENABLED. La unica via de alta en prod es la
solicitud revisada. Con REGISTRATION_REQUESTS_ENABLED=false (default) tambien las solicitudes
y la activacion responden 404: el registro queda completamente cerrado, nunca abierto. En dev
y test el registro inmediato sigue como antes (lo usa el script de siembra externo de dev).

### Auditoria del reenvio de activacion (tarea 51)

Cada reenvio exitoso (202) de POST /api/platform/registration-requests/{id}/resend-activation
inserta en `auditoria_plataforma` una fila RESEND_ACTIVATION con el administrador y
`id_solicitud_objetivo`, en la misma transaccion. No hay endpoint de lectura: se consulta por SQL.

### Comprobaciones obligatorias del despliegue en Railway

Antes de abrir la compuerta de solicitudes (REGISTRATION_REQUESTS_ENABLED=true) y antes de
dar acceso publico a los logins:

1. Configurar `PUBLIC_RATE_LIMIT_CLIENT_IP_HEADER=X-Real-IP` en el servicio backend.
2. Revisar el log de arranque: no debe aparecer el WARN `PUBLIC_RATE_LIMIT_CLIENT_IP_HEADER
   esta vacia...`. Si aparece, la variable no llego al proceso.
3. Contra el servicio desplegado, desde una misma conexion, enviar logins fallidos con un
   `X-Real-IP` falso distinto en cada peticion (por ejemplo una cuenta de prueba inexistente
   en un tenant inexistente): al sexto intento debe llegar el 429. Si cada X-Real-IP falso
   obtiene cuota nueva, Railway deja pasar el valor del cliente: quitar la variable y no
   abrir la compuerta hasta resolverlo. Repetir con POST /api/registration-requests/activate
   (token falso) si se va a abrir la compuerta.
4. Confirmar que un login correcto desde otra conexion sigue funcionando.

Lista de comprobaciones de despliegue (tarea 51), ademas de las de arriba:

5. Variables obligatorias de prod definidas y privadas: DB_URL, DB_USERNAME, DB_PASSWORD,
   JWT_SECRET, PLATFORM_JWT_SECRET, MAIL_OUTBOX_ENCRYPTION_KEY (tres claves distintas) y
   ACTIVATION_BASE_URL con https. MAIL_OUTBOX_ENCRYPTION_KEY_PREVIOUS vacia salvo durante
   una rotacion.
6. Log de arranque: Flyway en version 12 y `Started ServiboxApplication`; sin el WARN de
   PUBLIC_RATE_LIMIT_CLIENT_IP_HEADER.
7. POST /api/auth/register responde 404. Con la compuerta apagada, POST
   /api/registration-requests y .../activate responden 404.
8. Si hay SMTP: SMTP_SECURITY coherente con SMTP_PORT (starttls con 587, ssl con 465) y un
   envio real verificado en un buzon antes de abrir la compuerta.
9. Un reenvio de activacion de prueba deja su fila RESEND_ACTIVATION en `auditoria_plataforma`.
10. Purga encendida (MAIL_OUTBOX_PURGE_ENABLED sin definir o true) salvo motivo documentado.
11. HSTS y cabeceras del lado del proxy o frontend: ver los hallazgos de superficie de la
    sesion 2026-10-31-backend-hardening.

### Suspension y revocacion de tenants (5A)

Sin variables nuevas. Suspender, reactivar y revocar se hacen solo por la API de plataforma
y quedan en `auditoria_plataforma` (accion, administrador, tenant, usuario, motivo, fecha).
Surten efecto en la siguiente peticion: cada peticion tenant consulta activo_tenant y
compara el claim authVersion con `usuarios.auth_version`. Los JWT tenant emitidos antes
de desplegar V11 no traen el claim y cuentan como version 0: siguen validos hasta vencer o
hasta la primera revocacion. No editar `activo_tenant` ni `auth_version` a mano por SQL:
se salta la version, el motivo y la auditoria.

### Disponibilidad HTTP

`GET /api/health` devuelve 200 con `{"status":"UP"}` sin autenticacion ni datos internos.
Solo ese metodo y ruta se agregan a las excepciones publicas existentes de login y registro.
Es disponibilidad del servidor HTTP: no consulta la base ni garantiza transacciones,
latencia o espacio de disco. Un arranque nuevo necesita primero Flyway y validacion
Hibernate, pero perder la base despues no vuelve rojo este chequeo. Comprobar la base
por conexion SQL y un smoke autenticado independiente.

Configuracion futura, sin haberla aplicado: raiz del servicio `/` (raiz Git), Dockerfile
`Dockerfile`, arranque por ENTRYPOINT de imagen, ruta de healthcheck `/api/health`.
No configurar otro comando de build/start que active dev. Railway detecta Dockerfile
en la raiz del origen, ver [Dockerfiles](https://docs.railway.com/builds/dockerfiles).
Su healthcheck usa PORT durante el despliegue y no es monitoreo continuo posterior,
ver [Healthchecks](https://docs.railway.com/deployments/healthchecks).
No se agrega HEALTHCHECK Docker que requiera curl ni dependencia Actuator.

Topologia prevista: frontend/proxy separado con `/api` en el mismo origen. Sin CORS
abierto. Frontend/proxy, backups, dominio servibox.co, TLS publico y publicacion quedan
para sus tareas. No se ha validado consumo/costo de Railway ni contratado servicios.

## Reproduccion local completa

La nota de [sesion](ai-context/sessions/2026-10-29-backend-deployment.md) enlaza el
orquestador externo y los resultados. El auxiliar genera claves efimeras en memoria,
crea red/volumen/PostgreSQL 16 exclusivos, publica solo HTTP en loopback, inicia prod,
prueba JWT invalido al arranque, PORT, registro/login, cuenta/producto y persistencia,
y elimina solo sus recursos etiquetados. Ejecutarlo en una carpeta nueva de evidencia;
no reutilizar carpetas de tareas previas. Requiere Node y Docker existentes, sin instalar
dependencias. No usa ni detiene PostgreSQL 18 del host.
