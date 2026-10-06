# Modelo de datos objetivo

Fuente: Diccionario de Datos de ServiBox (informe UPTC-EISC-SB-01) y su diagrama entidad-relacion, 14 entidades en tercera forma normal. Este archivo es la especificacion del esquema al que debe converger el codigo.

**Estado.** Objetivo del refactor de normalizacion. El codigo todavia tiene el modelo anterior de 19 entidades hasta que termine ese refactor. Donde el codigo y este archivo difieran, este archivo manda, salvo lo anotado en "Decisiones de implementacion" y "Pendientes" al final.

## Reglas generales

* Multi-tenant: base compartida y esquema compartido. La columna `id_tenant` esta en todas las tablas excepto `TENANTS` y `CATEGORIA_IMPUESTOS`. En la capa JPA el aislamiento lo da el `@Filter` de Hibernate.
* Unicos compuestos con `id_tenant`: `(id_tenant, username)` en USUARIOS, `(id_tenant, numero_documento)` en TERCEROS, `(id_tenant, numero_factura_venta)` en VENTAS, `(id_tenant, codigo_producto)` en PRODUCTOS.
* Motor: PostgreSQL 16. Tipos fisicos: identificadores `INTEGER`; texto `VARCHAR(n)`; montos en COP `NUMERIC(14,2)`; porcentajes `NUMERIC(5,2)`; fechas `TIMESTAMP WITHOUT TIME ZONE` (UTC); banderas `BOOLEAN`.
* Integridad referencial: `ON DELETE RESTRICT` en las entidades maestras (cuentas, terceros, productos): nada con historial transaccional se elimina.
* Normalizacion aplicada: (1) `MOVIMIENTOS` unifica gastos operativos, ingresos ocasionales, transferencias, pagos de compras y recaudos de ventas; `tipo_movimiento` da el flujo y `categoria_movimiento` el concepto. (2) `TERCEROS` unifica clientes y proveedores; `tipo_tercero` vale CLIENTE, PROVEEDOR o AMBOS.
* Consecuencia de (1): ya no existen las tablas de abonos, pagos, transferencias, ingresos ocasionales ni gastos operativos. Tampoco `VENTAS` ni `COMPRAS` guardan la cuenta: la cuenta de un cobro o pago vive en el movimiento.

## Entidades

### TENANTS

Modulo: Multi-tenancy y administracion.

| Campo | Tipo Dato | Longitud | Nulidad | Clave | Descripcion Tecnica y Regla de Negocio |
|-|-|-|-|-|-|
| id_tenant | INTEGER (I) | 4 bytes | NOT NULL | PK | Identificador unico de la empresa o taller registrado. |
| nombre_tenant | VARCHAR | 150 | NOT NULL | - | Razon social o denominacion comercial de la serviteca. |
| slug_tenant | VARCHAR | 100 | NOT NULL | UK | Identificador alfanumerico unico para subdominio web y enrutamiento. |
| activo_tenant | BOOLEAN | 1 byte | NOT NULL | - | Estado de la cuenta: true para activa, false para suspendida. |
| fecha_creacion_tenant | TIMESTAMP (D) | 8 bytes | NOT NULL | - | Fecha y hora de registro de la suscripcion (UTC). |

### USUARIOS

Modulo: Multi-tenancy y administracion.

| Campo | Tipo Dato | Longitud | Nulidad | Clave | Descripcion Tecnica y Regla de Negocio |
|-|-|-|-|-|-|
| id_usuario | INTEGER (I) | 4 bytes | NOT NULL | PK | Identificador secuencial del usuario. |
| id_tenant | INTEGER (I) | 4 bytes | NOT NULL | FK | FK a TENANTS(id_tenant). Aislamiento del usuario por taller. |
| username | VARCHAR | 50 | NOT NULL | UK | Nombre de usuario unico por taller (UK compuesta con id_tenant). |
| password_hash | VARCHAR | 255 | NOT NULL | - | Hash criptografico de la contrasena calculado con BCrypt. |
| email | VARCHAR | 150 | NULL | - | Direccion de correo electronico del usuario para soporte y notificaciones. |
| activo | BOOLEAN | 1 byte | NOT NULL | - | Indica si la cuenta esta habilitada para autenticacion e inicio de sesion. |
| rol | VARCHAR | 30 | NOT NULL | - | Rol RBAC asignado para autorizacion de funciones (ADMIN, MECANICO, CAJERO). |
| created_at | TIMESTAMP (D) | 8 bytes | NOT NULL | - | Fecha y hora de registro del usuario (UTC). |
| updated_at | TIMESTAMP (D) | 8 bytes | NOT NULL | - | Fecha y hora de ultima modificacion en credenciales o perfil (UTC). |

### CONFIGURACION_FINANCIERA

Modulo: Multi-tenancy y administracion.

| Campo | Tipo Dato | Longitud | Nulidad | Clave | Descripcion Tecnica y Regla de Negocio |
|-|-|-|-|-|-|
| id_configuracion | INTEGER (I) | 4 bytes | NOT NULL | PK | Identificador unico de la configuracion financiera. |
| id_tenant | INTEGER (I) | 4 bytes | NOT NULL | FK, UK | FK a TENANTS(id_tenant). Relacion 1:1 estricta por taller. |
| porcentaje_gastos | NUMERIC | 5,2 | NOT NULL | - | Porcentaje de provision estimada para gastos operativos (0.00 a 100.00). |
| porcentaje_dian | NUMERIC | 5,2 | NOT NULL | - | Porcentaje de reserva para obligaciones fiscales de la DIAN. |
| porcentaje_ica | NUMERIC | 5,2 | NOT NULL | - | Porcentaje de provision para el Impuesto de Industria y Comercio municipal. |
| porcentaje_comision_tarjeta | NUMERIC | 5,2 | NOT NULL | - | Porcentaje de comision deducido por datafonos o pasarelas. |
| created_at | TIMESTAMP (D) | 8 bytes | NOT NULL | - | Fecha y hora de creacion de la parametrizacion contable (UTC). |
| updated_at | TIMESTAMP (D) | 8 bytes | NOT NULL | - | Fecha y hora de ultima modificacion de porcentajes (UTC). |

### CUENTAS

Modulo: Tesoreria.

| Campo | Tipo Dato | Longitud | Nulidad | Clave | Descripcion Tecnica y Regla de Negocio |
|-|-|-|-|-|-|
| id_cuenta | INTEGER (I) | 4 bytes | NOT NULL | PK | Identificador unico de la cuenta de tesoreria. |
| id_tenant | INTEGER (I) | 4 bytes | NOT NULL | FK | FK a TENANTS(id_tenant). Aislamiento de la cuenta por taller. |
| nombre_cuenta | VARCHAR | 100 | NOT NULL | - | Nombre descriptivo de la cuenta (Caja General, Bancolombia, Nequi). |
| balance_inicial | NUMERIC | 14,2 | NOT NULL | - | Saldo de apertura al registrar la cuenta en el sistema (en COP). |
| saldo_actual | NUMERIC | 14,2 | NOT NULL | - | Saldo disponible en tiempo real, recalculado por movimientos de fondos. |
| tipo_cuenta | VARCHAR | 30 | NOT NULL | - | Tipo de cuenta financiera (EFECTIVO, BANCO, DIGITAL). |
| created_at | TIMESTAMP (D) | 8 bytes | NOT NULL | - | Fecha y hora de registro de la cuenta (UTC). |
| updated_at | TIMESTAMP (D) | 8 bytes | NOT NULL | - | Fecha y hora de ultima afectacion de saldo (UTC). |

### MOVIMIENTOS

Modulo: Tesoreria.

| Campo | Tipo Dato | Longitud | Nulidad | Clave | Descripcion Tecnica y Regla de Negocio |
|-|-|-|-|-|-|
| id_movimiento | INTEGER (I) | 4 bytes | NOT NULL | PK | Identificador unico del registro en el libro mayor de tesoreria. |
| id_tenant | INTEGER (I) | 4 bytes | NOT NULL | FK | FK a TENANTS(id_tenant). |
| id_cuenta | INTEGER (I) | 4 bytes | NOT NULL | FK | FK a CUENTAS(id_cuenta). Cuenta financiera afectada en la transaccion. |
| id_movimiento_relacionado | INTEGER (I) | 4 bytes | NULL | FK | FK autoreferencial a MOVIMIENTOS(id_movimiento). Vincula la contrapartida contable en transferencias internas entre cuentas. |
| id_compra | INTEGER (I) | 4 bytes | NULL | FK | FK a COMPRAS(id_compra). Vincula abonos y pagos a facturas de compra. |
| id_venta | INTEGER (I) | 4 bytes | NULL | FK | FK a VENTAS(id_venta). Vincula abonos y recaudos de facturas de venta. |
| tipo_movimiento | VARCHAR | 20 | NOT NULL | - | Direccion contable del flujo financiero (INGRESO, EGRESO, TRANSFERENCIA). |
| categoria_movimiento | VARCHAR | 50 | NOT NULL | - | Categoria tecnica (GASTO_OPERATIVO, INGRESO_OCASIONAL, TRANSFERENCIA, PAGO_COMPRA, RECAUDO_VENTA). |
| monto | NUMERIC | 14,2 | NOT NULL | - | Valor monetario de la transaccion en pesos colombianos (monto > 0). |
| fecha_movimiento | TIMESTAMP (D) | 8 bytes | NOT NULL | - | Fecha y hora efectiva de la operacion financiera. |
| concepto | VARCHAR | 255 | NOT NULL | - | Descripcion detallada que justifica contablemente la transaccion. |
| notas | VARCHAR | 255 | NULL | - | Observaciones u anotaciones complementarias de auditoria. |
| created_at | TIMESTAMP (D) | 8 bytes | NOT NULL | - | Fecha y hora de persistencia del movimiento (UTC). |
| updated_at | TIMESTAMP (D) | 8 bytes | NOT NULL | - | Fecha y hora de ultima modificacion (UTC). |

### TERCEROS

Modulo: Comercial.

| Campo | Tipo Dato | Longitud | Nulidad | Clave | Descripcion Tecnica y Regla de Negocio |
|-|-|-|-|-|-|
| id_tercero | INTEGER (I) | 4 bytes | NOT NULL | PK | Identificador unico de la persona natural o juridica en el sistema. |
| id_tenant | INTEGER (I) | 4 bytes | NOT NULL | FK | FK a TENANTS(id_tenant). Directorio comercial aislado por taller. |
| tipo_tercero | VARCHAR | 20 | NOT NULL | - | Rol comercial del actor en el taller (CLIENTE, PROVEEDOR, AMBOS). |
| nombre_razon_social | VARCHAR | 150 | NOT NULL | - | Nombre completo del cliente o razon social del proveedor. |
| tipo_documento | VARCHAR | 20 | NOT NULL | - | Tipo de identificacion fiscal (CC, NIT, CE, RUT, PASAPORTE). |
| numero_documento | VARCHAR | 25 | NOT NULL | UK | Numero de documento de identidad o NIT fiscal (UK con id_tenant). |
| correo | VARCHAR | 150 | NULL | - | Direccion de correo electronico para notificaciones y facturacion. |
| celular | VARCHAR | 25 | NULL | - | Telefono movil de contacto comercial. |
| created_at | TIMESTAMP (D) | 8 bytes | NOT NULL | - | Fecha y hora de registro del tercero (UTC). |
| updated_at | TIMESTAMP (D) | 8 bytes | NOT NULL | - | Fecha y hora de ultima modificacion del contacto (UTC). |

### COMPRAS

Modulo: Comercial.

| Campo | Tipo Dato | Longitud | Nulidad | Clave | Descripcion Tecnica y Regla de Negocio |
|-|-|-|-|-|-|
| id_compra | INTEGER (I) | 4 bytes | NOT NULL | PK | Identificador unico de la factura de adquisicion de mercancia. |
| id_tenant | INTEGER (I) | 4 bytes | NOT NULL | FK | FK a TENANTS(id_tenant). |
| id_tercero | INTEGER (I) | 4 bytes | NOT NULL | FK | FK a TERCEROS(id_tercero). Proveedor que expide la factura. |
| numero_factura_compra | VARCHAR | 50 | NOT NULL | - | Consecutivo oficial de la factura fisica o electronica del proveedor. |
| fecha_compra | TIMESTAMP (D) | 8 bytes | NOT NULL | - | Fecha de expedicion formal de la factura de compra. |
| fecha_vencimiento_compra | TIMESTAMP (D) | 8 bytes | NULL | - | Fecha limite de pago acordada para compras a credito comercial. |
| forma_pago_compra | VARCHAR | 20 | NOT NULL | - | Modalidad de pago comercial: CONTADO o CREDITO. |
| medio_pago_compra | VARCHAR | 30 | NULL | - | Instrumento de desembolso: EFECTIVO, TRANSFERENCIA o CHEQUE. |
| estado_compra | VARCHAR | 20 | NOT NULL | - | Estado de cartera del comprobante: PENDIENTE, PAGADA o ANULADA. |
| subtotal_compra | NUMERIC | 14,2 | NOT NULL | - | Base gravable acumulada antes de impuestos en COP. |
| iva_total_compra | NUMERIC | 14,2 | NOT NULL | - | Monto total del IVA descontable generado en la transaccion. |
| total_compra | NUMERIC | 14,2 | NOT NULL | - | Total final a pagar al proveedor en COP (subtotal + iva_total). |
| created_at | TIMESTAMP (D) | 8 bytes | NOT NULL | - | Fecha y hora de insercion para auditoria (UTC). |
| updated_at | TIMESTAMP (D) | 8 bytes | NOT NULL | - | Fecha y hora de ultima actualizacion (UTC). |

### DETALLE_COMPRAS

Modulo: Comercial.

| Campo | Tipo Dato | Longitud | Nulidad | Clave | Descripcion Tecnica y Regla de Negocio |
|-|-|-|-|-|-|
| id_detalle_compra | INTEGER (I) | 4 bytes | NOT NULL | PK | Identificador unico de la linea de item en la factura de compra. |
| id_tenant | INTEGER (I) | 4 bytes | NOT NULL | FK | FK a TENANTS(id_tenant). |
| id_compra | INTEGER (I) | 4 bytes | NOT NULL | FK | FK a COMPRAS(id_compra). Factura a la que pertenece el renglon. |
| id_producto | INTEGER (I) | 4 bytes | NOT NULL | FK | FK a PRODUCTOS(id_producto). Repuesto ingresado al inventario. |
| cantidad_compra | INTEGER (I) | 4 bytes | NOT NULL | - | Cantidad de unidades fisicas adquiridas (valor entero > 0). |
| precio_compra | NUMERIC | 14,2 | NOT NULL | - | Costo unitario de compra antes de impuestos en COP. |
| iva_linea_compra | NUMERIC | 14,2 | NOT NULL | - | Monto de IVA correspondiente a la totalidad de unidades del renglon. |
| created_at | TIMESTAMP (D) | 8 bytes | NOT NULL | - | Fecha y hora de creacion del renglon (UTC). |
| updated_at | TIMESTAMP (D) | 8 bytes | NOT NULL | - | Fecha y hora de ultima modificacion (UTC). |

### VENTAS

Modulo: Comercial.

| Campo | Tipo Dato | Longitud | Nulidad | Clave | Descripcion Tecnica y Regla de Negocio |
|-|-|-|-|-|-|
| id_venta | INTEGER (I) | 4 bytes | NOT NULL | PK | Identificador unico de la transaccion mercantil de venta. |
| id_tenant | INTEGER (I) | 4 bytes | NOT NULL | FK | FK a TENANTS(id_tenant). |
| id_tercero | INTEGER (I) | 4 bytes | NOT NULL | FK | FK a TERCEROS(id_tercero). Cliente receptor del servicio o repuesto. |
| numero_factura_venta | VARCHAR | 50 | NOT NULL | UK | Consecutivo de factura expedido al cliente (UK compuesta con id_tenant). |
| fecha_venta | TIMESTAMP (D) | 8 bytes | NOT NULL | - | Fecha y hora de expedicion formal de la factura. |
| fecha_vencimiento_venta | TIMESTAMP (D) | 8 bytes | NULL | - | Fecha limite para cancelacion de ventas a credito comercial. |
| forma_pago_venta | VARCHAR | 20 | NOT NULL | - | Modalidad de cobro comercial: CONTADO o CREDITO. |
| medio_pago_venta | VARCHAR | 30 | NULL | - | Instrumento financiero: EFECTIVO, TARJETA_DEBITO, TARJETA_CREDITO, TRANSFERENCIA. |
| estado_venta | VARCHAR | 20 | NOT NULL | - | Estado operativo de la factura: PAGADA, PENDIENTE o ANULADA. |
| subtotal_venta | NUMERIC | 14,2 | NOT NULL | - | Base gravable de la venta antes de IVA en COP. |
| iva_por_pagar_venta | NUMERIC | 14,2 | NOT NULL | - | Monto total de IVA generado fiscalmente a declarar ante la DIAN. |
| total_venta | NUMERIC | 14,2 | NOT NULL | - | Total final cobrado al cliente en COP (subtotal_venta + iva_por_pagar_venta). |
| created_at | TIMESTAMP (D) | 8 bytes | NOT NULL | - | Fecha y hora de insercion para auditoria (UTC). |
| updated_at | TIMESTAMP (D) | 8 bytes | NOT NULL | - | Fecha y hora de ultima actualizacion (UTC). |

### DETALLE_VENTAS

Modulo: Comercial.

| Campo | Tipo Dato | Longitud | Nulidad | Clave | Descripcion Tecnica y Regla de Negocio |
|-|-|-|-|-|-|
| id_detalle_venta | INTEGER (I) | 4 bytes | NOT NULL | PK | Identificador unico de la linea de item en la factura de venta. |
| id_tenant | INTEGER (I) | 4 bytes | NOT NULL | FK | FK a TENANTS(id_tenant). |
| id_venta | INTEGER (I) | 4 bytes | NOT NULL | FK | FK a VENTAS(id_venta). Factura que agrupa esta linea transaccional. |
| id_producto | INTEGER (I) | 4 bytes | NOT NULL | FK | FK a PRODUCTOS(id_producto). Repuesto o servicio facturado. |
| cantidad_venta | INTEGER (I) | 4 bytes | NOT NULL | - | Unidades fisicas despachadas o servicios aplicados (> 0). |
| precio_venta | NUMERIC | 14,2 | NOT NULL | - | Precio de venta unitario final cobrado al cliente en COP. |
| iva_generado_linea | NUMERIC | 14,2 | NOT NULL | - | Impuesto al valor agregado derivado del renglon (base * tarifa). |
| diferencia_iva_linea | NUMERIC | 14,2 | NOT NULL | - | Diferencia fiscal entre el IVA generado y el IVA asumido en compra. |
| created_at | TIMESTAMP (D) | 8 bytes | NOT NULL | - | Fecha y hora de persistencia del renglon (UTC). |
| updated_at | TIMESTAMP (D) | 8 bytes | NOT NULL | - | Fecha y hora de ultima modificacion (UTC). |

### PRODUCTOS

Modulo: Inventario.

| Campo | Tipo Dato | Longitud | Nulidad | Clave | Descripcion Tecnica y Regla de Negocio |
|-|-|-|-|-|-|
| id_producto | INTEGER (I) | 4 bytes | NOT NULL | PK | Identificador unico del producto o repuesto en el catalogo. |
| id_tenant | INTEGER (I) | 4 bytes | NOT NULL | FK | FK a TENANTS(id_tenant). |
| id_categoria_producto | INTEGER (I) | 4 bytes | NOT NULL | FK | FK a CATEGORIA_PRODUCTOS(id_categoria_producto). |
| codigo_producto | VARCHAR | 50 | NOT NULL | UK | Codigo de barras o SKU del repuesto (UK compuesta con id_tenant). |
| descripcion_producto | VARCHAR | 255 | NOT NULL | - | Nombre y especificaciones tecnicas del repuesto o servicio. |
| costo_compra | NUMERIC | 14,2 | NOT NULL | - | Ultimo costo neto o promedio ponderado de compra en COP. |
| cantidad | INTEGER (I) | 4 bytes | NOT NULL | - | Saldo de existencias fisicas disponibles en almacen o bodega. |
| iva_producto | NUMERIC | 5,2 | NOT NULL | - | Tarifa nominal de IVA aplicable al item (19.00, 5.00, 0.00). |
| precio_sugerido | NUMERIC | 14,2 | NOT NULL | - | Precio de venta recomendado calculado a partir de costo y margen. |
| created_at | TIMESTAMP (D) | 8 bytes | NOT NULL | - | Fecha y hora de registro del producto en el catalogo (UTC). |
| updated_at | TIMESTAMP (D) | 8 bytes | NOT NULL | - | Fecha y hora de modificacion de existencias o precios (UTC). |

### CATEGORIA_PRODUCTOS

Modulo: Inventario.

| Campo | Tipo Dato | Longitud | Nulidad | Clave | Descripcion Tecnica y Regla de Negocio |
|-|-|-|-|-|-|
| id_categoria_producto | INTEGER (I) | 4 bytes | NOT NULL | PK | Identificador unico de la categoria o familia de productos. |
| id_tenant | INTEGER (I) | 4 bytes | NOT NULL | FK | FK a TENANTS(id_tenant). |
| nombre_categoria_producto | VARCHAR | 100 | NOT NULL | - | Denominacion de la familia (Llantas, Lubricantes, Baterias, Alineacion). |
| color_categoria | VARCHAR | 20 | NOT NULL | - | Codigo hexadecimal (#RRGGBB) para diferenciacion en la interfaz grafica. |
| stock_min_amarillo | INTEGER (I) | 4 bytes | NOT NULL | - | Umbral preventivo de existencias minimas para senalizacion de advertencia. |
| stock_min_rojo | INTEGER (I) | 4 bytes | NOT NULL | - | Umbral critico de stock que exige reposicion obligatoria. |
| margen_utilidad | NUMERIC | 5,2 | NOT NULL | - | Porcentaje de margen sugerido aplicado sobre el costo de adquisicion. |
| created_at | TIMESTAMP (D) | 8 bytes | NOT NULL | - | Fecha y hora de creacion de la categoria (UTC). |
| updated_at | TIMESTAMP (D) | 8 bytes | NOT NULL | - | Fecha y hora de ultima modificacion de parametros (UTC). |

### TIPOS_IMPUESTO

Modulo: Tributario.

| Campo | Tipo Dato | Longitud | Nulidad | Clave | Descripcion Tecnica y Regla de Negocio |
|-|-|-|-|-|-|
| id_impuesto | INTEGER (I) | 4 bytes | NOT NULL | PK | Identificador unico del tributo o gravamen fiscal. |
| id_tenant | INTEGER (I) | 4 bytes | NOT NULL | FK | FK a TENANTS(id_tenant). |
| nombre_impuesto | VARCHAR | 100 | NOT NULL | - | Denominacion legal del gravamen (IVA General 19%, IVA 5%, Retefuente). |
| tasa_impuesto | NUMERIC | 5,2 | NOT NULL | - | Porcentaje impositivo nominal a aplicar sobre la base gravable. |
| descripcion_impuesto | VARCHAR | 255 | NULL | - | Fundamentacion normativa segun el Estatuto Tributario colombiano. |
| aplica_transaccion | BOOLEAN | 1 byte | NOT NULL | - | Indica si el tributo se liquida de forma automatica en ventas. |
| es_iva | BOOLEAN | 1 byte | NOT NULL | - | Bandera que indica si el gravamen constituye IVA para efectos del balance fiscal. |
| created_at | TIMESTAMP (D) | 8 bytes | NOT NULL | - | Fecha y hora de registro del tributo (UTC). |
| updated_at | TIMESTAMP (D) | 8 bytes | NOT NULL | - | Fecha y hora de actualizacion de la tasa impositiva (UTC). |

### CATEGORIA_IMPUESTOS

Modulo: Tributario.

| Campo | Tipo Dato | Longitud | Nulidad | Clave | Descripcion Tecnica y Regla de Negocio |
|-|-|-|-|-|-|
| id_categoria_producto | INTEGER (I) | 4 bytes | NOT NULL | PK, FK | FK a CATEGORIA_PRODUCTOS(id_categoria_producto). Componente 1 de PK compuesta. |
| id_impuesto | INTEGER (I) | 4 bytes | NOT NULL | PK, FK | FK a TIPOS_IMPUESTO(id_impuesto). Componente 2 de PK compuesta. |

## Claves foraneas

| Tabla Dependiente | Columna FK | Tabla Referenciada | Card. | Regla de Integridad y Semantica |
|-|-|-|-|-|
| USUARIOS | id_tenant | TENANTS | N:1 | Restriccion obligatoria; el usuario debe pertenecer a un inquilino existente. |
| CONFIGURACION_FINANCIERA | id_tenant | TENANTS | 1:1 | Unicidad estricta; cada taller posee una unica parametrizacion contable. |
| CUENTAS | id_tenant | TENANTS | N:1 | Aislamiento de saldos; cada cuenta de fondos pertenece a un taller especifico. |
| MOVIMIENTOS | id_tenant | TENANTS | N:1 | Libro mayor aislado; las partidas del extracto pertenecen a un taller. |
| MOVIMIENTOS | id_cuenta | CUENTAS | N:1 | Cuenta financiera afectada; todo movimiento debita o acredita una cuenta. |
| MOVIMIENTOS | id_movimiento_relacionado | MOVIMIENTOS | N:1 (0..1) | Contrapartida contable; vincula transferencias internas entre cuentas. |
| MOVIMIENTOS | id_compra | COMPRAS | N:1 (0..1) | Obligacion comercial; vincula el desembolso a una factura de compra. |
| MOVIMIENTOS | id_venta | VENTAS | N:1 (0..1) | Derecho de cobro; vincula el recaudo a una factura de venta emitida. |
| TERCEROS | id_tenant | TENANTS | N:1 | Directorio comercial unificado aislado por taller automotriz. |
| COMPRAS | id_tenant | TENANTS | N:1 | Facturacion de adquisiciones restringida al inquilino comprador. |
| COMPRAS | id_tercero | TERCEROS | N:1 | Proveedor formal; la compra debe registrarse a un tercero valido. |
| DETALLE_COMPRAS | id_tenant | TENANTS | N:1 | Linea transaccional de compra aislada por inquilino. |
| DETALLE_COMPRAS | id_compra | COMPRAS | N:1 | Composicion de factura; el item pertenece a una compra activa. |
| DETALLE_COMPRAS | id_producto | PRODUCTOS | N:1 | Recepcion en stock; el producto debe estar registrado en el catalogo. |
| VENTAS | id_tenant | TENANTS | N:1 | Facturacion de servicios y repuestos aislada por inquilino. |
| VENTAS | id_tercero | TERCEROS | N:1 | Cliente formal; la venta debe registrarse a un tercero valido. |
| DETALLE_VENTAS | id_tenant | TENANTS | N:1 | Linea transaccional de venta aislada por inquilino. |
| DETALLE_VENTAS | id_venta | VENTAS | N:1 | Composicion de factura; el item pertenece a una venta activa. |
| DETALLE_VENTAS | id_producto | PRODUCTOS | N:1 | Despacho de inventario; el producto o servicio debe estar en catalogo. |
| PRODUCTOS | id_tenant | TENANTS | N:1 | Catalogo de inventario aislado por taller. |
| PRODUCTOS | id_categoria_producto | CATEGORIA_PRODUCTOS | N:1 | Clasificacion obligatoria; todo repuesto pertenece a una familia. |
| CATEGORIA_PRODUCTOS | id_tenant | TENANTS | N:1 | Familias de repuestos y umbrales de stock aislados por taller. |
| TIPOS_IMPUESTO | id_tenant | TENANTS | N:1 | Estructura tributaria y tarifas parametrizadas por taller. |
| CATEGORIA_IMPUESTOS | id_categoria_producto | CATEGORIA_PRODUCTOS | N:1 | Parte 1 de tabla asociativa; vincula la familia de articulos. |
| CATEGORIA_IMPUESTOS | id_impuesto | TIPOS_IMPUESTO | N:1 | Parte 2 de tabla asociativa; vincula el tributo aplicable. |

## Decisiones de implementacion

Decididas durante el refactor. Cada una se anota tambien en `03-DECISIONS.md`.

* **Direccion del movimiento.** `tipo_movimiento` solo toma INGRESO o EGRESO, porque es lo que da el signo sobre el saldo de la cuenta. TRANSFERENCIA es una `categoria_movimiento`, no un tipo. El diccionario lista TRANSFERENCIA entre los valores de `tipo_movimiento`, pero una transferencia no tiene direccion por si sola.
* **Transferencia.** Son dos movimientos con categoria TRANSFERENCIA: un EGRESO en la cuenta origen y un INGRESO en la cuenta destino. El INGRESO apunta al EGRESO con `id_movimiento_relacionado`.
* **Categorias manuales.** GASTO_OPERATIVO (EGRESO) e INGRESO_OCASIONAL (INGRESO) son los unicos movimientos que el usuario crea, edita y elimina directamente. Los de categoria TRANSFERENCIA, PAGO_COMPRA y RECAUDO_VENTA se gestionan desde su operacion.
* **Terceros.** Una persona o empresa existe una sola vez por tenant, identificada por `numero_documento`. Si ya existe como CLIENTE y se usa como proveedor (o al reves), pasa a AMBOS. Una venta exige un tercero CLIENTE o AMBOS; una compra, PROVEEDOR o AMBOS.
* **Anular y restaurar facturas.** Anular una venta o compra no borra sus movimientos: solo revierte el saldo de cada cuenta y deja la factura en ANULADA. Un movimiento enlazado por `id_venta` o `id_compra` a una factura ANULADA es inactivo: no sale en extractos de cuenta, no cuenta en sumas ni en el saldo pendiente, y no se reporta. Esa condicion se deriva del estado de la factura, no se guarda en una columna. Restaurar reactiva esos movimientos y vuelve a aplicar los saldos; en compras exige saldo suficiente por cada egreso, igual que al crear o pagar. El estado resultante se deriva: contado PAGADA, credito segun el saldo pendiente con la tolerancia de un peso. Invariante: `saldo_actual` es `balance_inicial` mas la suma de los movimientos activos de la cuenta. Los movimientos manuales (GASTO_OPERATIVO e INGRESO_OCASIONAL) no tienen factura y se siguen eliminando de verdad.

## Pendientes

Decisiones que se toman en los pasos 18 y 19 del refactor, cuando haya que resolver el esquema fisico:

* Semantica de `iva_producto`: el diccionario lo describe como tarifa, pero el codigo guarda un monto en pesos por unidad.
* Roles de usuario (el diccionario dice ADMIN, MECANICO, CAJERO; el codigo tiene ADMIN y EMPLEADO) y tipos de cuenta (el diccionario agrega DIGITAL).
* Tipos fisicos frente a los del codigo: identificadores (INTEGER contra Long), montos (NUMERIC contra Double), porcentajes (5,2 contra fracciones), fechas (TIMESTAMP en UTC contra LocalDate).
* Unicos que el codigo ya aplica y el diccionario no lista: nombre de cuenta y numero de factura de compra por tenant.
