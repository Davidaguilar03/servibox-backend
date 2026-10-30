# Identidad y seguridad de plataforma, 2026-10-30

Revision tecnica aprobada a partir del informe externo; cierre Git autorizado.
La revision manual Docker global sigue aplazada y es obligatoria antes de publicar.
La continuacion verifico migracion con datos, arranque prod y seguridad en PostgreSQL
16 desechable. La evidencia anterior H2 se conserva por separado.

## Git y alcance

Raiz Git exterior; Maven en servibox-backend/ interior. Main y origin/main coinciden en
643494a tras fetch, merge PR 43 confirmado en historial. Rama feat/44-platform-security
libre local/remota antes de crearla. Se conservaron las dos modificaciones ajenas .idea.
Sin frontend, AGENTS, Dockerfile, migraciones V1 a V7, documentos academicos, commit,
push, PR, despliegue ni cuenta real. Informe de auditoria externo usado como guia,
contrastado con autenticacion, filtros, configuracion y esquema actuales.

## Contratos implementados

* V8 agrega ADMINISTRADORES_PLATAFORMA sin tenant; id estable, email unico normalizado,
  BCrypt, activo, auth_version no negativo. No se modifica Role comercial ni cuentas tenant.
* Bootstrap privado explicito: selectores platform-bootstrap y platform-bootstrap-stdin
  del main. Consola Java readPassword o dos lineas UTF-8 por stdin privado redirigido.
  No acepta argumentos adicionales. Contexto no web, rechazo de override HTTP, cierre
  automatico, sin siembra demo. Repeticion rechazada y persist/INSERT del id reservado 1
  impide reemplazos concurrentes. No hay creacion automatica ni registro HTTP de operadores.
* POST /api/platform/auth/login recibe email/password; devuelve token y admin {id,email}.
  GET /api/platform/auth/me devuelve solo {id,email}. Errores de credenciales genericos.
* Firma PLATFORM_JWT_SECRET diferente de JWT_SECRET; prod la exige sin fallback,
  minimo 32 bytes, no defaults dev/test. PLATFORM_JWT_EXPIRATION_MS default 900000,
  rango 1 a 86400000. Sin refresh. Claims: sub id, iss servibox-platform, aud platform,
  identityType PLATFORM, iat, exp, authVersion; prohibido tenantId. Activo y version se
  consultan cada peticion y fallo de base no autentica.
* Cadenas separadas y stateless: PLATFORM_IDENTITY solo en plataforma; TENANT_IDENTITY
  en familias comerciales. Resto denegado. Solo login propio publico en plataforma.
  Publicos tenant POST login/register y GET health exactos. Salud sigue status UP sin DB.
  Filtro tenant sin registro servlet automatico, no corre sobre plataforma. Limpieza
  de TenantContext y SecurityContext al entrar/salir. Sin impersonacion ni exportacion global.
* JWT tenant nuevo agrega iss servibox-tenant, aud tenant e identityType TENANT.
  Heredado solo sin los tres marcadores, con firma tenant y claims completos validos,
  hasta expirar. No rota JWT_SECRET ni modifica hashes o registro/login tenant.
  Usuario activo y rol efectivo desde BD. La version/revocacion tenant por suspension
  y reactivacion sigue pendiente para tarea 5, sin declararla resuelta.

Operacion local y futura en contenedor detallada en docs/DEPLOYMENT.md. Sin mecanismo
de recuperacion publica o gestion de operadores adicionales. Recuperacion privada auditada
y reautenticacion de acciones sensibles pendientes antes de operar acciones destructivas.

## Primera pasada: pruebas y evidencia H2

Base aislada desde main: 494, cero fallos/errores/omitidas. Primer intento de medir base
quedo contaminado porque se actualizo el diccionario mientras SchemaConformanceTest
seguia ejecutandose; fallo esa unica prueba. Se corrigio el procedimiento ejecutando
main en worktree temporal separado, eliminado despues. No se atribuye el primer intento
a una falla del producto. Ver base-isolated-junit.log y base-summary.json externos.

Verificacion focalizada: 61, cero fallos/errores/omitidas. Cobertura nueva: bootstrap,
DTO, login, activo/version, firmas/tipos/audiencias, claims ausentes/mal tipados, legacy,
familias comerciales y metodos, cierre de rutas desconocidas, contexto y fallos de BD.

Pruebas existentes ajustadas, sin retirar aserciones comerciales:
* AuthLoginIntegrationTest: solo ruta sintetica pasa al namespace inventory permitido.
* ProductionConfigurationTest: tres casos validos agregan clave plataforma exclusiva.
* SchemaConformanceTest: 15 tablas en vez de 14, misma comparacion exhaustiva del modelo.
* FlywayMigrationTest.lasMigracionesAplicanLimpiasSobreUnaBaseVacia: conteo 15 tablas.
* RequestBodyErrorsTest: controlador y cuatro usos de ruta sintetica movidos a inventory;
  se conservan los 400 de enums invalidos y el 200 de valores validos.
* ServiboxApplicationTests.contextLoads: perfil test explicito, sin fallback productivo.

Primera suite completa con cambios detecto esos tres ajustes de preparacion aun pendientes:
534 ejecutadas, cinco fallos y un error (cuatro rutas sinteticas, un conteo, un perfil).
Se corrigen las preparaciones, sin modificar reglas de negocio ni eliminar aserciones.

Integracion JUnit usa H2. No se repite bateria financiera externa ni navegador.
Suite completa final: 494 base + 40 nuevas = 534, cero fallos/errores/omitidas,
03:21 min. Nuevas: 36 PlatformSecurityTest, 3 PlatformFailureTest y 1 caso adicional
en ProductionConfigurationTest. Catorce casos existentes afectados en seis archivos,
ninguno retirado. Empaquetado final con skipTests correcto; es build, no otra suite.
El primer smoke real detecto 401 en vez de 403 en ruta desconocida por despacho /error;
se corrigio con JSON 403 directo y se conservo el intento fallido separado.
Smoke final: 45/45 comprobaciones, incluidas 22 peticiones HTTP reales, sin navegador,
sobre H2 de archivo desechable y perfil default con claves efimeras explicitas. Seis
escenarios CLI: sin consola rechazado, entrada invalida rechazada, creacion por stdin
privado correcta, repeticion rechazada, otro email rechazado y override HTTP rechazado.
Todos los procesos CLI cerraron sin servidor HTTP. Consola interactiva oculta implementada,
no ensayada de forma interactiva; contenedor no ensayado por Docker apagado.
Registro tenant 201, login de ambos ambitos y me 200; tokens cruzados 401, ocho familias
comerciales rechazadas, rutas/metodos desconocidos 401/403 segun identidad, salud intacta.
DTOs exactos y sin secretos en logs comprobados. No sumar checks externos a JUnit.
PID y scratch propios identificados en verified/http-smoke.json, servidor detenido y
base eliminada. No se tocaron servicios ajenos ni se reintentaron limpiezas bloqueadas.
Git diff check correcto; ambos .idea conservan hashes iniciales. Sin stage/commit/push/PR.

Evidencia externa: C:/Users/PC/Documents/Codex/2026-10-30/servibox-platform-security/.
Se conserva redactada, sin claves, passwords privados, hashes reales o JWT funcionales.

## Continuacion PostgreSQL 16, 2026-10-30

Daemon Docker 29.5.2 disponible. PostgreSQL 16.15 real, version numerica 160015, en
base, volumen y red nuevos exclusivos, sin puerto host de BD. No se usaron bases
existentes ni se toco PostgreSQL del host. Runtime anterior por imagen con ID
82602c41ea62dffdddc04dbbe8de6d90a0d219e728883737bd5cd55082825841; JAR actual
montado readonly para prod. Sin nuevo build de imagen ni cambio del Dockerfile.

232 hashes de fuentes/recursos/pruebas/pom/Dockerfile/AGENTS/.idea sin diferencias.
JAR SHA256 645A54D25E7DC529BABADE42EBF2F57F44CB5E9C8686ECB61D2A053F4E556ECF.
No cambios de codigo/configuracion: se reutilizan 534 JUnit, empaquetado y 45 checks
con 22 HTTP H2 previos. No se suman a los resultados PostgreSQL ni se atribuyen a PG.

Corrida aceptada: 180/180 comprobaciones y 113 HTTP reales, 16 de preparacion V7 y
97 sobre V8 prod. Es evidencia externa, no suite completa JUnit sobre PostgreSQL.
Dos tenants con usuarios y datos en las 14 tablas comerciales, 42 filas totales.
V7 se preparo usando la aplicacion anterior, que emitio JWT tenant heredados reales.
Tras detenerla, Flyway actual agrego solo V8; historial/checksums V1 a V7 identicos,
Hibernate valido esquema y arranque ordinario dejo cero administradores de plataforma.

Cantidades y digest de todas las columnas/filas comerciales identicos antes/despues
de migrar y al terminar bootstrap/autenticacion. Filas: tenants 2, usuarios 2,
categoria_productos 6, categoria_impuestos 6, tipos_impuesto 2, configuracion_financiera 2,
cuentas 4, productos 2, terceros 4, compras 2, detalle_compras 2, ventas 2,
detalle_ventas 2, movimientos 4. Stock por producto esperado/obtenido 1/1 y saldo de
cuenta esperado/obtenido 12345.67/12345.67. Sin nueva bateria financiera externa.

Ocho rechazos prod antes de migrar V7: clave plataforma ausente/vacia/blanca/corta,
default dev/test, igual a JWT_SECRET y ausencia de clave tenant. Diagnostico seguro,
salida no cero y datos intactos. Bootstrap real por stdin privado en contenedor:
invalido 1 y cero cuentas, correcto 0 y una cuenta normalizada, repetido/otro email 1
sin cambiar el digest de la cuenta. Sin HTTP ni puertos y cierre de todos los comandos.

Login/me plataforma 200 y DTO exacto; credenciales invalidas e identidad inactiva 401.
Desactivar o incrementar auth_version rechaza JWT previo; nuevo login con version 1
funciona. JWT tenant emitidos antes de migrar siguen validos solo en su ambito;
contrasenas anteriores sirven para nuevo login tenant con marcadores nuevos. Rol
efectivo y usuario activo revalidados por BD, tambien para JWT heredado. Estado del
tenant y su revocacion por suspension siguen pendientes de tarea 5.

Plataforma contra 13 rutas comerciales por cuatro metodos: 52 rechazos 401, incluidos
perfil y exportacion. Tenant contra plataforma 401; producto del vecino 404.
Firmas/tipos/audiencias cruzados, expiracion y claims invalidos 401 sin 500.
Rutas/metodos desconocidos denegados, 403 JSON consistente y salud sin cambios.
Logs comprobados sin credenciales privadas antes de conservar evidencia redactada.

Incidentes externos conservados: primer intento 133 checks correctos y error del
auxiliar al leer balance en lugar de currentBalance; segundo detecto readiness del
servidor temporal de initdb, que estaba apagandose. Solo se corrigieron auxiliares.
No sumar intentos: aceptado unicamente postgresql16/final/result.json, PASS.

Evidencia nueva externa bajo postgresql16/: source-before/source-verification.json;
final/ contiene checks, http, snapshots comerciales, historial Flyway, logs sanitizados,
comandos y limpieza. Informe previo y esta nota previa preservados alli como copias.
Corrida aceptada elimino 16 contenedores, un volumen y una red propios identificados
por etiqueta; los intentos previos tambien limpiaron sus recursos. Sin recursos o
cuentas efimeras persistentes, navegador, servicios ajenos, commit, push ni PR.

Revision tecnica aprobada y cierre autorizado. Consola oculta interactiva no ensayada manualmente;
alternativa stdin privada si ensayada en contenedor. No se declara construida nueva
imagen ni desplegado el sistema, panel, solicitudes/correo, metricas o borrado.

## Cierre aprobado, 2026-10-30

Informe aceptado: C:/Users/PC/Documents/Codex/2026-10-30/servibox-platform-security/informe.md.
Rama feat/44-platform-security; HEAD inicial 643494a, main y origin/main iguales tras
fetch y PR 43 mergeado. Diff completo revisado, incluidos los 14 archivos nuevos.
Comparacion actual contra source-before.json: 232 archivos, cero diferencias.
JAR actual con el mismo SHA256 645A54D25E7DC529BABADE42EBF2F57F44CB5E9C8686ECB61D2A053F4E556ECF.
Sin cambios posteriores de codigo/configuracion; se reutilizan estas evidencias aceptadas:

* JUnit sobre H2: 534, 494 base y 40 nuevas, cero fallos, errores u omitidas.
* Empaquetado final: BUILD SUCCESS; no constituye otra ejecucion JUnit.
* Comprobaciones iniciales sobre H2: 45, incluidas 22 HTTP.
* Comprobaciones PostgreSQL 16.15: 180, incluidas 113 HTTP. Migracion V7 a V8:
  42 filas de 14 tablas comerciales conservadas, cantidades y valores sin diferencias.

Los tres conteos de pruebas se conservan separados. Las adaptaciones de 14 casos
existentes en seis archivos no se cuentan como nuevas: cuatro casos AuthLoginIntegrationTest
y cuatro RequestBodyErrorsTest cambian rutas sinteticas a inventory; tres casos validos
ProductionConfigurationTest agregan firma exclusiva a sus fixtures; SchemaConformanceTest
y FlywayMigrationTest esperan 15 tablas; contextLoads activa perfil test explicito.
Se conservan todas sus aserciones comerciales.

Bootstrap privado por stdin verificado en contenedor. Consola oculta interactiva sin
ensayo manual. JAR actual ejecutado en runtime de contenedor, sin construir imagen nueva.
Sin panel, solicitudes, correo, metricas o borrado. Suspension/reactivacion y revocacion
tenant pendientes de tarea 5. Sin Railway ni despliegue publico. La revision manual
Docker global sigue aplazada y obligatoria antes de publicar.

Publicacion limitada a las 33 rutas propias, sin frontend, AGENTS.md, Dockerfile,
migraciones V1 a V7 ni los dos .idea ajenos. Evidencia, logs y auxiliares externos no
se incorporan al commit. Revision del contenido sin secretos ni credenciales privadas;
solo valores ficticios dev/test. No hay lint backend configurado. Controles del diff de
trabajo y del stage correctos; sin merge, checkout ni procesos ajenos detenidos.

## Mensajes nuevos

Error HTTP nuevo para futura incorporacion al mapa frontend: `Acceso denegado` (403,
denegacion de cadena de seguridad). Se reutilizan Credenciales invalidas, No autenticado
y Peticion invalida. El 403 se escribe como JSON directamente para evitar que el dispatcher
de error lo transforme en 401 al pasar por /error. Mensajes internos de arranque/CLI:

* PLATFORM_JWT_SECRET es obligatorio en prod, requiere al menos 32 bytes, debe ser exclusivo y no de desarrollo o pruebas
* Configuracion JWT de plataforma invalida
* Email de plataforma invalido
* La clave de plataforma requiere al menos 12 caracteres y como maximo 72 bytes UTF-8
* El bootstrap de plataforma ya fue ejecutado
* Bootstrap requiere un unico selector, sin credenciales en argumentos
* Bootstrap requiere consola con entrada oculta o pipe privado explicito
* La entrada stdin de bootstrap requiere un pipe privado sin consola
* Bootstrap no admite un contexto HTTP
* Entrada privada demasiado larga
* Entrada privada incompleta
* Bootstrap de plataforma rechazado; revise configuracion, entrada y existencia de cuenta
* JWT de plataforma invalido
* JWT tenant invalido
* Ambito JWT tenant invalido

Los filtros descartan errores de token internamente, sin exponer mensajes o claims.
Solicitud, correo, metricas, borrado, panel y despliegue quedan fuera de esta entrega.
