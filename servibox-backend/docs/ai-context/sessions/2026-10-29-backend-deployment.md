# Preparacion backend para despliegue, 2026-10-29

Se puede continuar con la revision de David. Railway no fue publicado ni probado.
Frontend/proxy, dominio, backups, secretos reales y QA publico siguen pendientes.

## Git y alcance

Rama chore/43-backend-deployment creada desde main actualizado: 984b8274fe30af5b05cbfc72ccfdf5603a7c223d,
igual a origin/main tras fetch; PR 42 integrado. Rama libre local y remota antes de
crearla. Raiz Git exterior servibox-backend; Maven en servibox-backend/ interior.
Los cambios ajenos .idea/compiler.xml y .idea/servibox-backend.iml se conservaron.
Sin frontend, AGENTS, migraciones V1 a V7, commit, push, PR ni servicios externos.

## Cambios

Nuevos: Dockerfile y .dockerignore en raiz Git; docs/DEPLOYMENT.md;
config/ProductionConfiguration.java y config/HealthController.java;
config/ProductionConfigurationTest.java y config/HealthSecurityTest.java;
esta nota. Modificados: application-prod.properties, SecurityConfig.java,
01-ARCHITECTURE.md y 03-DECISIONS.md. Doce archivos propios en total.

Contenedor multietapa con wrapper 3.3.4/Maven 3.9.16 y Java 21; runtime JRE con usuario
10001:10001, sin Maven, compilador ni fuentes de aplicacion. Contexto desde raiz Git;
Docker transfirio 1.20 MB. Prod usa PORT con fallback 8080, respetando SERVER_PORT;
en Railway se debe dejar SERVER_PORT sin definir. DB_URL es JDBC PostgreSQL y las
credenciales se pasan por entorno. Driver PostgreSQL explicito, Flyway, validate y
open-in-view=false. Ninguna dependencia nueva ni cambio de negocio.

JWT_SECRET se exige en prod antes de crear beans de aplicacion; no blanco, al menos
32 bytes UTF-8 y distinto del fallback dev. Desarrollo y tests conservan su facilidad
local. Mensaje nuevo exacto de arranque, no respuesta HTTP ni texto para mapa frontend:

`JWT_SECRET es obligatorio en prod, debe tener al menos 32 bytes y no puede ser el secreto de desarrollo`

GET /api/health publico devuelve solo {"status":"UP"}. No consulta base; incluso con
PostgreSQL detenido devuelve 200. Solo ese metodo/ruta se abre adicionalmente. Los
defaults dev y sus clases siguen en el JAR compartido, pero el contenedor activa prod,
sin siembra demo ni conexion H2. No agregar secretos dentro de src.

## Versiones y digests

* Docker Desktop 4.75.0, Engine 29.5.2, Linux amd64.
* Imagen build: eclipse-temurin:21-jdk-jammy@sha256:4b1a74fbffbe6b4e7b6f0171a472478282fb463790a51edf10e465d578f22b69.
* Imagen runtime: eclipse-temurin:21-jre-jammy@sha256:1eac8b8fee11ca7d6725a48bc5d41992ed1561504ebae25490d2d062847e8269.
* Runtime verificado: Temurin 21.0.13+9. Host Maven: Java 21.0.9.
* Base: postgres@sha256:9e2cf6287f25c3151f4c9421ccce2b17f3e60061c111a29165e0d2cdd9ff0a97;
  SELECT version y server_version_num confirman PostgreSQL 16.15 / 160015.
* Imagen local final servibox-backend:task3:
  sha256:82602c41ea62dffdddc04dbbe8de6d90a0d219e728883737bd5cd55082825841,
  175736714 bytes, linux/amd64. Sin publicacion en registro.

## Validacion

* Suite completa: 483 base + 11 nuevas = 494, cero fallos/errores/omitidas, 03:17 min.
  Integracion de la suite en H2; no se declara suite completa sobre PostgreSQL.
  ProductionConfigurationTest: 9 nuevas; HealthSecurityTest: 2 nuevas.
  Ninguna prueba existente ajustada o eliminada.
* Maven package posterior con skipTests: BUILD SUCCESS, 7.948 s. Docker build desde
  raiz: BUILD SUCCESS; Maven dentro de Linux 57.861 s. Son empaquetados, no otras suites.
* Corrida Docker corregida: 57/57 comprobaciones, incluidas 17 peticiones HTTP reales.
  No sumar esos controles a JUnit ni a las tareas anteriores.
* JWT ausente, vacio, blanco y fallback dev: salida no cero con mensaje claro y ninguna
  tabla creada antes del rechazo. Arranque valido: prod, PORT=9017, Flyway V1 a V7 y
  Hibernate inicializado sin error de esquema. Cero tenants antes del registro.
* Registro 201, login 200, perfil 200; cuenta 201, inicial/saldo 1234.56 y color #ABCDEF;
  producto 201, stock 0 y lectura protegida 200. Anonimo en tenant/productos: 401;
  POST salud y GET subruta salud: 401. Salud publica: 200, solo status UP.
* Reinicio de aplicacion conservando base: perfil, cuentas/saldos, producto e historial
  Flyway identicos; JWT previo util. PostgreSQL tambien se detuvo/inicio para demostrar
  la diferencia entre salud HTTP y base. Volumen propio conservado durante pruebas.
* Runtime UID/GID 10001:10001. Imagen sin variables DB_PASSWORD/JWT_SECRET embebidas.
  Inspeccion de 8 capas, 5333 archivos y 369 entradas del JAR: cero hallazgos de claves
  efimeras o archivos credenciales inesperados. Sin secretos efimeros en logs.
  Es una inspeccion acotada de valores conocidos/rutas, no una auditoria general.
* git diff --check correcto. Backend no define lint. No frontend ni navegador.

## Evidencia y reproduccion

Directorio externo conservado:
C:/Users/PC/Documents/Codex/2026-10-29/servibox-backend-deployment/.
Informe completo informe.md; suite en junit.log/junit-summary.json; builds en package.log
y docker-build.log. Resultado aceptado en verified/: checks.json con esperado/obtenido,
http.json redactado, logs, historial Flyway antes/despues, snapshots de persistencia,
versiones, usuario/imagen, inspeccion de capas y cleanup.json. Fuentes smoke.mjs e
inspect-image.py fuera del repo; comandos exactos de Docker en verified/commands.json.

Desde raiz Git: docker build con tag servibox-backend:task3 y contexto punto.
Desde carpeta Maven: mvnw.cmd test y mvnw.cmd -DskipTests package.
Para repetir smoke desde raiz Git, PowerShell:

```powershell
$env:EVIDENCE_DIR='C:/ruta/nueva/evidencia-task3'
node C:/Users/PC/Documents/Codex/2026-10-29/servibox-backend-deployment/smoke.mjs
```

El auxiliar necesita Node, Python y Docker existentes. Genera claves en memoria,
red/volumen/nombres exclusivos y publica HTTP solo en 127.0.0.1 con puerto host libre;
PostgreSQL no publica puerto host. Variables y referencias oficiales en DEPLOYMENT.md.
Las imagenes base y el wrapper fijan versiones; no existe lockfile Maven ni garantia
de identidad binaria byte a byte. No se instalan dependencias del proyecto adicionales.

Primer intento externo: 45 controles previos correctos, luego Timeout salud porque
el auxiliar reutilizaba el puerto host aleatorio anterior tras docker restart. Se
observo 58343 antes y 58365 despues, con puerto interno 9017 constante. Corregido solo
el auxiliar para consultar docker port de nuevo; corrida completa posterior separada
en verified/. Primer resultado FAIL y limpieza conservados en raiz externa, incident.json.
No se cambio producto ni expectativas para aprobar. No sumar ambos intentos.

## Limpieza y pendientes

Ambos intentos eliminaron solo sus contenedores, red y volumen, verificando etiquetas.
El archivo temporal de inspeccion de capas se elimino. Se conservan imagen final local,
imagenes base y cache Docker para revision, sin servidores propios activos. PostgreSQL
18 del host y contenedores ajenos intactos. No se reintento borrar temporales bloqueados.
Hashes finales ajenos identicos: compiler.xml
DD2D0E382A3182CDABF5EB6DE516CCEEFD651F266B4213C6C272CAFE8CC8F4B0;
servibox-backend.iml B8A314F0F6865642AA8409D0C0B4ED5BFC1781E65A147BA088AEC2CF8B11164A.

Pendiente: revision de David y cierre Git autorizado por separado, frontend/proxy,
configuracion real Railway/PostgreSQL16/TLS, politica de registro, secretos reales,
dominio, backups, presupuesto y QA publico/visual. Los 100000 COP son objetivo del
usuario, no costo validado ni autorizacion de compra. No se repitio el recorrido amplio
de exportaciones o concurrencia de tarea 2; su evidencia sigue siendo independiente.

## Cierre autorizado

David comunico la revision de la evidencia tecnica del informe externo y autorizo
commit, push y PR hacia main con titulo chore/43-backend-deployment. Esta tarea no
cambia interfaz y no requiere una nueva aprobacion visual. No se atribuye a David
una comprobacion manual del contenedor: lo comunicado fue la revision del informe.

Revision previa al cierre: rama correcta y doce archivos propios confirmados, ocho
nuevos y cuatro modificados; codigo, pruebas e insumos Docker sin cambios posteriores
a la evidencia aceptada. Solo se agrega esta nota de cierre. Se reutilizan las 494
JUnit, el empaquetado, el build Docker y las 57 comprobaciones locales (17 HTTP);
no se repiten por rutina. git diff --check y git diff --cached --check correctos.
Stage limitado a esas doce rutas, sin secretos, logs, temporales ni evidencia externa;
ambos archivos ajenos .idea excluidos y preservados. AGENTS, frontend y V1 a V7 intactos.
Railway sigue sin publicar ni probar. Sin merge ni cambio de rama al cerrar.
