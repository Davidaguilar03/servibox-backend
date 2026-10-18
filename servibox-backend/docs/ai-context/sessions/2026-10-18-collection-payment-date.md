# 2026-10-18: Zona de negocio y fecha de abonos y pagos

Rama `fix/24-collection-payment-date`. Objetivo: que abonos y pagos acepten una fecha y que
todo "hoy" de negocio use la zona del taller en vez de la del servidor.

## Lo que habia

Usos de "ahora" en `src/main`:

| Archivo | Uso | Clase |
|-|-|-|
| `sales/service/SalesService.java` | fecha de la venta si no viene | negocio |
| `sales/service/SalesService.java` | fecha del abono (`registrarAbono`) | negocio |
| `purchases/service/PurchasesService.java` | fecha de la compra si no viene | negocio |
| `purchases/service/PurchasesService.java` | fecha del pago (`registrarPago`) | negocio |
| `treasury/service/TreasuryService.java` | fecha de un movimiento sin fecha (`aplicarMovimiento`) | negocio |
| `config/JpaAuditingConfiguration.java` | `created_at`, `updated_at` en UTC | auditoria |
| `tenant/Tenant.java` | `fecha_creacion_tenant` en UTC | auditoria |
| `auth/JwtService.java` | emision y expiracion del token (`new Date()`) | instante tecnico |

Todos los de negocio eran `LocalDate.now()` con la zona de la JVM. No hay periodos por
defecto en reportes ni vencimientos calculados: no usan "ahora".

* Abono y pago: `CollectionRequest` y `PaymentRequest` solo tenian `accountId` y `amount`
  (`@NotNull`). El movimiento se fechaba con `LocalDate.now()`. El monto: mayor a cero
  (400), no mas que el saldo pendiente con la tolerancia de un peso (400), y la factura
  tiene que estar PENDIENTE (400). En el pago, ademas, saldo suficiente en la cuenta (409).
* Autollantas: `CollectionFormController` y `PaymentFormController` proponen hoy en el
  `DatePicker` y no validan la fecha.

## Lo que se hizo

* Bean `Clock` de negocio con `servibox.business.time-zone`
  (`${BUSINESS_TIME_ZONE:America/Bogota}`); los cinco usos de negocio lo leen.
* `date` opcional en abonos y pagos; futura es 400.
* `registrarAbono` y `registrarPago` reciben la fecha: las 21 llamadas de las pruebas
  existentes pasan `null` (mismo comportamiento que antes: hoy).
* `OperationalExpenseTest` y `TreasuryMovementsTest` comparaban la fecha por defecto con
  `LocalDate.now()` del sistema; ahora con `LocalDate.now(clock)`, porque con la JVM en UTC
  fallaban entre las 19:00 y las 24:00 de Bogota. Las demas pruebas pasan fechas
  explicitas o ventanas que siguen conteniendo el dia de negocio.
* Sin cambios de esquema ni en `MovementResponse`.
* Decisiones: [03-DECISIONS.md](../03-DECISIONS.md), entrada "Zona horaria de negocio y
  fecha de abonos y pagos".

## Verificacion

* Tests: 219 antes, 227 despues. Nuevas `treasury.CollectionPaymentDateTest` (6) y
  `treasury.BusinessClockZoneTest` (2). Se comprobo que la segunda falla si el reloj fijo
  usa UTC en vez de la zona de negocio (2026-10-19 en vez de 2026-10-18), y se restauro.
* Prueba real en perfil dev con `-Duser.timezone=UTC`: login, producto, venta a credito,
  abono sin fecha y con fecha pasada (201), con fecha futura (400), compra a credito y sus
  pagos sin fecha y con fecha pasada (201). Esa prueba se hizo cuando la fecha de hoy era la
  misma en UTC y en Bogota, asi que no distingue las zonas; la que lo demuestra es
  `BusinessClockZoneTest`.
