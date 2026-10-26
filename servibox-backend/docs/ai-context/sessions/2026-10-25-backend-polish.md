# 2026-10-25: Backend polish

Rama fix/41-backend-polish, cierre autorizado con limitaciones expresas. Fecha America/Bogota.
Resuelta la decision sobre precios unitarios: precision significativa mayor a dos decimales
se rechaza con 400 antes de escribir, sin ampliar el esquema. PostgreSQL 16 sigue pendiente;
el fallo 500 observado con H2 sigue como incidencia por investigar, sin causa demostrada.
Ultima suite tras esta decision: 466 = 435 base + 31 nuevas, sin fallos ni omisiones.

## Git y lectura previa

Main, HEAD y origin/main en 190cd3c231a6e44db6de0778afc4592e400447a0 tras fetch.
PR backend 40 MERGED 2026-10-25T19:58:33Z; frontend 32 MERGED 20:37:56Z.
Rama inexistente local y remota antes de crearla. Cambios previos solo en
.idea/compiler.xml y .idea/servibox-backend.iml, intactos y fuera del alcance.
Raiz Maven y carpeta real de contexto: servibox-backend/ dentro de la raiz Git.
Frontend solo leido para mapa de errores y ordenes de consumidores, sin modificaciones.

## Orden por endpoint

| Endpoint | Orden final |
|-|-|
| GET /api/inventory/products | description ASC, id ASC |
| GET /api/inventory/categories | name ASC, id ASC |
| GET /api/inventory/tax-types | name ASC, id ASC |
| GET /api/counterparties con/sin role | name ASC, id ASC; conserva Consumidor final |
| GET /api/treasury/accounts | name ASC, id ASC |
| GET /api/sales y /api/purchases | invoiceDate DESC, id DESC; anuladas incluidas |
| Detalles de ventas y compras | id ASC, captura |
| GET /api/treasury/movements y extracto de cuenta | date DESC, id DESC, conservado |
| GET /api/reporting/movements | date DESC, sourceId DESC, conservado |
| Exportacion MOVIMIENTOS | date ASC, id ASC, conservado |
| GET /api/reporting/iva | IVA generado DESC, categoria ASC para empates |

Sin paginacion ni parametros nuevos. Filtro tenant conservado en consultas y transacciones.
Bloqueos y refresh intactos: documento, cuentas y productos, cada grupo por id ASC.

## V6: columnas y motivos

| Indice | Columnas, en orden | Motivo |
|-|-|-|
| ix_categoria_tenant_nombre | id_tenant, nombre_categoria_producto, id_categoria_producto | Catalogo ordenado y tenant |
| ix_impuesto_tenant_nombre | id_tenant, nombre_impuesto, id_impuesto | Catalogo y seleccion de impuestos |
| ix_producto_tenant_descripcion | id_tenant, descripcion_producto, id_producto | Listado ordenado |
| ix_tercero_tenant_nombre | id_tenant, nombre_razon_social, id_tercero | Listados con/sin rol |
| ix_producto_categoria | id_categoria_producto | Recalculo por categoria y FK |
| ix_categoria_impuesto_impuesto | id_impuesto | FK inversa, PK ya cubre categoria |
| ix_venta_tenant_fecha | id_tenant, fecha_venta, id_venta | Listado y rangos de reportes |
| ix_compra_tenant_fecha | id_tenant, fecha_compra, id_compra | Listado y rangos de reportes |
| ix_venta_tercero | id_tercero | FK no cubierta por UNIQUE de numero |
| ix_compra_tercero | id_tercero | FK inversa; UNIQUE inicia por tenant |
| ix_detalle_venta_documento | id_venta, id_tenant, id_detalle_venta | Lineas de documento y FK |
| ix_detalle_compra_documento | id_compra, id_tenant, id_detalle_compra | Lineas de documento y FK |
| ix_detalle_venta_producto | id_producto, id_tenant | Existencia de facturas y FK |
| ix_detalle_compra_producto | id_producto, id_tenant, id_compra, id_detalle_compra | Ultima compra por producto, existencia y FK |
| ix_movimiento_tenant_fecha | id_tenant, fecha_movimiento, id_movimiento | Libro, rangos y reportes |
| ix_movimiento_cuenta_fecha | id_cuenta, id_tenant, fecha_movimiento, id_movimiento | Extracto, saldoAl y FK |
| ix_movimiento_venta | id_venta, id_tenant, id_movimiento | Pendientes, anular/restaurar, FK |
| ix_movimiento_compra | id_compra, id_tenant, id_movimiento | Pendientes, anular/restaurar, FK |

PK y UNIQUE existentes cubren usuario/tenant, configuracion/tenant, cuenta/tenant/nombre,
producto/tenant/codigo, tercero/tenant/documento y numeros de documentos. No se duplican
esos prefijos ni se crean versiones ASC y DESC del mismo btree. Sin medicion de rendimiento.

## Precision aprobada y limites

Caso nuevo aprobado: dos lineas con base 0.03 al 19 por ciento producen base 0.06,
IVA 0.02 y total 0.08 tanto en ventas como en compras. Se multiplica precio por cantidad
antes del redondeo final; el IVA no se redondea por unidad. Moneda usa HALF_UP y
BigDecimal.valueOf para dinero. Montos y saldos se normalizan antes de persistir y
responder; agregados e identidades usan sumas/restas decimales. No se redondean tasas.
Sugerido e IVA del costo mantienen su regla de pesos enteros.

No hay migracion de datos ni recalculo historico. La prueba V5 a V6 conserva incluso
un total historico 0.07 con suma de IVA de lineas 0.02: no reinterpreta el documento.
Anular y restaurar usan importes y movimientos guardados; costo de venta congelado intacto.
La tolerancia existente de un peso sigue saldando pendientes inferiores a 1 y permitiendo
el exceso tolerado: conservarla es una regla de dominio, no garantiza deuda aritmetica
exacta en ese intervalo. Los gastos siguen pudiendo dejar saldo negativo.

Limitacion reportada: precio 0.005 por cantidad 2 da base 0.01 antes de guardar, pero
NUMERIC(14,2) guarda precio 0.01, que por 2 implica 0.02. No hay columna de base por linea.
Resuelta por decision posterior aprobada: rechazar 0.005 y cualquier precio unitario
positivo con mas de dos decimales significativos. El servicio valida todas las lineas al
entrar en crearFactura, dentro de la transaccion, antes de calcular o persistir cualquier
documento/linea, crear Consumidor final o modificar stock y saldos. No redondea precios.
DTO de entrada y records de linea usan BigDecimal, para conservar tambien precision que
Double perderia al deserializar, por ejemplo 0.0100000000000000000001. El criterio es
stripTrailingZeros().scale() > 2: 0.01 y 0.0100 son equivalentes y se aceptan. JSON sigue
siendo numerico; entidades, columnas y respuestas no cambian. Obligatoriedad y signo se
conservan, incluidos el mensaje de precio no positivo y Peticion invalida para null.
Pagos, abonos, transferencias y saldos mantienen su normalizacion aprobada; anular y
restaurar no pasan por esta validacion nueva ni reinterpretan importes historicos.

PDF muestra coma decimal y XLSX formato numerico #,##0.00 si hay centavos; enteros siguen
como antes. El frontend usa formatos y calculos propios que pueden ocultar centavos o
recalcular IVA sin redondear por linea; queda pendiente su revision en su propia tarea.

## Configuracion y comprobaciones

Perfil prod existente: spring.jpa.open-in-view=false. Activar SPRING_PROFILES_ACTIVE=prod,
con DB_URL, DB_USERNAME, DB_PASSWORD y JWT_SECRET existentes. Sin credenciales inventadas.
Pruebas tambien false; HTTP dev usa configuracion temporal false. No se agregan EAGER:
las relaciones de negocio ya lo eran y tenant LAZY no se expone. Exportaciones usan
servicios transaccionales y proyecciones acotadas.

## Pruebas y empaquetado

Verificacion posterior a la decision de rechazar precios con precision excesiva:

* Pruebas afectadas: BackendPolishTest, SalesValidationTest, PurchasesValidationTest,
  SalesInvoicingTest, PurchasesFlowTest, SalesRestoreTest y PurchasesRestoreTest:
  73 pruebas, 0 fallos/errores/omitidas, BUILD SUCCESS, 1:22 min, 18:50:58 -05:00.
* Suite completa posterior: 466 = 435 base + 17 nuevas anteriores + 14 de esta decision,
  0 fallos, 0 errores, 0 omitidas. BUILD SUCCESS, 3:35 min, 18:56:16 -05:00, tras limpiar
  solo target/surefire-reports. No se ejecuto la suite base de main.
  Los 58 XML regenerados suman 466, sin fallos/errores/omitidas.
* Empaquetado final posterior: mvnw -DskipTests package, BUILD SUCCESS, salida 0,
  11.497 s, 18:57:39 -05:00. git diff --check correcto.
* Las 14 nuevas ejecuciones de BackendPolishTest son parametrizadas: 4 rechazos (venta y
  compra con 0.005 y 0.0100000000000000000001), 4 aceptaciones (0.01 y 0.0100 en ambas),
  y 6 regresiones de signo/obligatoriedad (0, -0.005 y null en ambas). La clase suma 26.
  Se comprueban status/error exactos, ausencia de documentos/lineas/movimientos/terceros
  parciales, producto sin cambios de stock/costo/precios y saldo intacto. La linea invalida
  va despues de una valida en el request. Los aceptados verifican SQL, POST, GET,
  precio 0.01 por cantidad 3, base 0.03, IVA 0.01, total 0.04 y stock/saldo correspondientes.
* Se conservan las pruebas anteriores del ejemplo 0.03 + 0.03 = 0.08 y de documentos
  historicos al anular/restaurar. No se ajustaron expectativas anteriores para aceptar
  este cambio, ni se elimino ninguna prueba. Las seis pruebas de mensajes ajustadas
  anteriormente se enumeran abajo; siguen pasando.
* HTTP final en backend dev temporal, open-in-view=false: venta y compra rechazan 0.005
  y 0.0100000000000000000001 con el 400 y texto aprobados, sin documento, movimientos ni
  cambios de stock/costo/saldo. Ambas aceptan los literales JSON 0.01 y 0.0100 por cantidad
  3, precio guardado 0.01, IVA 0.01 y total 0.04; GET igual al POST. Dos lineas de 0.03
  dan 0.08 y dos de 5.67 dan 13.50. Tras compras y ventas equivalentes, stock 100 y saldo
  100.00, esperados y obtenidos. Una compra adicional con saldo 0.02 y dos lineas de 0.01
  fue 201, total 0.02, saldo final 0.00: confirma el caso bloqueado por el frontend.
  Ningun ERROR ni LazyInitializationException en esta ejecucion breve; no reproduce ni
  resuelve la incidencia H2 anterior.

Evidencia anterior, antes de esta decision adicional:

Suite completa tras eliminar solo target/surefire-reports desde Maven, con ruta absoluta
comprobada y eliminacion no recursiva: 452 = 435 base + 17 nuevas, 0 fallos, 0 errores,
0 omitidas. 58 XML regenerados suman 452. BUILD SUCCESS, 3:02 min, 17:52:15 -05:00.
No se ejecuto la suite base de main ni se creo una copia para contarla.
Empaquetado con mvnw -DskipTests package: BUILD SUCCESS, 8.056 s, salida Maven 0,
17:53:24 -05:00. git diff --check sin incidencias.

* BackendPolishTest: 12 nuevas. Ejemplo aprobado en venta y compra, cantidad antes del
  redondeo, pago/abono con mitades y pendientes, ciclos repetidos de anular/restaurar,
  manuales negativos y edicion, transferencia, saldo global sin residuos, API/KPIs/PDF/
  XLSX con centavos, endpoints sin OSIV y aislamiento, catalogos con empates, documentos
  con anulados y desempates, orden de movimientos por uso, historico sin recalculo.
* V6IndexesTest: 2 nuevas. Desde cero y actualizacion V5 a V6, columnas de los 18 indices,
  14 tablas, ausencia de prefijos redundantes de unicas, migracion idempotente y valores
  historicos sin modificar. Las mismas 2 tambien pasan en PostgreSQL real temporal.
* MonedaTest: 2 nuevas, HALF_UP positivo/negativo, limites, ruido binario y sumas/restas.
* ConcurrentOperationsTest: 1 nueva, 20 ingresos simultaneos de 0.105 se guardan como
  0.11; inicial 0.10, saldo final exacto 2.30. Las 15 pruebas de la clase pasan.
* Siembra: DevDemoDataTest (7) y DevDemoDataFirstDayTest (1) pasan sin ajustes de cifras.

Existentes ajustadas solo por los cuatro mensajes compatibles:
TenantRegistrationTest.nitInvalidoEs400, telefonoInvalidoEs400, slugInvalidoEs400 y
usernameInvalidoEs400; TenantProfileTest.lasReglasDelNitSonLasDelRegistro y
lasReglasDelTelefonoSonLasDelRegistro. Ninguna expectativa monetaria previa se cambio.
El perfil test desactiva OSIV para toda la suite. Sin bajas ni dependencias nuevas.

## PostgreSQL real y herramientas

Docker instalado, servicio detenido y sin daemon. PostgreSQL instalado es 18.2, no 16.
Se uso initdb/pg_ctl de esa instalacion para un cluster nuevo en un directorio temporal
propio, solo 127.0.0.1:18541; no se accedio al servicio existente de 5432 ni a datos reales.
Flyway directo, el mismo mecanismo del proyecto y las pruebas, migro desde cero V1 a V6
y actualizo V5 a V6 con documentos existentes. Ambas pruebas pasan, indices y columnas
comprobados con DatabaseMetaData; validate e idempotencia tambien pasan.

Ademas, el JAR empaquetado arranco con perfil prod, sin dev, contra ese cluster: V1 a V6
en public y Hibernate ddl-auto=validate correctos. Se registraron tenants nuevos y se
repitieron 31 comprobaciones HTTP de la secuencia decimal, anulaciones, aislamiento y
exportaciones, todas correctas con OSIV false del propio perfil prod. Sin excepciones
lazy ni errores en esa ejecucion. Esto verifica PostgreSQL 18.2, no sustituye la prueba
pendiente de PostgreSQL 16. No se instalaron herramientas ni dependencias.
La validacion de entrada de precios agregada despues se comprobo con las suites indicadas
arriba; no se volvio a ejecutar PostgreSQL 18.2 por ese cambio. PostgreSQL 16 no se ha
verificado y permanece explicitamente pendiente, sin equivalencia entre versiones.

## HTTP real, demo antes y despues

Antes: JAR existente, empaquetado el 2026-10-25 14:53:37 -05:00, anterior a estos cambios,
perfil dev y puerto temporal 18441. Despues: comando mvnw spring-boot:run con perfil dev,
puerto 18442 y spring.jpa.open-in-view=false temporal. La captura previa decodificada
con PowerShell da global 28178315.55; no se afirma haber capturado el literal crudo del
residuo que registraba la sesion anterior. El test nuevo construye ese escenario y
comprueba decimalmente el JSON emitido, tambien cuando usa notacion cientifica equivalente.

43 comprobaciones numericas HTTP pasan; listados de catalogos/documentos/movimientos
ordenados, detalles, extractos, inventario y exportaciones con 200. Otro tenant recibe
listas vacias y 404 para documento, producto y cuenta ajenos. No hubo LazyInitializationException.
El demo sigue con 27 productos, 45 ventas, 9 compras y 77 movimientos. No se modifico
DevDataInitializer ni se alteraron cifras para ocultar diferencias.

| Cifra del demo | Antes | Despues |
|-|-|-|
| Ventas con IVA | 76209943.11 | 76209943.11 |
| Compras con IVA | 32210920 | 32210920 |
| Ventas sin IVA | 64041969 | 64041969 |
| Costo de lo vendido | 43263000 | 43263000 |
| Utilidad bruta | 20778969 | 20778969 |
| Gastos operativos | 19708000 | 19708000 |
| Otros ingresos | 310000 | 310000 |
| Utilidad neta | 1380969 | 1380969 |
| Bancolombia | 16963933.02 | 16963933.02 |
| Caja General | 4406852.45 | 4406852.45 |
| Nequi | 6807530.08 | 6807530.08 |
| Suma decimal/global | 28178315.55 | 28178315.55 |

## Incidencia pendiente: HTTP 500 al registrar pago en dev/H2

Estado: pendiente de investigar. No hay reproduccion determinista ni causa raiz demostrada;
no se atribuye definitivamente a inactividad, Hikari, configuracion ni duracion. Tampoco
se determino si existia antes de esta rama. No fue LazyInitializationException.

Contexto y pasos observados, utiles para intentar reproducir:

1. Arrancar dev/H2 2.4.240 con open-in-view=false, puerto temporal 18442; inicio registrado
   2026-10-25T17:54:41.921-05:00, PID propio 284108. Demo sembrado, lecturas HTTP correctas.
2. Hubo unos 29 minutos entre el inicio y el fallo. Se observo un intervalo sin peticiones
   registradas; esto solo describe la cronologia, no demuestra que la inactividad lo cause.
3. Registrar tenant nuevo, producto con stock 100 y costo 0.01, proveedor y cuenta con
   inicial solicitado 100.105, guardado 100.11. Crear compra CREDITO, una linea de 10.03,
   total 11.94. La creacion fue correcta.
4. POST /api/purchases/10/payments con amount 2.335 y accountId de la cuenta creada en el
   escenario original: HTTP 500 a las 18:24:01. El id exacto de cuenta no quedo copiado en
   la traza conservada; usar el id creado en cada intento, sin asumir que se repite.
5. Reiniciar solo la instancia temporal y repetir toda la secuencia dio resultados
   correctos. Tambien paso en prod/PostgreSQL temporal. Esas repeticiones no prueban que
   la incidencia este resuelta.

Evidencia conservada en esta nota y en las salidas de herramientas de la conversacion:

```text
2026-10-25T18:24:01.928-05:00 ErrorCode: 23514, SQLState: 23514
Check constraint invalid: "CK_MOVIMIENTO_FACTURA: "
insert into movimientos (...) values (...) [23514-240]
Caused by: org.h2.message.DbException: The database has been closed [90098-240]
org.h2.engine.SessionLocal.getDatabase(SessionLocal.java:674)
org.h2.engine.SessionLocal.getCompareMode(SessionLocal.java:2137)
org.h2.engine.SessionLocal.compare(SessionLocal.java:2001)
java.util.TreeMap.getEntryUsingComparator(TreeMap.java:409)
java.util.TreeSet.contains(TreeSet.java:238)
org.h2.expression.condition.ConditionInConstantSet.getValue(ConditionInConstantSet.java:80)
org.h2.constraint.ConstraintCheck.checkRow(ConstraintCheck.java:99)
```

Los logs originales after.log y el cluster H2 en memoria ya no estan disponibles: se
eliminaron los temporales propios al terminar la comprobacion anterior, como se pidio.
No se cambiaron H2, Hikari ni CK_MOVIMIENTO_FACTURA para ocultar el fallo. Pendiente una
captura completa y una reproduccion controlada que permita determinar su causa.

## Tenant nuevo: esperado frente a obtenido

Los mismos resultados pasan en HTTP dev/H2 y prod/PostgreSQL. Cuenta origen inicial
100.105 se normaliza a 100.11; destino creado en 0.00. Fechas del mismo dia de negocio.

| Paso | Esperado | Obtenido |
|-|-|-|
| Saldo inicial | 100.11 | 100.11 |
| Compra credito, base 10.03 + IVA 1.91 | Total 11.94, saldo 100.11 | 11.94, 100.11 |
| Pago solicitado 2.335 | Monto 2.34, saldo 97.77, pendiente 9.60 | 2.34, 97.77, 9.60 |
| Venta credito, base 20.03 + IVA 3.81 | Total 23.84, saldo 97.77 | 23.84, 97.77 |
| Abono solicitado 2.345 | Monto 2.35, saldo 100.12, pendiente 21.49 | 2.35, 100.12, 21.49 |
| Transferencia solicitada 0.105 | Monto 0.11, origen 100.01, destino 0.11 | 0.11, 100.01, 0.11 |
| Saldo global | 100.12 | 100.12 |
| Anular venta | Origen 97.66, global 97.77 | 97.66, 97.77 |
| Restaurar venta | Origen 100.01, global 100.12 | 100.01, 100.12 |
| Anular compra | Origen 102.35, global 102.46 | 102.35, 102.46 |
| Restaurar compra | Origen 100.01, global 100.12 | 100.01, 100.12 |
| Ejemplo aprobado en venta y compra | Base 0.06, IVA 0.02, total 0.08 | 0.06, 0.02, 0.08 |

Consulta SQL directa al cluster temporal confirma saldo_actual e inicial mas movimientos
activos: origen 100.01, destino 0.11 y cuenta del otro tenant 0.00, sin diferencias.
Persistencia por SQL en pruebas, respuesta inmediata y GET posterior comprobados.

## API, PDF y XLSX

Rango demo 2026-08-01 a 2026-10-25; rango de tenants nuevos 2026-10-25 al mismo dia.
12 pares PDF/XLSX descargados: estado de resultados y movimientos del demo, por sus tres
cuentas, del ejemplo y de la secuencia. 221 celdas monetarias numericas contrastadas con
los importes del PDF; formato de centavos comprobado, sin redondear dinero al peso.
PDFBox extrae texto y renderiza las tres paginas del demo y ejemplos para inspeccion:
sin cortes, superposiciones ni perdida de centavos. Se uso la dependencia existente de
pruebas, sin Poppler disponible. POI lee los XLSX numericos y sus estilos.

| Reporte | Esperado API/modelo | Obtenido PDF y XLSX |
|-|-|-|
| Demo, utilidad neta | 1380969 | 1380969 |
| Demo movimientos, ingresos | 62181715.55 | 62181715.55 |
| Demo movimientos, egresos | -52103400 | -52103400 |
| Demo movimientos, neto | 10078315.55 | 10078315.55 |
| Secuencia, apertura | 100.11 | 100.11 |
| Secuencia, ingresos | 2.35 | 2.35 |
| Secuencia, egresos | -2.45 | -2.45 |
| Secuencia, neto | -0.10 | -0.10 |
| Secuencia, cierre | 100.01 | 100.01 |
| Ejemplo, ventas sin IVA | 0.06 | 0.06 |
| Ejemplo, costo de lo vendido | -0.02 | -0.02 |
| Ejemplo, utilidad neta | 0.04 | 0.04 |

No se entregan exportados ni scripts temporales como artefactos del repo. La entrada con
precios de mas de dos decimales ya queda resuelta mediante rechazo; las dependencias de
presentacion y calculo del frontend se concretan abajo y quedan para su propia tarea.

## Frontend pendiente: pantallas, calculos y ejemplos reproducibles

Solo lectura del frontend. Las funciones puras originales se comprobaron transpilando
en memoria con su TypeScript ya instalado, sin escribir archivos en ese repo, ejecutar
build/tests ni iniciar navegador. Los resultados siguientes proceden de esas funciones
y de los componentes que las usan, no de una prueba visual de las pantallas.

### Totales de las pantallas Nueva factura de venta y Nueva factura de compra

Fuentes frontend: src/sales/newSale.ts, invoiceTotals (lineas 62 a 73), y
src/purchases/newPurchase.ts, purchaseTotals (40 a 49). Sus consumidores son
src/pages/NewSaleInvoicePage.tsx:141 y src/pages/NewPurchaseInvoicePage.tsx:134.
Todavia acumulan el IVA sin redondear por linea y redondean al final; lineAmounts ya
redondea el IVA de cada renglon y por eso el pie puede no sumar sus propias lineas.

Reproduccion: elegir un producto con IVA 19 por ciento y stock suficiente; agregar dos
lineas separadas del mismo producto, cada una con cantidad 1 y precio 0.03. No sustituir
por una sola linea de cantidad 2, porque cambia la unidad de redondeo.

| Cifra | Backend aprobado | Funcion frontend actual |
|-|-|-|
| Base | 0.06 | 0.06 |
| IVA de cada linea | 0.01 y 0.01 | 0.01 y 0.01, lineAmounts |
| IVA del resumen | 0.02 | 0.01 |
| Total | 0.08 | 0.07 |

Ejemplo visible aun con el formato entero actual: dos lineas separadas de cantidad 1,
precio 5.67 e IVA 19 por ciento. Backend: base 11.34, IVA 1.08 + 1.08 = 2.16, total
13.50. Frontend: IVA agregado 2.1546, total 13.49. formatCurrency muestra 13.49 como
$ 13 en la vista previa, pero el total confirmado 13.50 como $ 14 en el detalle. El
resumen de compra tambien anticipa un saldo descontando 13.49 en lugar de 13.50.

### Nueva compra puede bloquear fondos suficientes

src/pages/NewPurchaseInvoicePage.tsx:141 compara currentBalance con totals.exactTotal;
handleSubmit (lineas 208 a 215) retorna si shortBalance. insufficientBalance, de
src/purchases/newPurchase.ts:52, solo compara balance < total.

Reproduccion: cuenta con saldo guardado 0.02; compra CONTADO con dos lineas separadas,
cantidad 1, precio 0.01, IVA 19 por ciento. Cada IVA definitivo es 0.00 y el backend
exige 0.02, por lo que alcanza. purchaseTotals da total 0.02, pero exactTotal 0.0238;
shortBalance resulta true y el formulario impide enviar una compra que el backend acepta.
No basta corregir el total visible: esta comparacion tambien debe usar el definitivo.

### Validacion de precio y mapa del mensaje nuevo

validateSale en src/sales/newSale.ts:129 y validatePurchase en
src/purchases/newPurchase.ts:105 solo verifican numero finito mayor que cero. Los inputs
de precio usan step="any" en ambas pantallas nuevas; los builders convierten con Number
y envian 0.005 sin rechazarlo. Las funciones actuales consideran validas esas lineas;
el nuevo backend responde 400 sin guardar nada. Pendiente validar los decimales
significativos tambien en el frontend y agregar al mapa el mensaje exacto listado abajo.

### Centavos ocultos despues de guardar, en reportes y tesoreria

src/lib/format.ts:7, formatCurrency, usa Math.round y cero decimales. Afecta los
resumenes/lineas de SaleDetailPage y PurchaseDetailPage, las listas SalesInvoicesPage y
PurchaseInvoicesPage (invoiceColumns y PendingBalance), tarjetas del Dashboard, vista
previa de ReportsPage, saldos y movimientos de AccountsPage y saldos de terceros.
No es un nuevo recalculo de IVA en todas esas pantallas: muchas usan correctamente los
importes de la API, pero ocultan los centavos que ahora muestran PDF y XLSX.

Reproducciones comprobadas: formatCurrency(0.08) da $ 0; 100.11 y 100.01 se muestran
ambos como $ 100; -0.10 da $ 0 y pierde el signo visible. En la secuencia de esta nota,
API/PDF/XLSX cierran en 100.01 y la pantalla de cuenta muestra $ 100. ReportsPage:347
tambien usa Math.round(row.value) < 0 para el color, de modo que una perdida de -0.10
no aparece como negativa. Revisar formatos monetarios con centavos y la clasificacion
por signo sin alterar los campos que conservan su regla de pesos enteros.

No hay evidencia de que haya que recalcular el IVA de documentos historicos en el
frontend: saleSummary usa subtotal y total de la API (IVA = total - subtotal), y las
columnas de detalle usan ivaAmount guardado. El ajuste de formula corresponde a las
facturas nuevas; la presentacion con centavos corresponde tambien a los importes leidos.

## Mensajes

Nuevo mensaje para src/api/errorMessages.ts del frontend, HTTP 400, formato {error,status}:

`El precio unitario admite como maximo dos decimales`

Aplica a POST /api/sales y POST /api/purchases. Agregar traduccion con tilde en maximo
en la tarea frontend. No se agrega un codigo de error ni se modifica su mapa en este repo.

Cuatro sufijos corregidos; los prefijos del mapa frontend permanecen byte por byte.
En esta lista los escapes Unicode representan las tildes exactas del codigo, manteniendo
esta documentacion ASCII. Los estados HTTP y formato {error,status} no cambian.

* El identificador debe tener de 3 a 30 caracteres con solo min\u00fasculas, n\u00fameros y guiones
* El usuario debe tener de 3 a 50 caracteres con letras, n\u00fameros, punto, guion y guion bajo
* El NIT solo admite digitos y un guion con el d\u00edgito de verificaci\u00f3n, por ejemplo 900123456-7
* El telefono solo admite digitos, espacios, +, - y par\u00e9ntesis

Pruebas existentes ajustadas solo por esos textos: TenantRegistrationTest (formato de
slug, usuario, NIT y telefono) y TenantProfileTest (formato de NIT y telefono).
ConcurrentOperationsTest y MonedaTest solo agregan casos; ninguna baja de pruebas.

Pendientes por dependencia del mapa: los siguientes patrones exactos de errorMessages.ts
contienen texto literal que perderia su traduccion al corregir tildes. Variables y grupos
son los del mapa, no codigos nuevos. Los cuatro mensajes de arriba tambien conservan sus
prefijos sin tildes por esta razon.

```text
^Debe aceptar los terminos y condiciones$
^El NIT solo admite digitos
^El telefono solo admite digitos
^El correo del administrador no es valido$
^El identificador (\S+) esta reservado$
^La contrasena debe tener al menos (\d+) caracteres$
^La contrasena no puede superar (\d+) caracteres$
^El telefono es obligatorio$
^(El nombre comercial|El NIT|El telefono|El nombre completo del administrador) no puede superar (\d+) caracteres$
^El registro de nuevas servitecas esta deshabilitado$
^Credenciales invalidas$
^Solo un administrador puede editar la configuracion financiera$
^El correo no tiene un formato valido$
^El nombre o razon social admite hasta (\d+) caracteres$
^El numero de documento admite hasta (\d+) caracteres$
^El tercero necesita un nombre o razon social$
^El tercero necesita un numero de documento$
^Ya existe una categoria con el nombre (.+)$
^Ya existe un producto con el codigo (.+)$
^El codigo del producto admite hasta (\d+) caracteres$
^El nombre de la categoria admite hasta (\d+) caracteres$
^La descripcion del producto admite hasta (\d+) caracteres$
^El codigo (.+) no se puede cambiar: el producto ya tiene facturas de venta o de compra$
^El tenant ya tiene una configuracion financiera
^Ya existe una factura con el numero (\S+)$
^La factura (\S+) ya esta anulada$
^La factura necesita un numero$
^Solo se puede abonar a una factura PENDIENTE, y esta esta (\w+)$
^Solo se puede restaurar una factura ANULADA, y la (\S+) esta (\w+)$
^Una factura necesita al menos una linea$
^Una factura a credito necesita fecha de vencimiento$
^La compra (\S+) ya esta anulada$
^La factura de compra necesita un numero$
^No se puede anular la compra (\S+): de (.+) quedan (-?\d+) unidades y habria que quitar (\d+)\. Parte de ese stock ya se vendio\.$
^Solo se puede pagar una compra PENDIENTE, y esta esta (\w+)$
^Solo se puede restaurar una compra ANULADA, y la (\S+) esta (\w+)$
^Una factura de compra necesita al menos una linea$
^El movimiento \d+ es de categoria \w+ y se gestiona desde su operacion$
^La categoria de un movimiento no se puede cambiar
^El rango de un reporte no puede pasar de (\d+) dias$
^El reporte tiene mas de (\d+) movimientos: acota el rango de fechas$
^(Categoria|Compra|Cuenta|Factura|Movimiento|Producto|Tercero) no encontrad[ao]: \d+$
^El recurso esta siendo modificado por otra operacion
```

Ademas, Peticion invalida y los mensajes Valor invalido con campo/parametro no tienen
regla especifica: el frontend reconoce palabras sin tildes para descartar texto tecnico.
Cambiar esa clasificacion exige revisar tambien UNREADABLE_WORDS/readable en su tarea.
No se modifican esos mensajes ni el rechazo de rol de categorias, que tampoco tiene regla
especifica equivalente al de configuracion financiera.

## Archivos de la tarea

Modificados, todos dentro de servibox-backend/:

```text
docs/ai-context/01-ARCHITECTURE.md
docs/ai-context/02-CONVENTIONS.md
docs/ai-context/03-DECISIONS.md
docs/ai-context/04-DATA-MODEL.md
src/main/java/com/servibox/backend/auth/TenantRegistrationService.java
src/main/java/com/servibox/backend/counterparties/repository/CounterpartyRepository.java
src/main/java/com/servibox/backend/counterparties/service/CounterpartyService.java
src/main/java/com/servibox/backend/inventory/repository/TaxTypeRepository.java
src/main/java/com/servibox/backend/inventory/service/InventoryService.java
src/main/java/com/servibox/backend/purchases/repository/PurchaseDetailRepository.java
src/main/java/com/servibox/backend/purchases/dto/PurchaseDetailRequest.java
src/main/java/com/servibox/backend/purchases/service/PurchasesService.java
src/main/java/com/servibox/backend/reporting/dto/KpisResponse.java
src/main/java/com/servibox/backend/reporting/export/ExcelReportGenerator.java
src/main/java/com/servibox/backend/reporting/export/PdfReportGenerator.java
src/main/java/com/servibox/backend/reporting/service/ReportExportService.java
src/main/java/com/servibox/backend/reporting/service/ReportingService.java
src/main/java/com/servibox/backend/sales/repository/SaleDetailRepository.java
src/main/java/com/servibox/backend/sales/dto/SaleDetailRequest.java
src/main/java/com/servibox/backend/sales/service/SalesService.java
src/main/java/com/servibox/backend/shared/Moneda.java
src/main/java/com/servibox/backend/treasury/service/TreasuryService.java
src/main/resources/application-prod.properties
src/test/java/com/servibox/backend/auth/TenantRegistrationTest.java
src/test/java/com/servibox/backend/concurrency/ConcurrentOperationsTest.java
src/test/java/com/servibox/backend/shared/MonedaTest.java
src/test/java/com/servibox/backend/tenant/TenantProfileTest.java
src/test/resources/application-test.properties
```

Nuevos:

```text
docs/ai-context/sessions/2026-10-25-backend-polish.md
src/main/resources/db/migration/V6__listing_and_foreign_key_indexes.sql
src/test/java/com/servibox/backend/polish/BackendPolishTest.java
src/test/java/com/servibox/backend/schema/V6IndexesTest.java
```

Sin cambios a AGENTS.md, frontend, seguridad, roles, registro funcional, dependencias,
siembra, historial contable ni migraciones V1 a V5. Los archivos .idea previos quedan
intactos. Procesos propios detenidos: backend previo 282936, dev 284108 y 284596,
prod 283784 y captura final 288532. Cluster PostgreSQL temporal detenido con pg_ctl
usando solo su PGDATA propio. Directorio temporal exclusivo eliminado, ruta absoluta
verificada; Test-Path false y ultimo backend inexistente comprobados. No se iniciaron
Vite ni Edge. Sin credenciales, scripts, descargas ni capturas agregados a Git.

Continuacion de validacion de precios: backend dev propio 286920 detenido; carpeta
temporal exclusiva servibox-polish-followup-1da4bf665bb84c38ae73a06c3f376271 eliminada
tras verificar su ruta absoluta. Test-Path false y backend inexistente comprobados.
Git conserva la rama fix/41-backend-polish, 28 archivos existentes modificados y 4 nuevos
de la tarea, mas los 2 archivos .idea previos intactos; nada staged, sin commit ni push.

## Verificacion de cierre autorizada

2026-10-25, America/Bogota. Rama confirmada fix/41-backend-polish. Sin procesos pesados
propios del proyecto activos antes de Maven; no se detuvo ningun proceso ajeno.
Se elimino unicamente target/surefire-reports dentro de la carpeta Maven, con ruta
absoluta comprobada y sin borrar otros artefactos de target.

* mvnw test completo: BUILD SUCCESS, 466 = 435 base + 31 nuevas, 0 fallos, 0 errores,
  0 omitidas. Los 58 XML regenerados suman 466. Duracion 3:36 min, final 19:09:53 -05:00.
* mvnw -DskipTests package posterior: BUILD SUCCESS, salida 0, 9.240 s,
  final 19:10:29 -05:00. Sin reintentos por carga ni fallas reales.
* Revision de status, diff y nuevos: 32 archivos de tarea, 28 modificados y 4 nuevos.
  Sin credenciales reales, bases, logs, scripts, exportados ni mediciones en esos archivos.
  .idea/compiler.xml y .idea/servibox-backend.iml conservan sus hashes y se excluyen.
  Frontend con git status limpio; V1 a V5 y AGENTS.md intactos.
* Se autoriza commit/push/PR solo con esas rutas, mensaje y titulo fix/41-backend-polish,
  base main, sin merge ni cambio de rama. Los estados sin commit/push descritos arriba
  corresponden a las etapas previas; hash y URL finales se reportan fuera de esta nota.

Se mantienen PostgreSQL 16 pendiente, el 500 H2 sin causa demostrada y la compatibilidad
frontend pendiente antes de desplegar, ademas de tolerancia de pendientes y gastos que
pueden dejar saldos negativos. El cuerpo del PR es el texto aprobado por el usuario.
