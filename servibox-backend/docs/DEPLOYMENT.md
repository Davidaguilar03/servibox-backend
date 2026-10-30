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
| REGISTRATION_ENABLED | No | Booleano, default true; false responde 403 al registro. Decidir politica antes de publicar |
| BUSINESS_TIME_ZONE | No | Id de java.time.ZoneId, default America/Bogota. Auditoria UTC |
| REPORTING_MAX_EXPORT_ROWS | No | Entero positivo, default 20000 movimientos por exportacion; superarlo responde 400 |

Exportacion: rango maximo existente de 366 dias para PDF/XLSX; acotar fechas y filas.
`DEV_DEMO_DATA` es exclusivo de dev, no se usa en prod. No hay siembra demo en prod.
El JAR conserva bibliotecas H2 de desarrollo por el pom existente, pero prod fija el
driver PostgreSQL y exige DB_URL; no arranca una base H2. Flyway aplica V1 a V7,
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
