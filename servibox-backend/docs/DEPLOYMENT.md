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
| REGISTRATION_ENABLED | No | Booleano, default true; false responde 403 al registro. Decidir politica antes de publicar |
| REGISTRATION_REQUESTS_ENABLED | No | Booleano, default false. false: POST /api/registration-requests responde 404. true: abre solicitudes y cierra el registro inmediato (403). No activar en publico hasta que 4B (activacion y correo) este verificada |
| REGISTRATION_REQUESTS_PENDING_RESERVATION | No | Duracion Spring positiva (7d, 48h, P7D), default 7d: vigencia de la reserva de slug en PENDING. Propuesta, no confirmada |
| REGISTRATION_REQUESTS_APPROVED_RESERVATION | No | Duracion positiva, default 30d desde la aprobacion. Propuesta, no confirmada |
| REGISTRATION_REQUESTS_IDEMPOTENCY_RETENTION | No | Duracion positiva, default 48h de retencion de Idempotency-Key. Propuesta, no confirmada. Un plazo no positivo impide arrancar |
| BUSINESS_TIME_ZONE | No | Id de java.time.ZoneId, default America/Bogota. Auditoria UTC |
| REPORTING_MAX_EXPORT_ROWS | No | Entero positivo, default 20000 movimientos por exportacion; superarlo responde 400 |

Exportacion: rango maximo existente de 366 dias para PDF/XLSX; acotar fechas y filas.
`DEV_DEMO_DATA` es exclusivo de dev, no se usa en prod. No hay siembra demo en prod.
El JAR conserva bibliotecas H2 de desarrollo por el pom existente, pero prod fija el
driver PostgreSQL y exige DB_URL; no arranca una base H2. Flyway aplica V1 a V9 (V9:
SOLICITUDES_REGISTRO, sin tocar tablas comerciales),
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

Cada peticion de plataforma revalida activo/authVersion. La recuperacion privada auditada
y un mecanismo operativo de rotacion/revocacion no tienen comando en esta entrega;
deben definirse antes de operar publicamente. Rotar PLATFORM_JWT_SECRET invalida todos
sus JWT y no cambia JWT_SECRET tenant. Suspension/reactivacion tenant pendiente de tarea 5.

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
