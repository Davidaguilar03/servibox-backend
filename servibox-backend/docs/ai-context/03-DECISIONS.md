# Log de decisiones tecnicas

Una entrada por decision, en orden cronologico inverso (la mas reciente arriba).

Formato de cada entrada:

```
## AAAA-MM-DD: Titulo corto de la decision

* Decision: que se decidio hacer.
* Alternativas consideradas: opciones que se evaluaron y se descartaron.
* Motivo: por que se eligio esta opcion sobre las demas.
```

## 2026-09-08: Gastos operativos, con edicion (revertir y recrear) y eliminacion

Cierra la nota *Donde vive OperationalExpense, para la proxima migracion* (2026-09-07).

* Hallazgo, verificado leyendo `treasury/model/OperationalExpense.java`,
  `treasury/controller/OperationalExpenseFormController.java`,
  `treasury/controller/OperationalExpensesController.java` y los metodos
  `saveOperationalExpense` / `deleteOperationalExpense` de `TreasuryService` en Autollantas:

  * **Campos:** `concept` (`concepto_gasto`), `amount` (`monto_gasto`), `account`
    (`id_cuenta`), `date` (`fecha_gasto`) y `notes` (columna `notes`, sin `@Column`).
    Ninguno lleva `nullable = false`; la obligatoriedad vive en el formulario, y su
    `validateFields()` exige **concepto, monto, cuenta y fecha**. **`notes` NO se valida**:
    es el campo "Observaciones" y es **opcional**. Se porta como opcional.
  * **Creacion:** si, registra un EGRESO igual que `OccasionalIncome` registra un INGRESO.
    `saveOperationalExpense` resta del `currentBalance` y crea un `Movement` "Egreso" con
    `sourceTable = "GASTOS_OPERATIVOS"`.
  * **Autollantas tiene LAS DOS: edicion y eliminacion.** El menu contextual de
    `OperationalExpensesController` ofrece "Editar" (abre el formulario, que llama a
    `saveOperationalExpense(expense, editMode = true)`) y "Eliminar" (con dialogo de
    confirmacion, llama a `deleteOperationalExpense`). Es el **unico** registro de su
    tesoreria con modo edicion: `OccasionalIncome` solo tiene eliminacion.
  * **Mecanismo exacto de la edicion**, y coincide con lo que se suponia: es
    **revertir y recrear**, no un ajuste por la diferencia. En modo edicion,
    `saveOperationalExpense` recorre `findBySourceIdAndSourceTable(id, "GASTOS_OPERATIVOS")`
    y por cada movimiento viejo le **suma de vuelta** su importe a `old.getAccount()` (la
    cuenta **del movimiento**, no la del gasto que se esta guardando) y lo **borra**; luego
    guarda el gasto con los campos nuevos y cae en el mismo bloque que la creacion, que
    resta el monto nuevo y crea un `Movement` nuevo.
  * **Mecanismo de la eliminacion:** `deleteOperationalExpense` suma el importe de vuelta al
    saldo, borra los `Movement` del gasto y **borra la fila**. Es el mismo patron que
    `deleteOccasionalIncome`: borrado, no estado `ANULADA`.
  * **Fechas:** el ultimo commit que toca cualquiera de estos archivos es `8b29f7e`
    (2026-08-04), anterior a las notas de esta migracion (2026-09-07), asi que no hay
    conflicto que resolver: lo documentado se derivo de este mismo codigo y se confirmo
    linea por linea antes de portarlo.

* Decision: se migra **dentro de `treasury`**, no en un modulo propio, aunque en la interfaz
  de Autollantas aparezca bajo Egresos. Se implementan **las dos** operaciones:
  `editarGastoOperativo` y `anularGastoOperativo`.
* La edicion reutiliza el `revertir(...)` privado que ya usaban la anulacion de ventas, la
  de compras y la de ingresos ocasionales, en vez de una segunda logica de reversion. Ese
  metodo ya hace exactamente lo que hace Autollantas en modo edicion, incluido lo importante:
  devuelve el importe a la cuenta **del movimiento**, lo que hace que **cambiar de cuenta**
  al editar salga bien. Cubierto por
  `editarUnGastoCambiandoDeCuentaDevuelveElDineroALaCuentaOriginal`.
* Alternativas consideradas para la edicion: aplicar solo la **diferencia** al saldo; o
  dejar el movimiento viejo y agregar un contra-movimiento.
* Motivo: la diferencia se rompe en cuanto la edicion cambia de cuenta, que es justo el caso
  que el codigo real resuelve bien. El contra-movimiento dejaria tres renglones en el
  historial de la cuenta por un gasto que se corrigio una vez, y el historial de tesoreria
  es una lista que alguien lee. Autollantas borra y recrea; se replica.
* **Se estreno el enum `MovementSourceType` con `OPERATIONAL_EXPENSE`**, y costo lo que la
  decision del 2026-09-07 prometia: **una linea**, sin columna nueva, sin migracion de
  esquema y sin tocar la firma de `aplicarMovimiento`. Con el diseno anterior (una relacion
  `ManyToOne` opcional por origen) habria sido la quinta columna nullable y el quinto
  parametro. Es la validacion practica de aquel refactor.
* Divergencia consciente: **`registrarGastoOperativo` no exige saldo suficiente**, a
  diferencia de `registrarEgresoDeCompra`. Autollantas tampoco lo hace aqui (su formulario
  solo valida concepto, monto, cuenta y fecha), asi que una cuenta puede quedar en negativo
  por un gasto operativo. Se replica el comportamiento real y **queda anotado como punto
  abierto**: si se decide que ningun egreso pueda dejar una cuenta en negativo, el sitio es
  este metodo, y hay que decidirlo para gastos e ingresos a la vez, no solo aqui.
* Sobre `notes`: es el unico registro de tesoreria migrado que lo trae. `OccasionalIncome`
  tiene el campo en Autollantas y **no** se porto (decision del 2026-09-07); aqui si, porque
  el alcance lo pedia y porque el formulario de gastos lo usa de verdad. Queda la asimetria
  entre las dos entidades espejo, anotada a proposito.
* Cubierto por `OperationalExpenseTest` (9 casos): registro y efecto en el saldo, gasto sin
  observaciones, edicion que revierte y recrea, edicion cambiando de cuenta, eliminacion,
  aislamiento por tenant en el listado y en el balance, PUT y DELETE sobre el gasto de otro
  tenant (404), el ciclo completo por HTTP y el rechazo sin JWT.

## 2026-09-08: Comprar reescribe el costo del producto, y anular no lo deshace

Cierra la omision marcada como "la mas importante de esta migracion" en la entrada
*Diferencias entre lo migrado de Purchases y el codigo de Autollantas* (2026-09-07).

* Hallazgo, verificado leyendo `purchases/service/PurchasesService.java` e
  `inventory/service/InventoryService.java` de Autollantas:
  * `savePurchaseWithDetails`, por cada `PurchaseDetail`, hace
    `realProduct.setQuantity(quantity + detail.getQuantity())` y, **si
    `detail.getUnitPrice() != null`**, `realProduct.setPurchaseCost(detail.getUnitPrice())`
    seguido de `inventoryService.recalculatePrices(realProduct)`.
  * El campo que se pisa es **solo `purchaseCost`**; `recalculatePrices` es el **metodo
    completo**, no un subconjunto: recalcula `taxAmount` y `suggestedPrice` y persiste.
    (Autollantas no tiene `minSalePrice`; ServiBox tampoco. Los dos campos derivados del
    costo son esos dos.)
  * Ese bloque recorre **todas** las lineas y corre **antes y fuera** del
    `if ("Contado".equals(purchase.getPaymentType()) && purchase.getAccount() != null)` que
    mueve la caja. Es decir: pasa en **Contado y en Credito por igual**, no hay condicion
    por tipo de pago que replicar.
  * Con el mismo producto en dos lineas, cada iteracion pisa la anterior: **gana el precio
    de la ultima linea procesada**.
* Decision: se porta tal cual a `PurchasesService.crearFactura`, en el mismo bucle que ya
  sumaba el stock (`sincronizarCosto`). Se llama a `InventoryService.recalculatePrices`, el
  mismo metodo que usa `saveProduct`, y no una copia de la formula.
* Motivo: es una regla de negocio real, no un efecto secundario accidental. El precio
  sugerido existe para responder "a cuanto vendo esto", y esa respuesta cambia cuando cambia
  lo que costo reponerlo. Dejarlo fuera hacia que el precio sugerido envejeciera en silencio
  contra el costo de la ultima compra.
* Diferencia menor y consciente: Autollantas salta la linea si `unitPrice` es `null`; aqui
  `crearFactura` ya normaliza el precio ausente a `0.0` antes de armar el detalle, asi que
  una linea sin precio deja `purchaseCost` en cero. `recalculatePrices` es un no-op con
  costo cero, de modo que el precio sugerido no se degrada. Es el mismo resultado que da
  Autollantas para `unitPrice = 0.0`.
* **Anular NO revierte el costo**, y se hereda a proposito: `cancelPurchase` de Autollantas
  solo resta `product.setQuantity(quantity - detail.getQuantity())` y toca tesoreria; no
  hay ni una linea que toque `purchaseCost` ni que vuelva a llamar a `recalculatePrices`
  (`restorePurchase` tampoco: repone el stock y el movimiento, y deja el costo como
  estaba). Anular una compra deshace el stock y el dinero pero **deja el producto con el
  costo y el precio sugerido que esa compra le puso**.
  * Alternativas consideradas: guardar el costo anterior en la compra para restaurarlo al
    anular; o recalcular el costo desde la ultima compra no anulada.
  * Motivo para no hacerlo: las dos inventan un historico de costos que el sistema real no
    tiene, y la segunda cambia el significado de `purchaseCost` (pasaria de "lo que costo la
    ultima vez que compre" a "lo que costo la ultima compra vigente"). Es una omision real
    de Autollantas, no un descuido de esta migracion: queda documentada aqui y en el javadoc
    de `anularFactura`, y **fijada por el test** `anularNoRevierteElCostoDelProducto`, para
    que nadie la "arregle" por accidente creyendo que es un bug de ServiBox.
* Cubierto por `PurchasesCostSyncTest`: costo y precio sugerido tras comprar (a credito y de
  contado), el precio de la ultima linea cuando el producto se repite, y la no reversion al
  anular. Ningun test previo de Purchases hubo que ajustarlo: todos compran al mismo precio
  que ya tenia el producto (100000.0), asi que el costo no cambia de valor, y ninguno
  afirmaba nada sobre `suggestedPrice`.

## 2026-09-07: El origen de un Movement pasa a ser (tipo, id)

* Decision: se eliminan las cuatro relaciones `ManyToOne` opcionales de `Movement`
  (`sourceTransfer`, `sourceSale`, `sourcePurchase`, `sourceOccasionalIncome`) y se
  reemplazan por `sourceType` (enum `MovementSourceType`, nullable) y `sourceId` (`Long`,
  nullable). `aplicarMovimiento` pasa de ocho parametros a seis y recibe el origen como
  `(tipo, id)`. Los tres buscadores del repositorio se unifican en
  `findBySourceTypeAndSourceIdAndTenantId`. `MovementResponse` expone `sourceType` y
  `sourceId` en vez de cuatro campos `sourceXxxId`.
* Alternativas consideradas: dejarlo como estaba y seguir agregando una relacion por
  origen; una tabla puente `ORIGEN_MOVIMIENTO`; o una jerarquia de entidades de origen con
  herencia JPA.
* Motivo: el diseno anterior no escalaba y ya se habia anotado el limite al llegar al
  cuarto. Cada origen nuevo obligaba a tocar cuatro sitios (entidad, repositorio, DTO y la
  firma de `aplicarMovimiento`), la firma llevaba cuatro parametros de los que tres eran
  siempre `null` en cada llamada, y nada impedia que dos vinieran poblados a la vez. Con el
  par, **agregar `OPERATIONAL_EXPENSE` es agregar un valor al enum**: sin columna nueva,
  sin cambio de esquema y sin tocar ninguna firma.

  La tabla puente y la jerarquia resuelven lo mismo con una tabla o un arbol de clases de
  mas, para un dato que solo se usa como "traeme los movimientos de esto para revertirlos".

* **La contrapartida, y por que se acepta:** se pierde la clave foranea. `sourceId` apunta
  a tablas distintas segun el tipo, asi que la base ya no valida que el id exista. La
  seguridad de tipos, sin embargo, **nunca estuvo en la constraint**: esta en que ningun
  sitio construye un `Movement` a mano. Todo pasa por metodos de `TreasuryService`
  (`registrarIngresoDeVenta(… Sale venta)`, `registrarEgresoDeCompra(… Purchase compra)`,
  `registrarIngresoOcasional`, `registrarTransferencia`) que reciben **la entidad tipada
  real** y sacan el id de ella; `aplicarMovimiento` es privado. Un id suelto no entra al
  sistema, asi que la FK estaba validando algo que el compilador ya garantizaba un nivel
  mas arriba.

  Lo que si se agrego para compensar: el buscador lleva `tenantId` explicito en el `where`
  ademas del `@Filter` de Hibernate. Sin FK, ese `where` es la unica garantia de que no se
  cruce un id de otro tenant.

* Alcance del cambio: `MovementSourceType` (nuevo), `Movement`, `MovementRepository`,
  `MovementResponse`, `TreasuryService`, y seis archivos de test. **`SalesService` y
  `PurchasesService` no se tocaron**: ninguno consulta el repositorio de movimientos
  directamente, los dos delegan en `TreasuryService.revertirMovimientos*`, que era el
  unico sitio que sabia de los buscadores por origen. Que el refactor no los alcanzara
  confirma que la frontera del modulo estaba bien puesta.
* Verificacion: el suite quedo en **91 tests, exactamente los mismos que antes** del
  refactor y clase por clase. No se agrego ni se quito cobertura: los tests verifican lo
  mismo, expresado con los campos nuevos.

## 2026-09-07: Estado de la gestion del esquema (sin migraciones formales)

* Constatacion, no decision: **no hay Flyway ni Liquibase** en `pom.xml`, ni ninguna
  carpeta de migraciones versionadas. Los tests corren sobre H2 en memoria con
  `spring.jpa.hibernate.ddl-auto=create-drop` (`application-test.properties`), y
  `application.properties` **no declara datasource ni `ddl-auto`**, asi que en local todo
  depende de la autoconfiguracion de Spring Boot sobre la H2 embebida.
* Consecuencia inmediata: refactors de esquema como el de `(tipo, id)` de arriba no
  requieren migracion; el esquema se regenera solo.
* **Pendiente para produccion:** el driver de PostgreSQL ya esta en el `pom.xml`. En cuanto
  haya una base persistente, `create-drop` deja de servir y hace falta una herramienta de
  migracion, con una linea base que refleje el esquema actual. A partir de ese momento
  cambios como este si necesitan su migracion escrita a mano.

## 2026-09-07: El IVA de compras en Autollantas no esta hardcodeado, pero si diverge de ventas

* Hallazgo: la documentacion de Notion describe el IVA de compras como `price*0.19` por
  unidad y el total como `subtotal*0.19`. **En el codigo real no hay ningun `0.19` en todo
  el paquete `purchases`** (verificado con grep sobre el paquete completo). Notion esta
  desactualizada en ese punto; el codigo manda.
* Lo que si hay es una **quinta copia** de `getIvaRate(Product)`, en
  `PurchaseFormController`, y **no calcula lo mismo que las otras cuatro**:

  ```java
  // purchases: suma TODAS las rates de la categoria
  return p.getCategory().getTaxTypes().stream()
          .mapToDouble(t -> t.getRate() != null ? t.getRate() : 0.0)
          .sum();

  // sales: filtra isVat, se queda con la primera, y tiene rama de servicios
  return p.getCategory().getTaxTypes().stream()
          .filter(t -> Boolean.TRUE.equals(t.getIsVat()))
          .mapToDouble(...).findFirst().orElse(0.0);
  ```

  O sea que en Autollantas una categoria con IVA 19 por ciento mas ReteICA 3 por ciento
  produce 22 por ciento de "IVA" al comprar y 19 por ciento al vender, sobre el mismo
  producto.
* Decision: ServiBox usa `InventoryService.getIvaRateForProduct` tambien en Purchases, o
  sea la semantica de `isVat`, la misma que Inventory y Sales.
* Motivo: la consigna era no corregir un hardcode sin avisar; el hardcode no existe, asi
  que esa condicion no aplica. Lo que hay es la inconsistencia entre modulos, y replicarla
  significaria meter retenciones dentro de una cifra de IVA descontable en el modulo nuevo,
  que es exactamente lo que la decision del 2026-08-28 sobre `taxAmount` ya rechazo por
  inflar el IVA declarable. Sumar rates que no son IVA al comprar y no al vender no es una
  regla de negocio, es una copia que se desincronizo. **Queda dicho para que se confirme:**
  si el criterio del negocio fuera realmente ese, se revierte con un metodo aparte.

## 2026-09-07: La anulacion de compras tenia el mismo defecto que la de ventas

* Hallazgo: `PurchasesService.cancelPurchase` de Autollantas revierte tesoreria solo con
  `"Contado".equals(paymentType) && "PAGADA".equals(status)`. Es la misma condicion, linea
  por linea, que `SalesService.cancelSale`. Anular una compra a credito con pagos ya
  hechos deja ese dinero descontado de la caja sin devolver.
* Decision: se aplica **la misma correccion** que ya se hizo en Sales, no se trata como un
  hallazgo nuevo: `anularFactura` revierte todos los movimientos ligados a la compra,
  pagos incluidos, apoyandose en `Movement.sourcePurchase`. Cubierto por
  `anularUnaCompraConPagosRevierteEsosPagos`.
* Motivo: es el mismo defecto de la misma forma en el modulo espejo, casi seguro por copia
  del uno al otro. Corregir uno y dejar el otro seria arbitrario.

## 2026-09-07: Anular una compra puede fallar por stock ya vendido

* Decision: `anularFactura` comprueba **todas** las lineas antes de tocar nada y rechaza
  con un mensaje que nombra el producto y las cantidades si a alguno le quedan menos
  unidades de las que habria que quitar.
* Alternativas consideradas: copiar Autollantas, que resta sin mirar y deja el producto en
  negativo; o dejar la cantidad en cero en vez de negativa.
* Motivo: Autollantas no valida nada aqui, asi que anular una compra cuyas unidades ya se
  vendieron deja stock negativo, que despues nadie sabe interpretar y que rompe la
  siguiente venta. Dejarlo en cero es peor: oculta el descuadre y pierde la unica senal de
  que algo no cuadra. Rechazar obliga a resolverlo, que es lo correcto: si esas unidades ya
  salieron, la compra no se puede deshacer sin antes anular las ventas.

  La comprobacion es previa y completa a proposito: la transaccion desharia una anulacion a
  medias igual, pero validar antes de escribir da el mensaje correcto en vez de un fallo a
  mitad de camino.

## 2026-09-07: Movement.sourcePurchase, cuarto origen y limite del patron (RESUELTO el mismo dia)

> **RESUELTO.** Se hizo el refactor que esta entrada dejaba pendiente: las cuatro
> relaciones se reemplazaron por el par `sourceType` + `sourceId`. Ver la entrada "El
> origen de un Movement pasa a ser (tipo, id)" mas arriba. La entrada original se conserva
> por el razonamiento.


* Decision: se agrega `Movement.sourcePurchase` siguiendo el mismo patron que los tres
  anteriores, sin cambiar el diseno.
* Motivo: **este es el punto donde dijimos que tocaria reevaluar**, y se mantiene el patron
  a proposito para no cambiar la forma de `Movement` en medio de una migracion. Pero la
  cuenta ya no sale igual que con dos o tres: son cuatro columnas nullables mutuamente
  excluyentes, ningun mecanismo impide que dos vengan pobladas a la vez, y cada origen
  nuevo obliga a tocar la entidad, el repositorio, el DTO y la firma de
  `aplicarMovimiento` (que ya lleva cuatro parametros de origen, tres de ellos siempre
  null en cada llamada).

  **Pendiente de discutir, no resuelto:** las salidas razonables son un par
  `(tipoOrigen enum, idOrigen)` con indice compuesto, que recupera lo malo del polimorfico
  pero acotado por un enum en vez de nombres de tabla en `String`; o una tabla puente
  `ORIGEN_MOVIMIENTO`. Antes de agregar un quinto origen conviene decidirlo.

## 2026-09-07: Diferencias entre lo migrado de Purchases y el codigo de Autollantas

* **`subtotal` e `ivaTotal` son columnas**, igual que en `Sale`: Autollantas solo persiste
  `total` y calcula el resto en la UI. Mismo criterio de congelar los totales de una
  factura emitida.
* **El saldo pendiente no es columna**: se deriva de la suma de pagos. Autollantas lo
  guarda en `saldo_pendiente`.
* **`businessName` no existe en Autollantas.** Su formulario tiene un unico campo
  "Nombre/razon social" que va a `nombre_proveedor`; aqui van separados porque el alcance
  lo pedia. `Supplier.name` sigue siendo el que se muestra.
* **`ivaAmount` de la linea es el de la linea completa.** Autollantas guarda en
  `impuesto_compra` el IVA **por unidad** (asi lo muestra la columna "IVA/Unidad"). Se
  unifico con el criterio de `SaleDetail.ivaAmount` para que las dos lineas signifiquen lo
  mismo y no haya que recordar cual es cual.
* ~~**No se porto la actualizacion del costo del producto.**~~ **RESUELTO el 2026-09-08**:
  se porto. En Autollantas `savePurchaseWithDetails` hace, por cada linea,
  `realProduct.setPurchaseCost(unitPrice)` y `inventoryService.recalculatePrices(realProduct)`:
  comprar a un costo nuevo **reescribe el costo del producto y rehace su precio sugerido**.
  Era una regla de negocio real y quedo fuera porque el alcance pedia solo incrementar
  `quantity`. Ver la entrada *Comprar reescribe el costo del producto, y anular no lo
  deshace* (2026-09-08), que ademas documenta que la anulacion **no** revierte ese costo.
* **No hay edicion ni restauracion** de compras (`savePurchaseWithDetails` en modo edicion,
  `restorePurchase` desde la papelera), igual que en Sales.
* Los enums `PaymentType` y `PurchaseStatus` se duplican en `purchases` en vez de
  compartirse con `sales`: son dos y tres valores fijos, y una clase comun obligaria a que
  los dos modulos dependieran entre si o de un paquete compartido por cinco constantes.

## 2026-09-07: Donde vive OperationalExpense, para la proxima migracion (RESUELTO el 2026-09-08)

* Ubicacion en Autollantas: **`treasury`**, no `purchases`, aunque en la interfaz aparezca
  bajo Egresos junto a las facturas de compra. Archivos:
  `treasury/model/OperationalExpense.java`, `treasury/repository/OperationalExpenseRepository.java`,
  `treasury/controller/OperationalExpensesController.java` y
  `OperationalExpenseFormController.java`, mas los metodos `saveOperationalExpense` y
  `deleteOperationalExpense` en `TreasuryService`.
* Campos del modelo: `concept` (`concepto_gasto`), `amount` (`monto_gasto`), `account`,
  `date` (`fecha_gasto`) y `notes`. Es la imagen espejo de `OccasionalIncome`.
* Comportamiento: **si**, genera egreso igual que `OccasionalIncome` genera ingreso.
  `saveOperationalExpense` resta del `currentBalance` y crea un `Movement` "Egreso" con
  `sourceTable = "GASTOS_OPERATIVOS"`; `deleteOperationalExpense` suma de vuelta y borra el
  movimiento. Tiene ademas modo edicion, que revierte el movimiento viejo antes de aplicar
  el nuevo, cosa que `OccasionalIncome` no tiene.
* Consecuencia para el diseno: al migrarlo hara falta un **quinto** origen en `Movement`,
  que es justo el punto en el que la decision de arriba dice que hay que reevaluar el
  patron antes de seguir agregando columnas.

> **Resuelto el 2026-09-08**, ver *Gastos operativos, con edicion (revertir y recrear) y
> eliminacion*. La reevaluacion ya se habia hecho: el quinto origen fue un valor mas en
> `MovementSourceType`, no una quinta columna. Se confirmo ademas que **tiene edicion y
> eliminacion**, y el mecanismo exacto de la edicion.

## 2026-09-07: Ingresos ocasionales, con eliminacion porque Autollantas la tiene

* Decision: `OccasionalIncome` vive **dentro de `treasury`**, no en un modulo propio.
  `registrarIngresoOcasional` pasa por `aplicarMovimiento` como todo lo demas, y **si** se
  implemento el deshacer (`anularIngresoOcasional`), con una tercera relacion opcional
  `Movement.sourceOccasionalIncome`.
* Investigacion previa: la duda era si Autollantas permite deshacer un ingreso ocasional ya
  registrado, para no inventar funcionalidad que el sistema real no tiene. **Si la tiene**:
  `OccasionalIncomeController` expone "Eliminar" en el menu contextual y en un boton
  (`btnEliminarClick`, mas atajo de teclado), con dialogo de confirmacion, y llama a
  `TreasuryService.deleteOccasionalIncome`, que resta el importe del saldo de la cuenta,
  borra los `Movement` con `sourceTable = "INGRESOS_OCASIONALES"` y borra la fila. Por eso
  se implementa.
* Sobre las fechas: el paquete no se toca desde `8b29f7e` (2026-08-04) el controller,
  `a0610cb` (2026-08-02) el formulario y `6b3e8ad` (2026-05-10) el modelo; la pagina de
  Notion es posterior. Se contrastaron y coinciden, salvo que Notion lista los campos como
  "concept, amount, account, date" y el modelo real tiene ademas `notes`.
* Alternativas consideradas: no implementar el deshacer, que era la opcion por defecto si
  la investigacion no lo encontraba; o dejar el registro con un estado `ANULADA` como se
  hizo con las facturas de venta.
* Motivo: se replica el comportamiento real, que es **borrado**, no anulacion con estado.
  Un ingreso ocasional no es un documento fiscal con numeracion: nadie lo reclama ni lo
  audita por numero, asi que no hay nada que conservar y una fila `ANULADA` seria ruido en
  el listado. Una factura de venta si se conserva porque se entrego y se declaro. **Ojo con
  el nombre del metodo:** se llama `anularIngresoOcasional` por consistencia con el resto
  de la API, pero su efecto es eliminar, no marcar. Esta anotado en el javadoc y en
  [02-CONVENTIONS.md](02-CONVENTIONS.md) para que nadie asuma lo contrario.

  El deshacer comparte con la anulacion de ventas la excepcion a la regla de que
  `aplicarMovimiento` es el unico punto que toca `currentBalance`, por el mismo motivo, y
  vive igualmente dentro de `TreasuryService`.

  **No se sembraron datos de prueba**: `BasicDataInitializer` de Autollantas no crea ningun
  ingreso ocasional por defecto, asi que `DevDataInitializer` se dejo intacto.

* Nota sobre los origenes de `Movement`: van tres relaciones opcionales
  (`sourceTransfer`, `sourceSale`, `sourceOccasionalIncome`). Al introducir la segunda se
  dejo dicho que con dos casos generalizar era pagar por adelantado; con tres la cuenta
  sigue saliendo, porque cada una se borra con su propia consulta derivada y la base las
  valida. Pero es el limite: **si Purchases trae una cuarta, toca revisar** si conviene una
  jerarquia de origen o volver al par polimorfico con un indice compuesto.

## 2026-09-07: Anular una factura muta el balance directamente, saltandose aplicarMovimiento

* Decision: `TreasuryService.revertirMovimientosDeVenta(Sale)` **borra** los movimientos
  de la venta y ajusta `currentBalance` a mano, al reves de cada movimiento borrado. Es la
  unica excepcion a la regla de que `aplicarMovimiento` es el unico punto que toca el
  saldo. Vive en `TreasuryService` y no en `SalesService` a proposito.
* Alternativas consideradas: registrar un `EGRESO` compensatorio en vez de borrar, que
  mantendria la regla intacta; o dejar el movimiento y solo marcar la factura `ANULADA`.
* Motivo: es el comportamiento real de Autollantas (`SalesService.cancelSale`), y no es un
  detalle de implementacion sino una decision contable del negocio: una factura anulada
  **no ocurrio**, asi que su rastro sale del extracto en vez de quedar como dos renglones
  que se cancelan. Un contra-movimiento seria mas limpio de codigo y peor de leer para el
  usuario: el extracto de la caja mostraria un ingreso y un egreso por una venta que nunca
  existio, y los reportes por periodo tendrian que aprender a ignorar pares. Dejar el
  movimiento sin mas descuadraria la caja contra la realidad.

  Que viva en `TreasuryService` es el limite que si se mantiene: si el saldo se va a mover
  por fuera del camino normal, que al menos sea dentro del modulo que es dueno del saldo, y
  no repartido por cada modulo que quiera revertir algo. `SalesService` pide la reversion,
  no la ejecuta.

  **Diferencia con Autollantas, deliberada:** alli `cancelSale` solo revierte tesoreria si
  la factura es `Contado` **y** esta `PAGADA`, asi que anular una factura a credito con
  abonos ya recibidos deja ese dinero sumado en la caja y descuadra. Aqui se revierten
  todos los movimientos ligados a la venta, abonos incluidos, lo que ademas sale gratis
  porque la relacion es `Movement.sourceSale`. Cubierto por
  `anularUnaFacturaConAbonosRevierteEsosAbonos`.

## 2026-09-07: El numero de factura lleva restriccion de base, no solo validacion

* Decision: `Sale` lleva `uk_venta_tenant_invoice_number` sobre
  `(tenant_id, numero_factura_venta)`, ademas de la validacion en
  `SalesService.crearFactura` que lanza `DuplicateInvoiceNumberException` (**409**). Lo
  mismo con `Customer` y `uk_cliente_tenant_document`.
* Alternativas consideradas: copiar Autollantas, que valida el numero con
  `existsByInvoiceNumberIgnoreCase` antes de guardar y **no tiene indice unico** en
  `ventas` (su propia documentacion lo dice explicito).
* Motivo: en Autollantas la unica via de escritura es el formulario, con un solo usuario en
  una maquina; la validacion de aplicacion alcanza. ServiBox es una API multi-tenant con
  varios clientes concurrentes: dos peticiones simultaneas con el mismo numero pasan las
  dos la comprobacion previa y las dos insertan, porque entre el `SELECT` y el `INSERT` no
  hay nada que lo impida. Un numero de factura repetido no es un problema estetico, es un
  documento fiscal duplicado. La restriccion de base es lo unico que cierra esa ventana.
  La validacion en el service se mantiene igualmente para dar un 409 legible en vez del
  error crudo de constraint violation: misma estructura de dos capas que
  `Product.code` y `Account.name`. Verificado que la restriccion existe de verdad en el
  DDL con `SalesDatabaseConstraintsTest`.

## 2026-09-07: Movement.sourceSale, y que treasury conozca a sales

* Decision: `Movement` gana un `ManyToOne` **opcional** a `Sale`
  (`id_venta_origen`, nullable), hermano de `sourceTransfer`. Entre los dos cubren los dos
  origenes automaticos que hoy existen. `MovementResponse` expone `sourceSaleId`.
* Alternativas consideradas: volver al par polimorfico `(tabla_origen, id_origen)` de
  Autollantas ahora que hay un segundo origen, que era la duda que quedo abierta al
  introducir `sourceTransfer`; o poner la relacion al reves, de `Sale` al `Movement`.
* Motivo: la duda abierta era si al aparecer el segundo origen convenia generalizar. Con
  dos casos a la vista, dos relaciones opcionales siguen siendo mejor que el par
  polimorfico: la base valida las dos, no hace falta ningun `switch` sobre nombres de tabla
  en `String`, y borrar los movimientos de una venta es una consulta derivada
  (`findBySourceSaleId`) en vez de un filtro por dos columnas sin indice. El precio es que
  `treasury` ahora importa de `sales`. Es la misma direccion de dependencia que ya tiene
  Autollantas, donde `Collection` vive en `treasury` y referencia `Sale`. Si algun dia
  hacen falta cuatro o cinco origenes, ahi si conviene revisar; con dos, generalizar es
  pagar por adelantado.

  Al reves (de `Sale` al movimiento) no sirve: una venta genera varios movimientos a lo
  largo del tiempo, el del contado y despues uno por cada abono. La cardinalidad manda.

## 2026-09-07: Diferencias entre lo migrado de Sales y el codigo de Autollantas

* Decision: se dejan anotadas las divergencias del modulo Sales que no tienen entrada
  propia, para no tener que redescubrirlas leyendo los dos repos.
* Detalle:
  * **`subtotal` e `ivaPorPagar` son columnas.** En Autollantas no existen como campos: la
    factura solo persiste `total`, y el subtotal y el IVA por pagar se recalculan en la UI
    (`calculateSubtotalSinIva`, `calculateDiferenciaIva`). Persistirlos sigue el mismo
    criterio que congelar `ivaAmount`: los totales de una factura emitida son un dato
    historico, no un calculo que deba dar distinto si cambian los impuestos manana.
  * **El saldo pendiente no es columna.** Autollantas lo guarda en `saldo_pendiente` y lo
    recalcula a mano en cinco sitios; aqui se deriva de la suma de abonos. Un denormalizado
    que se recalcula en cinco sitios es cinco oportunidades de desincronizarlo. Si algun
    dia el volumen lo pide, se materializa.
  * **`getIvaRateForProduct` no tiene la rama de servicios.** El de Autollantas mira
    primero `InventoryService.isService(p)` y, si lo es, usa `taxAmount / basePrice`.
    ServiBox no tiene todavia `itemType` ni `basePrice` en `Product`, asi que no hay
    servicios que distinguir. Cuando se migren, esa rama hay que traerla.
  * **Suma las rates con `isVat` en vez de quedarse con la primera.** Autollantas usa
    `findFirst()`; aqui se mantiene la semantica de suma que ya tenia `vatRateOf`, por
    coherencia con la decision de `taxAmount` del 2026-08-28.
  * **`Collection` vive en `sales`, no en `treasury`.** En Autollantas esta en `treasury`
    aunque solo tenga sentido colgando de una factura.
  * **`Customer` no tiene `documentType`.** Autollantas guarda tipo y numero de documento;
    aqui solo el numero, que es lo que pedia el alcance. Si hace falta discriminar CC de
    NIT, se agrega.
  * **La factura no se puede editar ni restaurar todavia.** Autollantas tiene
    `saveSaleWithDetails` en modo edicion y `restoreSale` desde la papelera, las dos con
    su propia reversion de stock y de caja. Fuera del alcance de esta migracion.

## 2026-09-07: La transferencia genera sus dos movimientos, con relacion al Transfer

* Decision: `registrarTransferencia` ya no toca `currentBalance` directamente. Guarda el
  `Transfer` y llama dos veces a `aplicarMovimiento`, un `EGRESO` en el origen y un
  `INGRESO` en el destino, los dos con `sourceTransfer` apuntando al `Transfer`; son esos
  movimientos los que mueven los saldos, todo en la misma transaccion. `Movement` gana un
  `ManyToOne` **opcional** a `Transfer` (`id_transferencia_origen`, nullable), null en los
  movimientos sueltos. `MovementResponse` expone `sourceTransferId`. Esto cierra la
  limitacion anotada mas abajo en este mismo archivo.
* Alternativas consideradas: portar el mecanismo de Autollantas tal cual, con
  `sourceTable = "TRANSFERENCIAS"` mas `sourceId`; guardar el origen como un simple enum
  o `String` sin relacion; o dejar que `registrarTransferencia` siguiera moviendo los
  saldos y ademas insertara los movimientos "informativos".
* Motivo: el problema de fondo era que el historial de una cuenta no explicaba todos sus
  cambios de saldo: una transferencia movia la plata y solo quedaba registrada en
  `TRANSFERENCIAS`, asi que el extracto mostraba un saldo que no cuadraba con sus propios
  renglones. Generar los dos movimientos lo arregla y ademas alinea el comportamiento con
  Autollantas.

  Que la transferencia **no** mueva el saldo por su cuenta es la parte que importa del
  refactor: `aplicarMovimiento` queda como el unico sitio del modulo donde se crea un
  `Movement` y se toca `currentBalance`. La alternativa de mover los saldos ahi y ademas
  insertar los movimientos deja dos caminos que calculan el mismo saldo, y basta con que
  uno cambie de signo para que el extracto y el balance se separen sin que ningun test lo
  note. Con un unico `if` decidiendo el signo eso no puede pasar.

  Sobre representar el origen: `(tabla_origen, id_origen)` es una clave foranea
  polimorfica que la base no puede validar, resuelta en Autollantas con un `switch` sobre
  nombres de tabla en `String`. Mientras el unico origen automatico sea la transferencia,
  una relacion real dice lo mismo con integridad referencial y sin el `switch`. La
  contrapartida conocida: cuando existan Sales y Purchases, un `Movement` que venga de una
  venta no cabe en `sourceTransfer` y habra que decidir si se agregan mas relaciones
  opcionales o se vuelve al par polimorfico. Se prefiere pagar esa decision cuando exista
  el segundo caso real y no adivinarla ahora con un campo generico que hoy no tiene con
  que compararse.
* Verificacion: el test de conservacion del dinero
  (`unaTransferenciaDebitaElOrigenAcreditaElDestinoYConservaElDinero`) se dejo **sin
  tocar** a proposito y sigue pasando con el refactor, que es la prueba de que mover el
  saldo via `Movement` da el mismo resultado que moverlo a mano. Nuevos:
  `unaTransferenciaGeneraElEgresoYElIngresoApuntandoAlTransfer` (servicio),
  `elListadoDeMovimientosMuestraLosGeneradosPorUnaTransferencia` (HTTP, contra
  `GET /api/treasury/accounts/{id}/movements`), `unMovimientoSueltoNoTieneTransferDeOrigen`
  y `unaTransferenciaRechazadaNoGeneraMovimientos`.

## 2026-09-07: Barrido de `findById` en todos los services, y 404 para el recurso de ruta

* Decision: se elimino todo uso del `findById` heredado de `JpaRepository` sobre entidades
  `TenantAware`. Quedan **3 metodos corregidos, los 3 en Inventory** (`findProductById`,
  `findCategoryById`, y la lectura dentro de `updateCategoryMargin`, que ahora reutiliza
  `findCategoryById`), con `findByIdAndTenantId` nuevo en `ProductRepository` y
  `ProductCategoryRepository`. Treasury ya estaba cubierto desde la entrada anterior.
  Ademas, un recurso pedido **por la ruta** que no existe para el tenant activo responde
  **404** (`shared.ResourceNotFoundException`), no 400.
* Alternativas consideradas: corregir solo `findProductById`, que era el unico caso
  anotado como pendiente; dejar el 400 que ya devolvia el `IllegalArgumentException`; o
  responder 403 en vez de 404 cuando la fila existe pero es de otro tenant.
* Motivo: al barrer el codigo aparecio que `findCategoryById` tenia el mismo hueco y era
  **peor que el de lectura**: `POST /api/inventory/products` resuelve la categoria por id,
  asi que sin el fix el tenant dos podia **crear** un producto colgando de una categoria
  del tenant uno. Es una escritura cruzada, no solo una lectura. Los dos casos quedan
  reproducidos en `InventoryTenantIsolationTest`
  (`unProductoDeOtroTenantNoSeDevuelvePorId` y
  `unaCategoriaDeOtroTenantNoSirveParaCrearleProductos`); verificado que ambos fallan al
  revertir el fix, con 200 y 201 respectivamente.

  Sobre el codigo de respuesta: 400 describia mal la situacion, porque la peticion estaba
  bien formada. 403 se descarto porque confirmaria que el id existe en otra cuenta, que es
  justo lo que el aislamiento tiene que ocultar; el mismo 404 cubre "no existe" y "es de
  otro tenant", con el mismo criterio que el mensaje generico de login de
  `AuthController`. El 400 se conserva cuando el id llega **dentro del cuerpo** de la
  peticion (`POST /api/treasury/movements`, `POST /api/treasury/transfers`,
  `POST /api/inventory/products`): ahi el problema si es la peticion. Por eso
  `TreasuryController` tiene `cuentaDeRuta` (404) y `cuentaObligatoria` (400) separadas, y
  el test de aislamiento de Treasury cambio su expectativa de 400 a 404.

## 2026-09-07: Nombre de cuenta unico por tenant

* Decision: `Account` lleva la restriccion compuesta `uk_cuenta_tenant_name` sobre
  `(tenant_id, nombre_cuenta)`, y `TreasuryService.saveAccount` valida con
  `findByNameAndTenantId` antes del INSERT lanzando `DuplicateAccountNameException`, que
  el `GlobalExceptionHandler` traduce a 409 con un mensaje legible.
* Alternativas consideradas: no poner restriccion ninguna, que es lo que hace Autollantas
  (su `CUENTAS` no tiene unique sobre el nombre); o `unique = true` sobre la columna sola;
  o dejar solo la restriccion de base sin validacion en el service.
* Motivo: en Autollantas las cuentas son dos, fijas, creadas por el inicializador, y el
  unico usuario nunca crea una tercera; el problema no existia. En ServiBox el endpoint
  `POST /api/treasury/accounts` deja crear cuentas libremente, y dos "Bancolombia" en el
  mismo taller hacen que cualquier movimiento vaya a la cuenta equivocada sin que nadie lo
  note, con el agravante de que el error se descubre cuadrando plata. `unique` sobre la
  columna sola esta descartado por la misma razon que en `User` y `Product`: cada tenant
  tiene su propia "Caja General". La validacion en el service ademas de la restriccion de
  base es el mismo patron de `DuplicateProductCodeException`: la base es la red de
  seguridad, el service es el que produce un mensaje que el usuario entiende.

## 2026-09-07: El `@Filter` de Hibernate no cubre `findById`, y eso era un cruce de tenants

* Decision: `TreasuryService.findAccountById` no usa el `findById` heredado de
  `JpaRepository`, sino la consulta derivada `findByIdAndTenantId`. Queda como convencion
  para toda busqueda por id de una entidad de negocio, ver
  [02-CONVENTIONS.md](02-CONVENTIONS.md).
* Alternativas consideradas: dejar `findById` y filtrar despues en el service comparando
  `getTenantId()` contra `TenantContext`; o envolver el `EntityManager`.
* Motivo: no es una preferencia de estilo, es un agujero real. El `@Filter` se aplica a
  las consultas (HQL, criteria, consultas derivadas de Spring Data) pero **no** a
  `EntityManager.find()`, que es lo que usa `findById`. Lo detecto el test de aislamiento
  del modulo: `GET /api/treasury/accounts/{id}/movements` con el token del tenant
  equivocado respondia 200 en vez de rechazar la cuenta ajena. Devolvia lista vacia solo
  porque la consulta de movimientos si es derivada y si estaba filtrada; la cuenta en si
  se resolvio cruzando el tenant. Filtrar a mano despues del `findById` funciona pero
  depende de que nadie se olvide en la entidad numero veinte; la consulta derivada lo hace
  imposible de olvidar porque el tenant esta en la firma del metodo. Envolver el
  `EntityManager` ya se descarto antes por romper la gestion de transacciones, ver los
  callejones sin salida en [01-ARCHITECTURE.md](01-ARCHITECTURE.md).
* ~~Pendiente conocido: `InventoryService.findProductById` tiene el mismo patron.~~
  **RESUELTO el 2026-09-07**, y el alcance real era mayor que el anotado: ver la entrada
  "Barrido de `findById` en todos los services" mas arriba en este mismo archivo.

## 2026-09-07: `Movement` con `concept` propio y `type` como enum

* Decision: `Movement` lleva una columna `concepto_movimiento` y un enum `MovementType`
  (`INGRESO`, `EGRESO`). Autollantas no tiene ninguna de las dos cosas: su
  `MOVIMIENTOS.tipo_movimiento` es un `String` con los literales `"Ingreso"` y `"Egreso"`,
  y la tabla no tiene columna de concepto.
* Alternativas consideradas: portar el esquema tal cual, con `type` como `String` y la
  descripcion derivada de `(tabla_origen_movimiento, id_origen_movimiento)` como hace
  `TreasuryService.resolveDescription`.
* Motivo: el par `(tabla_origen, id_origen)` es una referencia polimorfica a las tablas
  `VENTAS`, `COMPRAS`, `RECAUDOS`, `PAGOS`, `GASTOS_OPERATIVOS`, `INGRESOS_OCASIONALES` y
  `TRANSFERENCIAS` (de esas, `TRANSFERENCIAS`, `VENTAS`, `RECAUDOS` e
  `INGRESOS_OCASIONALES` ya existen en ServiBox, cada una como su propia relacion opcional
  en `Movement`). Cuando se escribio esta entrada ninguno de esos modulos existia: portarla
  entonces habria sido
  copiar una clave foranea sin integridad referencial que apunta a tablas ausentes, y un
  `switch` sobre nombres de tabla en `String`. Una columna `concept` da la misma
  informacion al usuario, es lo que el `POST /api/treasury/movements` necesita hoy, y no
  bloquea agregar el origen despues cuando existan Sales y Purchases. El enum en `type`
  sigue el mismo criterio que `Role` en `User`: `"Ingreso"` como `String` libre admite
  `"ingreso"`, `"INGRESO"` y typos silenciosos en una columna de la que depende el signo
  con el que se mueve la plata.

## 2026-09-07: La transferencia no genera movimientos automaticos (RESUELTO el mismo dia)

> **RESUELTO.** Se implemento la generacion de los dos movimientos, con una relacion
> `Movement.sourceTransfer` que Autollantas no tiene. Ver la entrada "La transferencia
> genera sus dos movimientos" mas arriba. La entrada original se conserva por el
> razonamiento.


* Decision: `registrarTransferencia` guarda el `Transfer` y mueve los dos saldos, y nada
  mas. Autollantas ademas crea dos `Movement`, un `"Egreso"` en el origen y un
  `"Ingreso"` en el destino, marcados con `sourceTable = "TRANSFERENCIAS"`.
* Alternativas consideradas: portar tambien la generacion de los dos movimientos.
* Motivo: se implemento el alcance pedido para esta migracion, que define la transferencia
  como Transfer mas los dos saldos. **Es una diferencia funcional real, no cosmetica:**
  hoy `GET /api/treasury/accounts/{id}/movements` no muestra las transferencias, asi que
  el extracto de una cuenta no explica todos sus cambios de saldo, mientras que en
  Autollantas si. El dato no se pierde (queda en `TRANSFERENCIAS`, y
  `findTransfersByAccountId` lo consulta), pero el ledger unificado que Autollantas arma
  con `UnifiedMovementRow` todavia no existe aqui. Cuando se necesite, la salida es
  generar los dos movimientos dentro de la misma transaccion, igual que Autollantas.

## 2026-09-07: Notion estaba al dia para Accounts, al reves que con Inventory

* Decision: se migro segun el codigo de Autollantas, y se confirmo que la pagina de Notion
  "Module - Accounts" coincide con el.
* Motivo: se dejo escrito porque con Inventory paso lo contrario y conviene no asumir la
  misma regla las dos veces. El paquete `treasury` de Autollantas no se toca desde el
  commit `8b29f7e` del 2026-08-04 (`fix/61-complete-module-reports-test`), y la pagina de
  Notion se edito el 2026-08-12, o sea que **la documentacion es mas nueva que el codigo**,
  al reves que en Inventory. Se contrastaron igual las dos fuentes campo por campo y no hay
  conflicto: Notion describe correctamente `Account` (los cinco campos y sus columnas), las
  dos cuentas semilla con saldo 0, el `type` de `MOVIMIENTOS` como `"Ingreso"` / `"Egreso"`
  y el `lblTotalGlobal` como suma de saldos. El unico punto que se presta a confusion es
  que Notion describe una columna "Concept" en la tabla de movimientos: esa columna de la
  vista **no** es un campo de `MOVIMIENTOS`, es el resultado de
  `TreasuryService.resolveDescription` sobre `(tabla_origen, id_origen)`. Verificado contra
  el codigo, ver la decision sobre `Movement` en este mismo archivo.

## 2026-08-28: taxAmount solo suma los impuestos marcados como IVA

* Decision: en `InventoryService.recalculatePrices`, `taxAmount` es
  `purchaseCost * suma(rates de la categoria con isVat true)`. Si ningun impuesto de la
  categoria tiene `isVat`, `taxAmount` es 0. El resto del metodo, incluido todo el calculo
  de `suggestedPrice`, se porto sin tocar.
* Alternativas consideradas: copiar Autollantas tal cual, donde `taxAmount` suma **todas**
  las rates de la categoria sin mirar `isVat`.
* Motivo: `taxAmount` representa el IVA del producto, que es un impuesto **recuperable**:
  se descuenta contra el IVA cobrado en ventas. Las retenciones tipo ReteICA no son
  recuperables, son un costo. Sumarlas dentro de `taxAmount` mezcla dos cosas que
  contablemente van a cuentas distintas, e infla el IVA declarable. En una categoria con
  IVA 19 por ciento mas ReteICA 3 por ciento sobre un costo de 25000, Autollantas reporta
  5500 de IVA cuando el IVA real es 4750. El campo `isVat` ya existe en `TaxType`
  justamente para hacer esa distincion, solo que el calculo no lo estaba usando.
* Nota sobre el estado real del codigo: la documentacion previa describia este calculo
  como un `0.19` hardcodeado dentro de un metodo `recalculateMinSalePrice`. Eso ya no es
  cierto. Autollantas hoy suma las rates de la categoria (el hardcode desaparecio), el
  metodo se llama `recalculatePrices`, y `minSalePrice` no existe. La divergencia que
  introduce ServiBox es unicamente el filtro por `isVat`.

## 2026-08-28: Verificar el estado del usuario en cada request

* Decision: `JwtAuthenticationFilter` consulta `USUARIOS` en cada request autenticado y
  solo autentica si el usuario todavia existe y esta activo. La authority tambien sale del
  rol en base, no del claim del token.
* Alternativas consideradas: confiar solo en la firma y la expiracion del token, que es lo
  que hace un JWT puro sin estado; o bajar la expiracion a minutos con refresh tokens; o
  mantener una lista de tokens revocados.
* Motivo: revocar un acceso tiene que ser inmediato. Con solo la expiracion, despedir a un
  empleado o detectar una cuenta comprometida deja la puerta abierta hasta 24 horas, y en
  un sistema multi-tenant hosteado ese es exactamente el momento en que el acceso importa.
  Bajar la expiracion con refresh tokens reduce la ventana pero no la cierra, y agrega
  toda la maquinaria de refresh. Una lista de revocados es estado igual, con la desventaja
  de que hay que acordarse de poblarla. Consultar el usuario es estado que ya existe y no
  puede desincronizarse: si la fila dice inactivo, el acceso se corta en el siguiente
  request. El costo es una consulta por request protegido, ver la nota de costo en
  [01-ARCHITECTURE.md](01-ARCHITECTURE.md).

## 2026-08-28: El tenant viaja en el JWT, no en una cabecera

* Decision: `TenantContext` se puebla desde el claim `tenantId` de un JWT firmado, en
  `JwtAuthenticationFilter`. Se elimino la cabecera `X-Tenant-Id` y la clase
  `TenantFilter` que la leia.
* Alternativas consideradas: mantener la cabecera junto al JWT, por comodidad al probar
  con curl o Postman; o resolver el tenant por subdominio.
* Motivo: la cabecera era andamiaje explicito mientras no habia login, y como valor en
  claro que manda el cliente, cualquiera con un token valido podia cambiar el numero y
  leer datos de otro tenant. El claim va dentro de la firma: alterarlo invalida el token.
  Mantener las dos vias en paralelo habria dejado justamente el agujero que el JWT viene a
  cerrar, porque bastaria con usar la mas debil. Subdominio queda para mas adelante, si
  hace falta, pero no cambia de donde sale la autoridad del dato.

## 2026-08-28: Rol como enum en la entidad, no como tabla Role

* Decision: `User.role` es un enum `Role` (`ADMIN`, `EMPLEADO`) persistido con
  `@Enumerated(STRING)`, no una tabla de roles con su relacion.
* Alternativas consideradas: tabla `ROLES` con relacion muchos a muchos y permisos por
  rol, el esquema clasico de Spring Security.
* Motivo: hoy son dos roles y no hay ningun caso de negocio que pida un tercero ni
  permisos por rol configurables por cliente. Una tabla agregaria dos joins, una pantalla
  de administracion y datos semilla por tenant, todo para representar dos valores fijos
  que ademas viven en el codigo de las autoridades de Spring. Si aparece la necesidad real
  de roles por tenant, migrar de enum a tabla es una migracion acotada; empezar por la
  tabla es complejidad que se paga desde el primer dia. `STRING` y no `ORDINAL` para que
  reordenar el enum no corrompa las filas existentes.

## 2026-08-28: Auditoria e identificadores propios, divergiendo de Autollantas

* Decision: `TenantAwareEntity` lleva `createdAt` y `updatedAt` (`@CreatedDate` /
  `@LastModifiedDate` con `AuditingEntityListener`, activados por `@EnableJpaAuditing`), y
  todos los identificadores del modelo son `Long`. Autollantas no tiene ningun campo de
  auditoria y usa `Integer` en sus entidades.
* Alternativas consideradas: copiar tal cual la convencion de Autollantas (`Integer`, sin
  auditoria) para que ambos proyectos se lean igual; o agregar las fechas a mano en cada
  entidad con `@PrePersist` y `@PreUpdate`.
* Motivo: Autollantas es una aplicacion de escritorio de un solo negocio, donde un dato
  raro se le pregunta al unico usuario que estuvo en la maquina. ServiBox es multi-tenant
  y hosteado: varios clientes sobre la misma base, sin acceso fisico al equipo. Ante un
  reclamo de "esto se cambio solo" o una fila con el `tenant_id` equivocado, `createdAt` y
  `updatedAt` son la unica forma de reconstruir que paso y cuando. Que sea automatico por
  listener y no manual evita el olvido en la entidad numero veinte. `Long` porque el
  volumen agregado de todos los tenants ya no es el de un solo negocio, y porque unifica
  con `Tenant.id`, que es `Long` por ser la referencia de `tenant_id`.

## 2026-08-28: Aislamiento multi-tenant por columna discriminadora

* Decision: un solo esquema con columna `tenant_id` en cada tabla de negocio, y filtro de
  Hibernate (`@FilterDef` / `@Filter` en `TenantAwareEntity`) habilitado por sesion a
  partir de `TenantContext`. Implementacion en
  [01-ARCHITECTURE.md](01-ARCHITECTURE.md).
* Alternativas consideradas: schema-per-tenant, es decir un esquema de base de datos
  independiente por cada inquilino, con enrutamiento del `DataSource` por request.
* Motivo: simplicidad operativa. Con schema-per-tenant cada migracion hay que correrla N
  veces, dar de alta un cliente implica crear y versionar un esquema nuevo, y los backups
  y el monitoreo se multiplican. La columna discriminadora deja una sola migracion, un
  solo pool de conexiones y un alta de tenant que es un INSERT. Ya venia definido asi
  desde la fase de formulacion academica del proyecto.
