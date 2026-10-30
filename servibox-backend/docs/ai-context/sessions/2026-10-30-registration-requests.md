# Solicitudes de registro y revision de plataforma (tarea 4A), 2026-10-30

Solo backend. Sin frontend, AGENTS.md, V1 a V8, tablas comerciales, commit, push ni PR.
Rama feat/45-registration-requests-review, libre local y remota, creada desde main en
b6021de (merge PR 44), igual a origin/main tras fetch. Los dos .idea ajenos conservan
sus hashes iniciales y quedan fuera.

## Implementado

* V9 SOLICITUDES_REGISTRO, ver [modelo de datos](../04-DATA-MODEL.md). Reserva vigente
  en slug_reservado con UNIQUE y CHECK ck_solicitud_reserva, equivalente portable del
  indice unico parcial (H2 no admite indices con WHERE). Primera version del CHECK usaba
  `slug_reservado = slug_solicitado` y aceptaba PENDING con NULL (comparacion desconocida);
  lo detecto una prueba directa en PostgreSQL 16 antes de cerrar y se corrigio con
  `IS NOT NULL`. Una prueba JUnit nueva cubre ahora las defensas de la base.
* Paquete `registration`: entidad, repositorio, servicio, controlador publico y controlador
  de plataforma. TenantRegistrationService expone validarSlug y validarResponsable con los
  mismos mensajes; su validacion interna los usa sin cambiar comportamiento.
* Seguridad: POST `/api/registration-requests` publico en la cadena tenant; en plataforma
  GET listado y detalle, POST approve y reject con PLATFORM_IDENTITY. Nada mas se abre.

## Contrato

POST `/api/registration-requests`, header `Idempotency-Key` obligatorio.
Cuerpo: businessName, nit, phone, slug, adminFullName, adminEmail, acceptTerms.
202 `{"requestId":"<uuid>","status":"PENDING","message":"Solicitud recibida, pendiente de revision"}`.
Compuerta apagada: 404 `Recurso no encontrado`. Con JSON mal formado la conversion
responde 400 antes del controlador, aun apagada (limitacion conocida).

GET `/api/platform/registration-requests?status=&page=0&size=25` (size 1 a 100):
`{items,page,size,totalElements,totalPages}`; items sin matches.
GET `/api/platform/registration-requests/{id}`: id, status, businessName, nit, phone,
slug, contactName, contactEmail, createdAt, reservationExpiresAt, reservationExpired,
reviewedAt, reviewerId, rejectionReason, version y matches {requestsWithSameNit,
requestsWithSameEmail, tenantsWithSameNit}. Nunca clave de reintento, hash ni datos
comerciales. Fechas UTC sin zona, precision de microsegundos.
POST `/{id}/approve` `{"expectedVersion":n}` y POST `/{id}/reject`
`{"expectedVersion":n,"reason":"..."}`: 200 con el detalle.

Errores, formato `{"error","status"}`. Mensajes nuevos para el mapa del frontend:

* 400 `La clave de reintento es obligatoria`
* 400 `La clave de reintento no es valida`
* 400 `La solicitud no admite contrasena`
* 400 `Paginacion invalida`
* 400 `La version esperada es obligatoria`
* 400 `El motivo de rechazo es obligatorio`
* 400 `El motivo de rechazo no puede superar 500 caracteres`
* 404 `Solicitud no encontrada`
* 409 `El codigo solicitado no esta disponible`
* 409 `La clave de reintento ya se uso con otros datos`
* 409 `La solicitud ya no permite esta operacion`
* 409 `El recurso cambio; actualiza e intenta de nuevo`
* Interno de arranque: `Plazos de solicitudes de registro invalidos`

Se reutilizan los 400 del registro actual, `Recurso no encontrado` (404), `Acceso
denegado` (403), 401 de la cadena y el 409 generico de bloqueo no obtenido. Con
solicitudes abiertas el registro inmediato responde 403 `El registro de nuevas
servitecas esta deshabilitado`.

## Pruebas

Conteos separados por motor; no se suman entre si.

* JUnit H2, base en main sin cambios: 534, cero fallos, errores u omitidas.
* JUnit H2, suite completa final: 557 = 534 base + 23 nuevas, cero fallos, errores u
  omitidas, 03:24 min. Nuevas: 16 RegistrationRequestTest (validaciones, idempotencia,
  clave vencida, slug de tenant existente, reserva y liberacion, vencimiento, registro
  inmediato cerrado, listado/detalle, filtros, aprobar sin tenant con reintento, version
  vieja, motivo, restricciones de la base, autoridades), 5 RegistrationRequestConcurrencyTest
  (10 hilos: mismo slug, misma clave, doble aprobacion, aprobar contra rechazar, version
  vieja) y 2 RegistrationRequestsDisabledTest (compuerta apagada 404, registro inmediato
  201 intacto).
* Ajustadas, sin retirar aserciones: SchemaConformanceTest y
  FlywayMigrationTest.lasMigracionesAplicanLimpiasSobreUnaBaseVacia esperan 16 tablas.
* Autoridades observadas: sin token 401; JWT tenant en rutas de plataforma 401 (la
  cadena de plataforma no lo autentica); metodos o rutas de plataforma no abiertos 403
  `Acceso denegado`; JWT de plataforma en rutas comerciales 401.
* PG16 (evidencia externa, PostgreSQL 16.15 en contenedor desechable con puerto solo
  en 127.0.0.1): Flyway V1 a V9 sobre base vacia y validacion Hibernate correctas.
  RegistrationRequestTest 16/16 y RegistrationRequestConcurrencyTest 5/5 en la corrida 1;
  la clase de concurrencia se repitio dos veces mas, 5/5 cada una. Sondas SQL directas:
  UNIQUE de reserva rechaza segunda reserva vigente, CHECK rechaza PENDING sin reserva
  (tras la correccion). Una corrida previa en otro contenedor (20/20) uso el CHECK
  defectuoso y queda descartada; las cifras anteriores son de un contenedor nuevo con V9
  corregida. Ambos contenedores y sus volumenes anonimos eliminados.
* Compilacion incluida en las corridas; no hay lint backend configurado.

## Limitaciones y corte 4A/4B

* Plazos 7 d, 30 d y 48 h son propuesta no confirmada por David; configurables.
* Vencimiento perezoso: una PENDING vencida sigue PENDING (con reservationExpired true)
  hasta que otra solicitud pide su slug; no hay limpieza programada de claves vencidas.
* La solicitud no guarda username del administrador: 4B debe pedirlo o ampliar V9.
  No hay version de terminos; solo se exige acceptTerms true.
* Carrera tenant contra solicitud: el registro inmediato queda cerrado con la compuerta
  abierta; 4B debe reservar/consumir el slug en la misma transaccion que crea el tenant.
* Palabras reservadas iguales al registro actual; `plataforma`, `platform` y `activar`
  no se agregaron (propuesta de la auditoria, fuera de "validaciones iguales").
* Sin limite de tasa (429) ni ACTIVATED; sin correo, outbox, token, tenant ni ADMIN.
* La auditoria proponia cambiar `/api/auth/register`; prevalece la ruta nueva pedida.
