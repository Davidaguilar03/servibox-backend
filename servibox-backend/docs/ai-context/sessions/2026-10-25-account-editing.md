# 2026-10-25: Edicion de cuentas

Se puede continuar con la tarea 13. Rama `feat/40-account-editing`, sin commit ni push.
Fecha America/Bogota. La garantia concurrente conserva gastos que permiten saldo negativo.

## Git y hallazgos previos

* `git fetch origin`: main, HEAD y origin/main en
  `2a91f1cf77dbc114ce0f08bd2de1f758f6c18c59`. PR 39 `chore/agents-md` MERGED,
  2026-10-25T19:29:35Z. Rama nueva creada; no existia local ni remotamente.
* Cambios previos unicamente en `.idea/compiler.xml` y `.idea/servibox-backend.iml`;
  intactos, fuera del alcance. No se leyo ni modifico el frontend.
* Raiz Git contiene AGENTS.md; Maven, fuentes y contexto viven un nivel dentro,
  en `servibox-backend/`.
* Alta: `AccountRequest` exige nombre no blanco y tipo EFECTIVO, BANCO o DIGITAL.
  Saldo inicial omitido o null se vuelve 0; acepta negativos. Nombre VARCHAR(100),
  sin validacion de largo en el DTO del alta. Sin recorte ni normalizacion.
  Unicidad exacta por tenant, consulta y restriccion `uk_cuenta_tenant_name`.
  Propiedades desconocidas y `currentBalance` se ignoran, comprobado tambien por HTTP.
* Cualquier rol autenticado puede crear cuentas; no hay estado de cuenta desactivada.
  Compras y transferencias validan saldo actual, no historico. Gastos operativos no
  exigen saldo suficiente. La contradiccion inicial con una garantia de ausencia global
  de negativos se reporto y el usuario confirmo conservar esta regla.
* Bloqueos actuales: documento primero, cuentas despues y productos al final, cada grupo
  por id ascendente. Cuentas con tenant explicito, PESSIMISTIC_WRITE y refresh.
  PDF/XLSX calculan apertura con inicial vigente mas movimientos anteriores al periodo.

## Contrato final

`PUT /api/treasury/accounts/{id}`, JWT, cualquier rol autenticado como el alta.

```json
{"name":"Banco renombrado","initialBalance":70000,"type":"BANCO"}
```

* `name` obligatorio, no blanco, hasta 100 caracteres, mismo limite de la columna
  del alta. En PUT se rechaza el exceso con 400 antes de escribir. No se modifica el alta.
  Nombre exacto y sin recorte; duplicado del tenant 409, excluyendo la propia cuenta.
* `initialBalance` obligatorio y >= 0. Para editar solo nombre se envia el inicial actual.
* `type` opcional o null: sin cambio. Igual al actual se acepta; diferente, 400.
* `currentBalance` y campos desconocidos se ignoran. Saldo actual no editable directamente.
* 200 con `AccountResponse`, por ejemplo en el escenario HTTP:

```json
{"id":6,"name":"Banco renombrado","initialBalance":70000.0,"currentBalance":10000.0,"type":"BANCO"}
```

* Una transaccion, `bloquearCuentas` antes de leer, validar y escribir; filtro explicito
  por tenant y refresh. Inexistente o ajena, 404.
* Saldo nuevo = saldo anterior + (inicial nuevo - inicial anterior). Si cambia el inicial
  y el resultado es negativo, `InsufficientBalanceException`, 409 con faltante.
  Si solo cambia el nombre, no revalida saldos, incluso con saldo actual negativo.
* No crea movimientos ni modifica KPIs. Sin validacion historica nueva.
  La consulta de unicidad se hace antes de mutar la entidad para evitar un flush prematuro.
  El helper privado de unicidad recibe id y nombre; la logica del alta no cambia.

## Pruebas

* Base informada: 409. No se ejecuto la suite de main ni se creo una copia.
* Suite de la rama con `./mvnw test`: 435 = 409 + 26 nuevas, 0 fallos,
  0 errores, 0 omitidas. Primera ejecucion: BUILD SUCCESS, 3:15 min.
* `AccountEditingTest`: 24 ejecuciones nuevas, incluyendo casos parametrizados.
  Nombre, duplicado sin persistir cambios, otro tenant, comparacion exacta, blancos/null,
  limites 100/101, aumento y reduccion exactos, resultado cero, rechazo por faltante,
  nombre con saldo negativo, saldo ausente/null/negativo, tipo, campos desconocidos,
  inexistente/ajena, 401 y ADMIN/MECANICO/CAJERO con los mismos permisos que el alta.
* `ConcurrentOperationsTest`: 1 nueva prueba, 10 rondas de dos solicitudes simultaneas.
  Inicial 100, nuevo 70, gasto 80: solo se aceptan edicion 200 y saldo -10, o edicion
  409 por faltante 10 y saldo 20. Gasto siempre 201; invariante comprobada en cada ronda.
  Sin perdida de actualizaciones ni interbloqueo. Las 14 pruebas de la clase pasan.
* `ReportExportTest`: 1 nueva prueba con PDF y XLSX, movimiento previo y posterior
  al rango, y KPIs iguales antes/despues del cambio de inicial. Inicial 1000000 pasa
  a 1100000; apertura septiembre 1300000, ingresos 269000, egresos -189500,
  neto 79500, cierre septiembre 1379500, saldo actual 1369500 por gasto posterior.
  Las 16 pruebas de la clase pasan.
* `DevDemoDataTest`: 7 pruebas pasan; el resto de la suite, incluidas siembra y esquema,
  sin regresiones. No se ajusto ni elimino ninguna prueba existente: solo se agrego
  un caso a ConcurrentOperationsTest y otro a ReportExportTest para cubrir el PUT.
* La eliminacion recursiva de target/surefire-reports fue rechazada por revision
  automatica. Se completo de forma mas segura: ruta absoluta verificada, sin subcarpetas,
  eliminacion no recursiva de sus archivos y luego de la carpeta vacia (Test-Path false).
  Segunda ejecucion completa tras esa limpieza: BUILD SUCCESS, 3:09 min,
  435 pruebas, 0 fallos, 0 errores y 0 omitidas, final 2026-10-25 14:46:34 -05:00.
  Los 56 XML regenerados suman 435 y corresponden a esta ejecucion.

## HTTP real, tenant nuevo

Perfil dev con el comando solicitado, puerto 8080 libre. Compra fechada 2026-10-24,
producto sin IVA para total exacto 60000. Dos tenants nuevos registrados por HTTP.

| Paso | Estado | Esperado | Obtenido |
|-|-|-|-|
| Alta BANCO inicial 100000, con currentBalance inyectado | 201 | Actual 100000 | 100000 |
| Compra de contado 60000 | 201 | Actual 40000 | 40000 |
| PUT inicial 50000 | 409 | Faltan 10000; no guardar | Faltan 10000; inicial 100000, actual 40000 y nombre intactos |
| PUT inicial 70000 | 200 | Actual 10000 | 10000 |
| Nombre duplicado, inicial 70000 | 409 | No guardar | Inicial 70000, actual 10000 y nombre intactos |
| Nombre nuevo, inicial 70000 | 200 | Actual 10000 | 10000 |
| Tipo EFECTIVO | 400 | Rechazo | El tipo de una cuenta no se puede cambiar |
| Tipo BANCO | 200 | Actual 10000 | 10000 |
| Tipo omitido | 200 | Actual 10000 | 10000 |
| Sin JWT | 401 | Rechazo | No autenticado |
| JWT de otro tenant | 404 | Rechazo | Cuenta no encontrada: 6 |
| Id inexistente | 404 | Rechazo | Cuenta no encontrada: 9223372036854775807 |

Tras los cambios queda un solo movimiento, el pago de compra de 60000.
KPIs del tenant nuevo antes/despues de editar inicial: totalPurchases 60000,
todos los demas indicadores 0, incluidos otherIncome y netProfit, identicos.

## PDF y XLSX descargados por HTTP

Archivos de la cuenta renombrada, despues de inicial 70000. Cifras esperadas = obtenidas
en ambos formatos; XLSX leido como numeros y PDF extraido y renderizado a PNG para
inspeccion visual, sin cortes ni superposiciones.

| Rango | Apertura | Ingresos | Egresos | Neto | Cierre |
|-|-|-|-|-|-|
| 2026-10-24 a 2026-10-25 | 70000 | 0 | -60000 | -60000 | 10000 |
| 2026-10-25 a 2026-10-25 | 10000 | 0 | 0 | 0 | 10000 |

En el segundo rango la compra es anterior al periodo; apertura 70000 - 60000 = 10000.
El reporte no tiene una fila adicional para el saldo inicial de la cuenta: muestra la
apertura calculada del periodo. No se modificaron generadores ni consultas de reportes.

## Demo, antes frente a despues

Solo se renombro Caja General conservando inicial 2500000. PUT 200; nombre restaurado
con PUT 200 y lista completa comprobada igual a la original. Cifras capturadas por API,
sin asumir valores de notas anteriores.

| Indicador | Antes | Despues |
|-|-|-|
| totalSales | 76209943.11 | 76209943.11 |
| totalPurchases | 32210920 | 32210920 |
| netSales | 64041969 | 64041969 |
| costOfSales | 43263000 | 43263000 |
| grossProfit | 20778969 | 20778969 |
| operatingExpenses | 19708000 | 19708000 |
| otherIncome | 310000 | 310000 |
| netProfit | 1380969 | 1380969 |
| Caja General, saldo actual | 4406852.45 | 4406852.45 |
| Bancolombia, saldo actual | 16963933.02 | 16963933.02 |
| Nequi, saldo actual | 6807530.08 | 6807530.08 |
| Balance global, JSON literal | 28178315.549999997 | 28178315.549999997 |

Iniciales tambien iguales: Caja 2500000, Banco 15000000, Nequi 600000.
La suma decimal esperada es 28178315.55; la representacion Double del endpoint global
conserva el residuo indicado, identico antes/despues. Redondeo fuera de alcance.

## Mensajes nuevos para el frontend, tarea 13

* 400 exacto: `El tipo de una cuenta no se puede cambiar`.
* 409 plantilla: `Saldo insuficiente en {cuenta}: faltan {monto} para cambiar el saldo inicial`.
  Ejemplo HTTP exacto: `Saldo insuficiente en Banco prueba: faltan $ 10.000 para cambiar el saldo inicial`.
  Monto con Moneda.formatear, mismo formato de moneda existente.
* Se reutilizan `Peticion invalida` para anotaciones del DTO, `Ya existe una cuenta con
  el nombre {nombre}`, `Cuenta no encontrada: {id}` y `No autenticado`; no son nuevos.

## Archivos y limitaciones

Modificados: TreasuryController, TreasuryService, Account (comentarios),
InsufficientBalanceException (constructor de mensaje), ConcurrentOperationsTest,
ReportExportTest, 01-ARCHITECTURE.md, 02-CONVENTIONS.md y 03-DECISIONS.md.
Nuevos: AccountUpdateRequest, AccountEditingTest y esta nota. Sin dependencias,
migraciones, seguridad, siembra, frontend ni cambios funcionales en otros endpoints.

No hay historial de cambios del inicial: los reportes anteriores se recalculan con el
valor vigente. No se agrega revision historica. Un gasto posterior permitido puede
dejar saldo negativo aunque la edicion haya validado correctamente bajo bloqueo.
El dinero sigue Double/NUMERIC(14,2); redondeo queda para otra tarea. Pruebas en H2,
sin Docker ni comprobacion contra PostgreSQL real. Sin prueba de desactivacion porque
ese estado no existe en cuentas.

Backend y Maven iniciados por esta tarea detenidos (PIDs 260508 y 270064, verificados).
Temporales HTTP, descargas y capturas eliminados fuera del repo, carpeta vacia eliminada.
No se iniciaron Vite, Edge ni procesos ajenos; no se guardaron credenciales en Git.
