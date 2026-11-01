# Modelo de datos objetivo

Fuente: Diccionario de Datos de ServiBox (informe UPTC-EISC-SB-01) y su diagrama entidad-relacion, 14 entidades en tercera forma normal. Este archivo es la especificacion del esquema al que debe converger el codigo.

**Estado.** El refactor de normalizacion termino (ver `sessions/2026-10-06-refactor-normalizacion.md`): las 14 tablas comerciales parten de Flyway `V1__init_schema.sql`, escrita desde este archivo; V2 a V7 las evolucionan. V8 agrega la identidad global de plataforma, V9 las solicitudes de registro y V10 la activacion de cuentas y el outbox de correo, y V11 la auditoria de plataforma, para 19 tablas. V12 amplia la auditoria al reenvio de activacion (sin tablas nuevas). `schema.SchemaConformanceTest` compara la base con este archivo. Donde el codigo y este archivo difieran, este archivo manda, salvo lo anotado en "Decisiones de implementacion" y "Pendientes" al final.

## Reglas generales

* Multi-tenant: base compartida y esquema compartido. La columna `id_tenant` esta en todas las tablas excepto `TENANTS`, `CATEGORIA_IMPUESTOS`, `ADMINISTRADORES_PLATAFORMA` (V8, identidad global adicional a las 14 tablas comerciales) `SOLICITUDES_REGISTRO` (V9, global, sin datos comerciales), `ACTIVACIONES_REGISTRO` y `CORREOS_SALIENTES` (V10, globales) y `AUDITORIA_PLATAFORMA` (V11, global; nombra al tenant afectado en `id_tenant_objetivo`, sin FK; desde V12 el reenvio nombra la solicitud en `id_solicitud_objetivo`). En la capa JPA el aislamiento lo da el `@Filter` de Hibernate.
* Unicos compuestos con `id_tenant`: `(id_tenant, username)` en USUARIOS, `(id_tenant, nombre_cuenta)` en CUENTAS, `(id_tenant, numero_documento)` en TERCEROS, `(id_tenant, id_tercero, numero_factura_compra)` en COMPRAS (desde V3; antes `(id_tenant, numero_factura_compra)`), `(id_tenant, numero_factura_venta)` en VENTAS, `(id_tenant, codigo_producto)` en PRODUCTOS.
* Motor: PostgreSQL 16. Tipos fisicos: identificadores y claves foraneas `BIGINT`; texto `VARCHAR(n)`; valores de enum `VARCHAR(n)` con su longitud, no tipo ENUM; montos en COP `NUMERIC(14,2)`; tasas y porcentajes como fraccion `NUMERIC(8,6)` (0.19 es 19 por ciento); fechas de negocio (`fecha_movimiento`, `fecha_compra`, `fecha_vencimiento_compra`, `fecha_venta`, `fecha_vencimiento_venta`) `DATE`; fechas de auditoria (`created_at`, `updated_at`, `fecha_creacion_tenant`) `TIMESTAMP WITHOUT TIME ZONE` en UTC; banderas `BOOLEAN`.
* Integridad referencial: `ON DELETE RESTRICT` en todas las claves foraneas: nada con historial transaccional se elimina.
* Normalizacion aplicada: (1) `MOVIMIENTOS` unifica gastos operativos, ingresos ocasionales, transferencias, pagos de compras y recaudos de ventas; `tipo_movimiento` da el flujo y `categoria_movimiento` el concepto. (2) `TERCEROS` unifica clientes y proveedores; `tipo_tercero` vale CLIENTE, PROVEEDOR o AMBOS.
* Consecuencia de (1): ya no existen las tablas de abonos, pagos, transferencias, ingresos ocasionales ni gastos operativos. Tampoco `VENTAS` ni `COMPRAS` guardan la cuenta: la cuenta de un cobro o pago vive en el movimiento.

## Entidades

### TENANTS

Modulo: Multi-tenancy y administracion.

| Campo | Tipo Dato | Longitud | Nulidad | Clave | Descripcion Tecnica y Regla de Negocio |
|-|-|-|-|-|-|
| id_tenant | BIGINT (I) | 8 bytes | NOT NULL | PK | Identificador unico de la empresa o taller registrado. |
| nombre_tenant | VARCHAR | 150 | NOT NULL | - | Razon social o denominacion comercial de la serviteca. |
| nombre_busqueda_tenant | VARCHAR | 450 | NOT NULL | - | V13. nombre_tenant normalizado con SearchText (sin marcas diacriticas, minusculas, espacios colapsados; la enie queda como n). Lo mantiene la aplicacion en setName; es contra lo que busca la plataforma. No se expone en la API. |
| slug_tenant | VARCHAR | 100 | NOT NULL | UK | Identificador alfanumerico unico para subdominio web y enrutamiento. |
| nit_tenant | VARCHAR | 25 | NULL | - | NIT de la serviteca, digitos con guion y digito de verificacion opcionales (900123456-7). Obligatorio en el registro y en PUT /api/tenant, pero NULL en la base: null en los tenants anteriores (V2) hasta que editen su perfil. |
| telefono_tenant | VARCHAR | 25 | NULL | - | Telefono de contacto de la serviteca: digitos, espacios, +, - y parentesis. Obligatorio en el registro y en PUT /api/tenant, pero NULL en la base: null en los tenants anteriores (V2) hasta que editen su perfil. |
| activo_tenant | BOOLEAN | 1 byte | NOT NULL | - | Estado de la cuenta: true para activa (ACTIVE), false para suspendida (SUSPENDED). Lo cambia la plataforma (V11). |
| version_tenant | BIGINT | 8 bytes | NOT NULL | - | V11. Version para expectedVersion de la plataforma; sube solo al suspender o reactivar. No negativa (CHECK). |
| fecha_suspension_tenant | TIMESTAMP (D) | 8 bytes | NULL | - | V11. Fecha de la suspension vigente (UTC). NULL si el tenant esta activo (CHECK ck_tenant_suspension). |
| motivo_suspension_tenant | VARCHAR | 500 | NULL | - | V11. Motivo de la suspension vigente. NULL si el tenant esta activo; NULL tambien en un tenant inactivo desde antes de V11. |
| fecha_creacion_tenant | TIMESTAMP (D) | 8 bytes | NOT NULL | - | Fecha y hora de registro de la suscripcion (UTC). |

### USUARIOS

Modulo: Multi-tenancy y administracion.

| Campo | Tipo Dato | Longitud | Nulidad | Clave | Descripcion Tecnica y Regla de Negocio |
|-|-|-|-|-|-|
| id_usuario | BIGINT (I) | 8 bytes | NOT NULL | PK | Identificador secuencial del usuario. |
| id_tenant | BIGINT (I) | 8 bytes | NOT NULL | FK | FK a TENANTS(id_tenant). Aislamiento del usuario por taller. |
| username | VARCHAR | 50 | NOT NULL | UK | Nombre de usuario unico por taller (UK compuesta con id_tenant). |
| password_hash | VARCHAR | 255 | NOT NULL | - | Hash criptografico de la contrasena calculado con BCrypt. |
| email | VARCHAR | 150 | NULL | - | Direccion de correo electronico del usuario para soporte y notificaciones. |
| nombre_completo | VARCHAR | 150 | NULL | - | Nombre para mostrar del usuario. Lo pide el registro publico para el administrador; null en los usuarios anteriores (V2). |
| activo | BOOLEAN | 1 byte | NOT NULL | - | Indica si la cuenta esta habilitada para autenticacion e inicio de sesion. |
| rol | VARCHAR | 30 | NOT NULL | - | Rol RBAC asignado para autorizacion de funciones (ADMIN, MECANICO, CAJERO). |
| auth_version | BIGINT | 8 bytes | NOT NULL | - | V11. Version de sesion, inicial 0, no negativa (CHECK). Debe coincidir con el claim authVersion del JWT en cada peticion; revocar o suspender la sube. Nunca se devuelve por HTTP. |
| created_at | TIMESTAMP (D) | 8 bytes | NOT NULL | - | Fecha y hora de registro del usuario (UTC). |
| updated_at | TIMESTAMP (D) | 8 bytes | NOT NULL | - | Fecha y hora de ultima modificacion en credenciales o perfil (UTC). |

### ADMINISTRADORES_PLATAFORMA

Modulo: Identidad global de plataforma, V8. Sin tenant ni rol comercial.

| Campo | Tipo Dato | Longitud | Nulidad | Clave | Descripcion Tecnica y Regla de Negocio |
|-|-|-|-|-|-|
| id_administrador | BIGINT | 8 bytes | NOT NULL | PK | Identificador estable; bootstrap reserva 1 con INSERT, sin reemplazo. |
| email | VARCHAR | 150 | NOT NULL | UK | Normalizado con strip y minusculas; unico global. CHECK LOWER(TRIM(email)). |
| password_hash | VARCHAR | 255 | NOT NULL | - | Hash BCrypt privado. Nunca se devuelve por HTTP. |
| activo | BOOLEAN | 1 byte | NOT NULL | - | Revalidado en cada peticion de plataforma. |
| auth_version | BIGINT | 8 bytes | NOT NULL | - | No negativo, inicial 0; debe coincidir con el JWT en cada peticion. |

### SOLICITUDES_REGISTRO

Modulo: Solicitudes de registro, V9 (tarea 4A). Global, sin tenant, sin contrasena ni usuario. Fechas en UTC.

| Campo | Tipo Dato | Longitud | Nulidad | Clave | Descripcion Tecnica y Regla de Negocio |
|-|-|-|-|-|-|
| id_solicitud | VARCHAR | 36 | NOT NULL | PK | UUID aleatorio generado por la aplicacion; opaco, no revela volumen. |
| nombre_comercial | VARCHAR | 150 | NOT NULL | - | Razon social o nombre comercial, recortado. Mismas reglas que el registro. |
| nombre_comercial_busqueda | VARCHAR | 450 | NOT NULL | - | V13. nombre_comercial normalizado con SearchText; lo mantiene la aplicacion. Filtro tenantName. No se expone en la API. |
| nit | VARCHAR | 25 | NOT NULL | - | NIT recortado, mismas reglas que el registro. No es unico: solo coincidencia indicativa. |
| telefono | VARCHAR | 25 | NOT NULL | - | Telefono recortado, mismas reglas que el registro. |
| nombre_responsable | VARCHAR | 150 | NOT NULL | - | Nombre completo del responsable que sera administrador. |
| nombre_responsable_busqueda | VARCHAR | 450 | NOT NULL | - | V13. nombre_responsable normalizado con SearchText; lo mantiene la aplicacion. Filtro contactName. No se expone en la API. |
| correo_responsable | VARCHAR | 150 | NOT NULL | - | Correo de entrega tal como llego, recortado. |
| correo_normalizado | VARCHAR | 150 | NOT NULL | - | Correo en minusculas para coincidencias indicativas; no es unico. CHECK LOWER(TRIM). |
| slug_solicitado | VARCHAR | 30 | NOT NULL | - | Slug normalizado (recortado, minusculas) con las reglas del registro. Se conserva siempre. |
| slug_reservado | VARCHAR | 30 | NULL | UK | Reserva vigente: igual a slug_solicitado en PENDING y APPROVED, NULL en REJECTED, CANCELLED y ACTIVATED (CHECK ck_solicitud_reserva). Su UNIQUE equivale al indice unico parcial por estado. |
| estado | VARCHAR | 20 | NOT NULL | - | PENDING, APPROVED, REJECTED, CANCELLED o ACTIVATED (CHECK, ACTIVATED desde V10). ACTIVATED deja slug_reservado NULL: el slug pasa al tenant. |
| version | BIGINT | 8 bytes | NOT NULL | - | Version optimista para expectedVersion; sube en cada cambio. No negativa. |
| clave_idempotencia | VARCHAR | 100 | NULL | UK | Header Idempotency-Key del envio. Se pone NULL cuando vence y se reutiliza. |
| hash_cuerpo | VARCHAR | 64 | NOT NULL | - | SHA-256 hexadecimal del cuerpo recibido; distingue reintento de cuerpo distinto. |
| idempotencia_expira_en | TIMESTAMP (D) | 8 bytes | NOT NULL | - | Fin de la retencion de la clave (propuesta 48 h). |
| fecha_creacion | TIMESTAMP (D) | 8 bytes | NOT NULL | - | Fecha y hora de recepcion. |
| reserva_expira_en | TIMESTAMP (D) | 8 bytes | NOT NULL | - | Vencimiento de la reserva: creacion mas 7 dias en PENDING, aprobacion mas 30 dias en APPROVED (propuesta). |
| fecha_revision | TIMESTAMP (D) | 8 bytes | NULL | - | Fecha y hora de aprobacion o rechazo. |
| id_revisor | BIGINT | 8 bytes | NULL | FK | FK a ADMINISTRADORES_PLATAFORMA(id_administrador) que aprobo o rechazo. |
| motivo_rechazo | VARCHAR | 500 | NULL | - | Obligatorio en REJECTED (CHECK). |
| id_tenant_activado | BIGINT | 8 bytes | NULL | UK, FK | V10. FK a TENANTS(id_tenant) creado al activar; solo y siempre en ACTIVATED (CHECK ck_solicitud_activada). Unico: una solicitud crea un tenant. |
| version_politica | VARCHAR | 10 | NULL | - | V14. Version de la politica y terminos aceptada (AAAA-MM-DD). NULL solo en filas anteriores a V14; toda solicitud nueva la lleva. Va junto con fecha_aceptacion_politica (CHECK ck_solicitud_politica). |
| fecha_aceptacion_politica | TIMESTAMP (D) | 8 bytes | NULL | - | V14. Fecha y hora del servidor, UTC, en que se acepto la politica. Sin IP ni agente de usuario. |

### ACTIVACIONES_REGISTRO

Modulo: Activacion de cuentas, V10 (tarea 4B). Global, sin tenant. Una fila por enlace emitido (aprobar o reenviar). Fechas en UTC.

| Campo | Tipo Dato | Longitud | Nulidad | Clave | Descripcion Tecnica y Regla de Negocio |
|-|-|-|-|-|-|
| id_activacion | BIGINT | 8 bytes | NOT NULL | PK | Identificador autoincremental. |
| id_solicitud | VARCHAR | 36 | NOT NULL | FK | FK a SOLICITUDES_REGISTRO. |
| generacion | INTEGER | 4 bytes | NOT NULL | - | 1 al aprobar, +1 por reenvio (CHECK >= 1). La unicidad por solicitud la da el bloqueo de la solicitud al emitir. |
| digest_token | VARCHAR | 64 | NOT NULL | UK | SHA-256 hexadecimal del token opaco de 32 bytes. El token crudo no se guarda en ninguna tabla. |
| fecha_creacion | TIMESTAMP (D) | 8 bytes | NOT NULL | - | Emision; base del intervalo y del cupo de reenvios. |
| expira_en | TIMESTAMP (D) | 8 bytes | NOT NULL | - | Emision mas la vigencia del token (propuesta 24 h). |
| fecha_consumo | TIMESTAMP (D) | 8 bytes | NULL | - | Activacion exitosa. Excluyente con fecha_revocacion (CHECK). |
| fecha_revocacion | TIMESTAMP (D) | 8 bytes | NULL | - | Reemplazado por un reenvio. |

### CORREOS_SALIENTES

Modulo: Outbox de correo, V10 (tarea 4B). Global, sin tenant. Fechas en UTC.

| Campo | Tipo Dato | Longitud | Nulidad | Clave | Descripcion Tecnica y Regla de Negocio |
|-|-|-|-|-|-|
| id_correo | BIGINT | 8 bytes | NOT NULL | PK | Identificador autoincremental. |
| id_activacion | BIGINT | 8 bytes | NOT NULL | FK | FK a ACTIVACIONES_REGISTRO: enlace que lleva el correo; reenviar o activar cancela lo pendiente. |
| destinatario | VARCHAR | 150 | NOT NULL | - | Correo del responsable de la solicitud. |
| plantilla | VARCHAR | 30 | NOT NULL | - | ACTIVATION (CHECK). El asunto sale de la plantilla. |
| payload_cifrado | VARCHAR | 4000 | NULL | - | Cuerpo con el enlace, AES-256-GCM con MAIL_OUTBOX_ENCRYPTION_KEY, Base64. Solo en PENDING (CHECK); se borra al enviar, fallar, revocar, consumir o vencer. |
| estado | VARCHAR | 10 | NOT NULL | - | PENDING, SENT (aceptado por el transporte, no entregado al buzon) o FAILED (CHECK). |
| intentos | INTEGER | 4 bytes | NOT NULL | - | Reclamos del worker; no negativo. |
| proximo_intento | TIMESTAMP (D) | 8 bytes | NOT NULL | - | Reintento con retroceso exponencial. |
| reclamado_hasta | TIMESTAMP (D) | 8 bytes | NULL | - | Lease del worker que lo reclamo; vencido, otro worker puede retomarlo. |
| expira_en | TIMESTAMP (D) | 8 bytes | NOT NULL | - | Igual al vencimiento del token: despues el mensaje pasa a FAILED sin cuerpo. |
| ultimo_error | VARCHAR | 100 | NULL | - | Categoria sin datos sensibles: TRANSPORTE_TEMPORAL, TRANSPORTE_PERMANENTE, INTENTOS_AGOTADOS, PAYLOAD_INVALIDO, VENCIDO, REVOCADO, CONSUMIDO. |
| fecha_creacion | TIMESTAMP (D) | 8 bytes | NOT NULL | - | Encolado, en la transaccion de aprobar o reenviar. |
| fecha_envio | TIMESTAMP (D) | 8 bytes | NULL | - | Aceptacion por el transporte; solo en SENT (CHECK). |

### AUDITORIA_PLATAFORMA

Modulo: Auditoria de plataforma, V11 (tarea 5A) y V12 (tarea 51, reenvio de activacion). Global,
solo insercion. Fechas en UTC. CHECK ck_auditoria_objetivo (V12): RESEND_ACTIVATION lleva solo
id_solicitud_objetivo; las acciones sobre tenants llevan id_tenant_objetivo y nunca solicitud.

| Campo | Tipo Dato | Longitud | Nulidad | Clave | Descripcion Tecnica y Regla de Negocio |
|-|-|-|-|-|-|
| id_auditoria | BIGINT | 8 bytes | NOT NULL | PK | Identificador autoincremental. |
| accion | VARCHAR | 30 | NOT NULL | - | SUSPEND_TENANT, REACTIVATE_TENANT, REVOKE_USER_SESSIONS, REVOKE_TENANT_SESSIONS o RESEND_ACTIVATION (CHECK). |
| id_administrador | BIGINT | 8 bytes | NOT NULL | FK | FK a ADMINISTRADORES_PLATAFORMA(id_administrador) que ejecuto la accion. |
| id_tenant_objetivo | BIGINT | 8 bytes | NULL | - | Tenant afectado; NULL solo en RESEND_ACTIVATION (desde V12). Sin FK a proposito: el registro sobrevive a un borrado permanente. |
| id_usuario_objetivo | BIGINT | 8 bytes | NULL | - | Usuario afectado en REVOKE_USER_SESSIONS; NULL en las demas. Sin FK por el mismo motivo. |
| motivo | VARCHAR | 500 | NULL | - | Motivo recortado; obligatorio solo al suspender (lo exige el servicio). |
| id_solicitud_objetivo | VARCHAR | 36 | NULL | - | Solicitud de registro afectada en RESEND_ACTIVATION (V12); NULL en las demas. Sin FK por el mismo motivo. |
| fecha_auditoria | TIMESTAMP (D) | 8 bytes | NOT NULL | - | Momento de la accion. |

### CONFIGURACION_FINANCIERA

Modulo: Multi-tenancy y administracion.

| Campo | Tipo Dato | Longitud | Nulidad | Clave | Descripcion Tecnica y Regla de Negocio |
|-|-|-|-|-|-|
| id_configuracion | BIGINT (I) | 8 bytes | NOT NULL | PK | Identificador unico de la configuracion financiera. |
| id_tenant | BIGINT (I) | 8 bytes | NOT NULL | FK, UK | FK a TENANTS(id_tenant). Relacion 1:1 estricta por taller. |
| porcentaje_gastos | NUMERIC | 8,6 | NOT NULL | - | Provision estimada para gastos operativos, como fraccion de 0 a 1 (0.19 es 19 por ciento). |
| porcentaje_dian | NUMERIC | 8,6 | NOT NULL | - | Reserva para obligaciones fiscales de la DIAN, como fraccion de 0 a 1 (0.19 es 19 por ciento). |
| porcentaje_ica | NUMERIC | 8,6 | NOT NULL | - | Provision para el Impuesto de Industria y Comercio municipal, como fraccion de 0 a 1; el ICA es por mil (0.0082 es 8.2 por mil). |
| porcentaje_comision_tarjeta | NUMERIC | 8,6 | NOT NULL | - | Comision deducida por datafonos o pasarelas, como fraccion de 0 a 1 (0.19 es 19 por ciento). |
| created_at | TIMESTAMP (D) | 8 bytes | NOT NULL | - | Fecha y hora de creacion de la parametrizacion contable (UTC). |
| updated_at | TIMESTAMP (D) | 8 bytes | NOT NULL | - | Fecha y hora de ultima modificacion de porcentajes (UTC). |

### CUENTAS

Modulo: Tesoreria.

| Campo | Tipo Dato | Longitud | Nulidad | Clave | Descripcion Tecnica y Regla de Negocio |
|-|-|-|-|-|-|
| id_cuenta | BIGINT (I) | 8 bytes | NOT NULL | PK | Identificador unico de la cuenta de tesoreria. |
| id_tenant | BIGINT (I) | 8 bytes | NOT NULL | FK | FK a TENANTS(id_tenant). Aislamiento de la cuenta por taller. |
| nombre_cuenta | VARCHAR | 100 | NOT NULL | UK | Nombre descriptivo de la cuenta (Caja General, Bancolombia, Nequi). Unico por taller (UK compuesta con id_tenant). |
| balance_inicial | NUMERIC | 14,2 | NOT NULL | - | Saldo de apertura al registrar la cuenta en el sistema (en COP). |
| saldo_actual | NUMERIC | 14,2 | NOT NULL | - | Saldo disponible en tiempo real, recalculado por movimientos de fondos. |
| tipo_cuenta | VARCHAR | 30 | NOT NULL | - | Tipo de cuenta financiera (EFECTIVO, BANCO, DIGITAL). |
| color_cuenta | VARCHAR | 7 | NULL | - | Color hexadecimal opaco #RRGGBB; null en cuentas antiguas o sin color. Agregado en V7. |
| created_at | TIMESTAMP (D) | 8 bytes | NOT NULL | - | Fecha y hora de registro de la cuenta (UTC). |
| updated_at | TIMESTAMP (D) | 8 bytes | NOT NULL | - | Fecha y hora de ultima afectacion de saldo (UTC). |

### MOVIMIENTOS

Modulo: Tesoreria.

| Campo | Tipo Dato | Longitud | Nulidad | Clave | Descripcion Tecnica y Regla de Negocio |
|-|-|-|-|-|-|
| id_movimiento | BIGINT (I) | 8 bytes | NOT NULL | PK | Identificador unico del registro en el libro mayor de tesoreria. |
| id_tenant | BIGINT (I) | 8 bytes | NOT NULL | FK | FK a TENANTS(id_tenant). |
| id_cuenta | BIGINT (I) | 8 bytes | NOT NULL | FK | FK a CUENTAS(id_cuenta). Cuenta financiera afectada en la transaccion. |
| id_movimiento_relacionado | BIGINT (I) | 8 bytes | NULL | FK | FK autoreferencial a MOVIMIENTOS(id_movimiento). Vincula la contrapartida contable en transferencias internas entre cuentas. |
| id_compra | BIGINT (I) | 8 bytes | NULL | FK | FK a COMPRAS(id_compra). Vincula abonos y pagos a facturas de compra. |
| id_venta | BIGINT (I) | 8 bytes | NULL | FK | FK a VENTAS(id_venta). Vincula abonos y recaudos de facturas de venta. |
| tipo_movimiento | VARCHAR | 20 | NOT NULL | - | Direccion contable del flujo financiero (INGRESO, EGRESO). |
| categoria_movimiento | VARCHAR | 50 | NOT NULL | - | Categoria tecnica (GASTO_OPERATIVO, INGRESO_OCASIONAL, TRANSFERENCIA, PAGO_COMPRA, RECAUDO_VENTA). |
| monto | NUMERIC | 14,2 | NOT NULL | - | Valor monetario de la transaccion en pesos colombianos (monto > 0). |
| fecha_movimiento | DATE (D) | 4 bytes | NOT NULL | - | Fecha efectiva de la operacion financiera. |
| concepto | VARCHAR | 255 | NOT NULL | - | Descripcion detallada que justifica contablemente la transaccion. |
| notas | VARCHAR | 255 | NULL | - | Observaciones u anotaciones complementarias de auditoria. |
| created_at | TIMESTAMP (D) | 8 bytes | NOT NULL | - | Fecha y hora de persistencia del movimiento (UTC). |
| updated_at | TIMESTAMP (D) | 8 bytes | NOT NULL | - | Fecha y hora de ultima modificacion (UTC). |

### TERCEROS

Modulo: Comercial.

| Campo | Tipo Dato | Longitud | Nulidad | Clave | Descripcion Tecnica y Regla de Negocio |
|-|-|-|-|-|-|
| id_tercero | BIGINT (I) | 8 bytes | NOT NULL | PK | Identificador unico de la persona natural o juridica en el sistema. |
| id_tenant | BIGINT (I) | 8 bytes | NOT NULL | FK | FK a TENANTS(id_tenant). Directorio comercial aislado por taller. |
| tipo_tercero | VARCHAR | 20 | NOT NULL | - | Rol comercial del actor en el taller (CLIENTE, PROVEEDOR, AMBOS). |
| nombre_razon_social | VARCHAR | 150 | NOT NULL | - | Nombre completo del cliente o razon social del proveedor. |
| tipo_documento | VARCHAR | 20 | NOT NULL | - | Tipo de identificacion fiscal (CC, NIT, CE, RUT, PASAPORTE). |
| numero_documento | VARCHAR | 25 | NOT NULL | UK | Numero de documento de identidad o NIT fiscal (UK compuesta con id_tenant). |
| correo | VARCHAR | 150 | NULL | - | Direccion de correo electronico para notificaciones y facturacion. |
| celular | VARCHAR | 25 | NULL | - | Telefono movil de contacto comercial. |
| created_at | TIMESTAMP (D) | 8 bytes | NOT NULL | - | Fecha y hora de registro del tercero (UTC). |
| updated_at | TIMESTAMP (D) | 8 bytes | NOT NULL | - | Fecha y hora de ultima modificacion del contacto (UTC). |

### COMPRAS

Modulo: Comercial.

| Campo | Tipo Dato | Longitud | Nulidad | Clave | Descripcion Tecnica y Regla de Negocio |
|-|-|-|-|-|-|
| id_compra | BIGINT (I) | 8 bytes | NOT NULL | PK | Identificador unico de la factura de adquisicion de mercancia. |
| id_tenant | BIGINT (I) | 8 bytes | NOT NULL | FK | FK a TENANTS(id_tenant). |
| id_tercero | BIGINT (I) | 8 bytes | NOT NULL | FK | FK a TERCEROS(id_tercero). Proveedor que expide la factura. |
| numero_factura_compra | VARCHAR | 50 | NOT NULL | UK | Consecutivo oficial de la factura fisica o electronica del proveedor (UK compuesta con id_tenant e id_tercero: unico por proveedor dentro del tenant). |
| fecha_compra | DATE (D) | 4 bytes | NOT NULL | - | Fecha de expedicion formal de la factura de compra. |
| fecha_vencimiento_compra | DATE (D) | 4 bytes | NULL | - | Fecha limite de pago acordada para compras a credito comercial. |
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
| id_detalle_compra | BIGINT (I) | 8 bytes | NOT NULL | PK | Identificador unico de la linea de item en la factura de compra. |
| id_tenant | BIGINT (I) | 8 bytes | NOT NULL | FK | FK a TENANTS(id_tenant). |
| id_compra | BIGINT (I) | 8 bytes | NOT NULL | FK | FK a COMPRAS(id_compra). Factura a la que pertenece el renglon. |
| id_producto | BIGINT (I) | 8 bytes | NOT NULL | FK | FK a PRODUCTOS(id_producto). Repuesto ingresado al inventario. |
| cantidad_compra | INTEGER (I) | 4 bytes | NOT NULL | - | Cantidad de unidades fisicas adquiridas (valor entero > 0). |
| precio_compra | NUMERIC | 14,2 | NOT NULL | - | Costo unitario de compra antes de impuestos en COP. |
| iva_linea_compra | NUMERIC | 14,2 | NOT NULL | - | Monto de IVA correspondiente a la totalidad de unidades del renglon. |
| created_at | TIMESTAMP (D) | 8 bytes | NOT NULL | - | Fecha y hora de creacion del renglon (UTC). |
| updated_at | TIMESTAMP (D) | 8 bytes | NOT NULL | - | Fecha y hora de ultima modificacion (UTC). |

### VENTAS

Modulo: Comercial.

| Campo | Tipo Dato | Longitud | Nulidad | Clave | Descripcion Tecnica y Regla de Negocio |
|-|-|-|-|-|-|
| id_venta | BIGINT (I) | 8 bytes | NOT NULL | PK | Identificador unico de la transaccion mercantil de venta. |
| id_tenant | BIGINT (I) | 8 bytes | NOT NULL | FK | FK a TENANTS(id_tenant). |
| id_tercero | BIGINT (I) | 8 bytes | NOT NULL | FK | FK a TERCEROS(id_tercero). Cliente receptor del servicio o repuesto. |
| numero_factura_venta | VARCHAR | 50 | NOT NULL | UK | Consecutivo de factura expedido al cliente (UK compuesta con id_tenant). |
| fecha_venta | DATE (D) | 4 bytes | NOT NULL | - | Fecha de expedicion formal de la factura. |
| fecha_vencimiento_venta | DATE (D) | 4 bytes | NULL | - | Fecha limite para cancelacion de ventas a credito comercial. |
| forma_pago_venta | VARCHAR | 20 | NOT NULL | - | Modalidad de cobro comercial: CONTADO o CREDITO. |
| medio_pago_venta | VARCHAR | 30 | NULL | - | Instrumento financiero: EFECTIVO, TARJETA_DEBITO, TARJETA_CREDITO, TRANSFERENCIA. |
| estado_venta | VARCHAR | 20 | NOT NULL | - | Estado operativo de la factura: PAGADA, PENDIENTE o ANULADA. |
| subtotal_venta | NUMERIC | 14,2 | NOT NULL | - | Base gravable de la venta antes de IVA en COP. |
| iva_por_pagar_venta | NUMERIC | 14,2 | NOT NULL | - | IVA neto a pagar a la DIAN en COP: suma de diferencia_iva_linea de las lineas, es decir IVA generado menos IVA descontable. |
| total_venta | NUMERIC | 14,2 | NOT NULL | - | Total final cobrado al cliente en COP: subtotal_venta mas el IVA generado (suma de iva_generado_linea de las lineas). |
| created_at | TIMESTAMP (D) | 8 bytes | NOT NULL | - | Fecha y hora de insercion para auditoria (UTC). |
| updated_at | TIMESTAMP (D) | 8 bytes | NOT NULL | - | Fecha y hora de ultima actualizacion (UTC). |

### DETALLE_VENTAS

Modulo: Comercial.

| Campo | Tipo Dato | Longitud | Nulidad | Clave | Descripcion Tecnica y Regla de Negocio |
|-|-|-|-|-|-|
| id_detalle_venta | BIGINT (I) | 8 bytes | NOT NULL | PK | Identificador unico de la linea de item en la factura de venta. |
| id_tenant | BIGINT (I) | 8 bytes | NOT NULL | FK | FK a TENANTS(id_tenant). |
| id_venta | BIGINT (I) | 8 bytes | NOT NULL | FK | FK a VENTAS(id_venta). Factura que agrupa esta linea transaccional. |
| id_producto | BIGINT (I) | 8 bytes | NOT NULL | FK | FK a PRODUCTOS(id_producto). Repuesto o servicio facturado. |
| cantidad_venta | INTEGER (I) | 4 bytes | NOT NULL | - | Unidades fisicas despachadas o servicios aplicados (> 0). |
| precio_venta | NUMERIC | 14,2 | NOT NULL | - | Precio de venta unitario final cobrado al cliente en COP. |
| iva_generado_linea | NUMERIC | 14,2 | NOT NULL | - | Impuesto al valor agregado derivado del renglon (base * tarifa). |
| diferencia_iva_linea | NUMERIC | 14,2 | NOT NULL | - | Diferencia fiscal entre el IVA generado y el IVA asumido en compra. |
| costo_unitario_linea | NUMERIC | 14,2 | NOT NULL | - | Costo unitario sin IVA del producto al facturar (>= 0), congelado: no cambia al anular o restaurar la venta ni al cambiar el costo del producto. Desde V4. |
| created_at | TIMESTAMP (D) | 8 bytes | NOT NULL | - | Fecha y hora de persistencia del renglon (UTC). |
| updated_at | TIMESTAMP (D) | 8 bytes | NOT NULL | - | Fecha y hora de ultima modificacion (UTC). |

### PRODUCTOS

Modulo: Inventario.

| Campo | Tipo Dato | Longitud | Nulidad | Clave | Descripcion Tecnica y Regla de Negocio |
|-|-|-|-|-|-|
| id_producto | BIGINT (I) | 8 bytes | NOT NULL | PK | Identificador unico del producto o repuesto en el catalogo. |
| id_tenant | BIGINT (I) | 8 bytes | NOT NULL | FK | FK a TENANTS(id_tenant). |
| id_categoria_producto | BIGINT (I) | 8 bytes | NOT NULL | FK | FK a CATEGORIA_PRODUCTOS(id_categoria_producto). |
| codigo_producto | VARCHAR | 50 | NOT NULL | UK | Codigo de barras o SKU del repuesto (UK compuesta con id_tenant). |
| descripcion_producto | VARCHAR | 255 | NOT NULL | - | Nombre y especificaciones tecnicas del repuesto o servicio. |
| costo_compra | NUMERIC | 14,2 | NOT NULL | - | Ultimo costo neto o promedio ponderado de compra en COP. |
| cantidad | INTEGER (I) | 4 bytes | NOT NULL | - | Saldo de existencias fisicas disponibles en almacen o bodega. |
| iva_producto | NUMERIC | 14,2 | NOT NULL | - | Monto de IVA por unidad en COP: costo_compra por la suma de las tasas de IVA (es_iva) de la categoria del producto, en pesos enteros (HALF_UP, .00). No es una tarifa. |
| precio_sugerido | NUMERIC | 14,2 | NOT NULL | - | Precio de venta recomendado calculado a partir de costo y margen, en pesos enteros (HALF_UP, .00). V5 redondeo los ya guardados. |
| created_at | TIMESTAMP (D) | 8 bytes | NOT NULL | - | Fecha y hora de registro del producto en el catalogo (UTC). |
| updated_at | TIMESTAMP (D) | 8 bytes | NOT NULL | - | Fecha y hora de modificacion de existencias o precios (UTC). |

### CATEGORIA_PRODUCTOS

Modulo: Inventario.

| Campo | Tipo Dato | Longitud | Nulidad | Clave | Descripcion Tecnica y Regla de Negocio |
|-|-|-|-|-|-|
| id_categoria_producto | BIGINT (I) | 8 bytes | NOT NULL | PK | Identificador unico de la categoria o familia de productos. |
| id_tenant | BIGINT (I) | 8 bytes | NOT NULL | FK | FK a TENANTS(id_tenant). |
| nombre_categoria_producto | VARCHAR | 100 | NOT NULL | - | Denominacion de la familia (Llantas, Lubricantes, Baterias, Alineacion). |
| color_categoria | VARCHAR | 20 | NOT NULL | - | Codigo hexadecimal (#RRGGBB) para diferenciacion en la interfaz grafica. |
| stock_min_amarillo | INTEGER (I) | 4 bytes | NOT NULL | - | Umbral preventivo de existencias minimas para senalizacion de advertencia. |
| stock_min_rojo | INTEGER (I) | 4 bytes | NOT NULL | - | Umbral critico de stock que exige reposicion obligatoria. |
| margen_utilidad | NUMERIC | 8,6 | NOT NULL | - | Margen sugerido aplicado sobre el costo de adquisicion, como fraccion (0.30 es 30 por ciento). |
| created_at | TIMESTAMP (D) | 8 bytes | NOT NULL | - | Fecha y hora de creacion de la categoria (UTC). |
| updated_at | TIMESTAMP (D) | 8 bytes | NOT NULL | - | Fecha y hora de ultima modificacion de parametros (UTC). |

### TIPOS_IMPUESTO

Modulo: Tributario.

| Campo | Tipo Dato | Longitud | Nulidad | Clave | Descripcion Tecnica y Regla de Negocio |
|-|-|-|-|-|-|
| id_impuesto | BIGINT (I) | 8 bytes | NOT NULL | PK | Identificador unico del tributo o gravamen fiscal. |
| id_tenant | BIGINT (I) | 8 bytes | NOT NULL | FK | FK a TENANTS(id_tenant). |
| nombre_impuesto | VARCHAR | 100 | NOT NULL | - | Denominacion legal del gravamen (IVA General 19%, IVA 5%, Retefuente). |
| tasa_impuesto | NUMERIC | 8,6 | NOT NULL | - | Tasa impositiva nominal a aplicar sobre la base gravable, como fraccion (0.19 es 19 por ciento). |
| descripcion_impuesto | VARCHAR | 255 | NULL | - | Fundamentacion normativa segun el Estatuto Tributario colombiano. |
| aplica_transaccion | BOOLEAN | 1 byte | NOT NULL | - | Indica si el tributo se liquida de forma automatica en ventas. |
| es_iva | BOOLEAN | 1 byte | NOT NULL | - | Bandera que indica si el gravamen constituye IVA para efectos del balance fiscal. |
| created_at | TIMESTAMP (D) | 8 bytes | NOT NULL | - | Fecha y hora de registro del tributo (UTC). |
| updated_at | TIMESTAMP (D) | 8 bytes | NOT NULL | - | Fecha y hora de actualizacion de la tasa impositiva (UTC). |

### CATEGORIA_IMPUESTOS

Modulo: Tributario.

| Campo | Tipo Dato | Longitud | Nulidad | Clave | Descripcion Tecnica y Regla de Negocio |
|-|-|-|-|-|-|
| id_categoria_producto | BIGINT (I) | 8 bytes | NOT NULL | PK, FK | FK a CATEGORIA_PRODUCTOS(id_categoria_producto). Componente 1 de PK compuesta. |
| id_impuesto | BIGINT (I) | 8 bytes | NOT NULL | PK, FK | FK a TIPOS_IMPUESTO(id_impuesto). Componente 2 de PK compuesta. |

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
| SOLICITUDES_REGISTRO | id_revisor | ADMINISTRADORES_PLATAFORMA | N:1 (0..1) | Revisor de plataforma que aprobo o rechazo la solicitud. |
| SOLICITUDES_REGISTRO | id_tenant_activado | TENANTS | 1:1 (0..1) | Tenant creado al activar la solicitud (V10). |
| ACTIVACIONES_REGISTRO | id_solicitud | SOLICITUDES_REGISTRO | N:1 | Enlaces emitidos para la solicitud (V10). |
| CORREOS_SALIENTES | id_activacion | ACTIVACIONES_REGISTRO | N:1 | Correo que entrega ese enlace (V10). |
| AUDITORIA_PLATAFORMA | id_administrador | ADMINISTRADORES_PLATAFORMA | N:1 | Administrador que ejecuto la accion (V11). |

## Decisiones de implementacion

Decididas durante el refactor. Cada una se anota tambien en `03-DECISIONS.md`.

* **Direccion del movimiento.** `tipo_movimiento` solo toma INGRESO o EGRESO, porque es lo que da el signo sobre el saldo de la cuenta. TRANSFERENCIA es una `categoria_movimiento`, no un tipo. El diccionario original listaba TRANSFERENCIA entre los valores de `tipo_movimiento`, pero una transferencia no tiene direccion por si sola; la tabla MOVIMIENTOS de arriba ya esta corregida.
* **Transferencia.** Son dos movimientos con categoria TRANSFERENCIA: un EGRESO en la cuenta origen y un INGRESO en la cuenta destino. El INGRESO apunta al EGRESO con `id_movimiento_relacionado`. El `concepto` de las dos patas es el texto que escribe el usuario, sin espacios sobrantes; si no escribe ninguno, el autogenerado ("Transferencia a X" en el EGRESO y "Transferencia desde Y" en el INGRESO). `notas` queda vacio.
* **Enlace del movimiento con su factura.** Restriccion CHECK `ck_movimiento_factura` en MOVIMIENTOS: RECAUDO_VENTA lleva `id_venta` y no `id_compra`, PAGO_COMPRA lleva `id_compra` y no `id_venta`, y las demas categorias no llevan ninguna de las dos. El diccionario no la lista; el service valida lo mismo antes de escribir.
* **Montos y cantidades positivos.** Restricciones CHECK `ck_movimiento_monto` (`monto > 0` en MOVIMIENTOS; el signo lo da `tipo_movimiento`), `ck_detalle_compra_cantidad` (`cantidad_compra > 0`), `ck_detalle_venta_cantidad` (`cantidad_venta > 0`) y, desde V4, `ck_detalle_venta_costo` (`costo_unitario_linea >= 0`). Son las reglas que el diccionario escribe en la descripcion de esas columnas.
* **Porcentajes de la configuracion entre 0 y 1.** Restricciones CHECK `ck_configuracion_porcentaje_gastos`, `ck_configuracion_porcentaje_dian`, `ck_configuracion_porcentaje_ica` y `ck_configuracion_porcentaje_comision_tarjeta` (`BETWEEN 0 AND 1`) en CONFIGURACION_FINANCIERA. `margen_utilidad` y `tasa_impuesto` no llevan CHECK.
* **Tipos fisicos.** Corregidos en este archivo para que coincidan con el codigo, no al reves: identificadores `BIGINT` (el codigo usa `Long`; el diccionario original decia INTEGER); tasas y porcentajes como fraccion en `NUMERIC(8,6)` (el original pedia porcentajes de 0 a 100 en `NUMERIC(5,2)`, que pierde precision en el ICA, que es por mil); fechas de negocio `DATE` (el original decia TIMESTAMP en UTC, que llevaria al dia siguiente una venta de la noche en hora de Colombia); enums `VARCHAR` con su longitud. Las fechas de auditoria siguen en TIMESTAMP y se escriben en UTC.
* **Categorias manuales.** GASTO_OPERATIVO (EGRESO) e INGRESO_OCASIONAL (INGRESO) son los unicos movimientos que el usuario crea, edita y elimina directamente. Los de categoria TRANSFERENCIA, PAGO_COMPRA y RECAUDO_VENTA se gestionan desde su operacion.
* **Terceros.** Una persona o empresa existe una sola vez por tenant, identificada por `numero_documento`. Si ya existe como CLIENTE y se usa como proveedor (o al reves), pasa a AMBOS. Una venta exige un tercero CLIENTE o AMBOS; una compra, PROVEEDOR o AMBOS.
* **Tercero obligatorio en facturas.** Toda factura tiene `id_tercero`. Una venta que no identifica al cliente usa el tercero "Consumidor final" del tenant (CLIENTE, NIT 222222222222, el numero que usa la DIAN para consumidor final), que se crea la primera vez y despues se reutiliza; cada tenant tiene el suyo. Una compra exige siempre su proveedor: no hay proveedor por defecto.
* **Roles y tipos de cuenta.** `rol` toma ADMIN, MECANICO o CAJERO, y `tipo_cuenta` EFECTIVO, BANCO o DIGITAL, como en el diccionario. Los roles todavia no tienen reglas de permisos: solo viajan en el token.
* **Comision de tarjeta.** `porcentaje_comision_tarjeta` es obligatoria y vale 0 si el taller no la configura. Se guarda como fraccion, igual que los demas porcentajes de CONFIGURACION_FINANCIERA (ver Tipos fisicos); todavia no entra en ningun calculo. Una sola fila por tenant, con la restriccion unica `uk_configuracion_tenant`.
* **Costo de lo vendido.** `costo_unitario_linea` (V4) guarda en cada linea de venta el `costo_compra` del producto leido con el producto bloqueado al facturar; un costo nulo se guarda como 0. No se recalcula nunca. La utilidad bruta de kpis es ventas sin IVA (`subtotal_venta`) menos la suma de `costo_unitario_linea * cantidad_venta`, sin facturas ANULADA. V4 relleno las lineas anteriores con el costo del producto al migrar, no con el de su fecha.
* **Anular y restaurar facturas.** Anular una venta o compra no borra sus movimientos: solo revierte el saldo de cada cuenta y deja la factura en ANULADA. Un movimiento enlazado por `id_venta` o `id_compra` a una factura ANULADA es inactivo: no sale en extractos de cuenta, no cuenta en sumas y no se reporta. Esa condicion se deriva del estado de la factura, no se guarda en una columna. Restaurar reactiva esos movimientos y vuelve a aplicar los saldos; en compras exige saldo suficiente por cada egreso, igual que al crear o pagar. El estado resultante se deriva: contado PAGADA, credito segun el saldo pendiente con la tolerancia de un peso. El saldo pendiente se calcula con **todos** los movimientos de la factura, tambien los inactivos, porque restaurar lo necesita para derivar el estado; la API muestra 0 para una factura ANULADA, que no se debe. Invariante: `saldo_actual` es `balance_inicial` mas la suma de los movimientos activos de la cuenta. Los movimientos manuales (GASTO_OPERATIVO e INGRESO_OCASIONAL) no tienen factura y se siguen eliminando de verdad.

## Pendientes

Lo que sigue abierto despues del refactor (ver `sessions/2026-10-06-refactor-normalizacion.md`, Pendientes):

* `medio_pago_compra` y `medio_pago_venta` son texto libre en el codigo, mientras el diccionario lista valores permitidos (EFECTIVO, TRANSFERENCIA, CHEQUE en compras; EFECTIVO, TARJETA_DEBITO, TARJETA_CREDITO, TRANSFERENCIA en ventas). Todavia no se validan.
* El dinero sigue `Double` en las entidades y `NUMERIC(14,2)` en la base (mapeado con `@JdbcTypeCode(SqlTypes.NUMERIC)`). Desde fix/41 los importes definitivos se normalizan en Moneda con aritmetica decimal y HALF_UP a dos decimales antes de guardar y responder. Migrar las entidades a BigDecimal sigue fuera de alcance. Las lineas solo guardan precio unitario, cantidad e IVA: por decision aprobada, los precios nuevos con mas de dos decimales significativos se rechazan con 400 antes de escribir, sin ampliar columnas ni agregar subtotal por linea. Entrada BigDecimal; 0.0100 se acepta como 0.01. No se reinterpretan historicos.

## Indices de consulta desde V6

V6 agrega 18 indices no unicos, sin cambiar columnas, restricciones ni datos. Cubren
catalogos por tenant/nombre, documentos por tenant/fecha, movimientos por tenant/rango
y cuenta/rango, detalles por documento y producto, y FK inversas sin prefijo cubierto.
CUENTAS aprovecha su UNIQUE por tenant/nombre y la tabla puente su PK por categoria;
no se agregan indices simples redundantes sobre esos prefijos. No se agregan indices
indiscriminados para toda FK: por ejemplo, movimiento relacionado no tiene consulta
inversa frecuente ni borrado de transferencias. Columnas y motivo de cada indice en
[sesion backend polish](sessions/2026-10-25-backend-polish.md). PostgreSQL crea indices
de PK/UNIQUE, no de FK; H2 puede agregar indices automaticos de FK que no son V6.
